package com.nuvio.app.features.player.shaders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderPerformanceGuardTest {
    @Test
    fun higherHeadroomIsHotter() {
        val thermal = ThermalHysteresis()
        // Cool readings never lower anything.
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.2f, 0))
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.84f, 1_000))
        // 0.85 and above is hot: one level down.
        assertEquals(ThermalDecision.Lower, thermal.onHeadroom(0.86f, 2_000))
        assertEquals(1, thermal.levelsDropped)
        // Still hot a moment later: no second drop before the interval.
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.95f, 10_000))
        assertEquals(ThermalDecision.Lower, thermal.onHeadroom(0.95f, 32_000))
        assertEquals(2, thermal.levelsDropped)
    }

    @Test
    fun naNIsIgnored() {
        val thermal = ThermalHysteresis()
        assertEquals(ThermalDecision.None, thermal.onHeadroom(Float.NaN, 0))
        assertEquals(ThermalDecision.Lower, thermal.onHeadroom(0.9f, 1_000))
        // NaN neither cools nor heats: the cool-down timer does not start on it.
        repeat(120) { assertEquals(ThermalDecision.None, thermal.onHeadroom(Float.NaN, 2_000L + it * 1_000L)) }
        assertEquals(1, thermal.levelsDropped)
    }

    @Test
    fun qualityComesBackOnlyBelowTheLowerMarkAfterADelay() {
        val thermal = ThermalHysteresis()
        thermal.onHeadroom(0.9f, 0)
        // Between 0.70 and 0.85 nothing changes, however long.
        for (t in 1..200) assertEquals(ThermalDecision.None, thermal.onHeadroom(0.78f, t * 1_000L))
        // Below 0.70: back only after 60 s of it.
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.6f, 201_000))
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.6f, 250_000))
        // A warm reading in between restarts the delay.
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.75f, 255_000))
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.6f, 256_000))
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.6f, 300_000))
        assertEquals(ThermalDecision.Raise, thermal.onHeadroom(0.6f, 316_000))
        assertEquals(0, thermal.levelsDropped)
        // Nothing left to give back.
        assertEquals(ThermalDecision.None, thermal.onHeadroom(0.1f, 500_000))
    }

    @Test
    fun thermalStatusModerateCountsAsHot() {
        val thermal = ThermalHysteresis()
        assertEquals(ThermalDecision.None, thermal.onStatus(1, 0))
        assertEquals(ThermalDecision.Lower, thermal.onStatus(2, 1_000))
    }

    private fun frame(t: Long, late: Long, playing: Boolean = true, passes: Double = 5.0, frameTime: Double? = 41.7) =
        FrameSample(nowMs = t * 1_000L, playing = playing, lateFrames = late, passTotalMs = passes, frameTimeMs = frameTime)

    @Test
    fun steadilyRisingDropsMeanStruggling() {
        val monitor = FrameDropMonitor()
        // A single burst (a seek, a hiccup) is not steady.
        assertFalse(monitor.onSample(frame(0, 0)))
        assertFalse(monitor.onSample(frame(1, 20)))
        for (t in 2L..10L) assertFalse(monitor.onSample(frame(t, 20)))
        // Two or three frames every second is.
        var late = 20L
        val results = (11L..16L).map { t -> late += 3; monitor.onSample(frame(t, late)) }
        assertTrue(results.any { it })
        // Paused seconds do not count.
        val paused = FrameDropMonitor()
        assertFalse((0L..20L).any { t -> paused.onSample(frame(t, t * 5, playing = false)) })
    }

    @Test
    fun passesSlowerThanAFrameMeanStruggling() {
        val monitor = FrameDropMonitor()
        assertFalse(monitor.onSample(frame(0, 0, passes = 50.0)))
        assertFalse(monitor.onSample(frame(1, 0, passes = 50.0)))
        assertFalse(monitor.onSample(frame(2, 0, passes = 50.0)))
        assertTrue(monitor.onSample(frame(3, 0, passes = 50.0)))
        // Then it waits before judging again.
        assertFalse(monitor.onSample(frame(4, 0, passes = 50.0)))
    }

    private val plan = ShaderPlan(
        active = true,
        presetId = ShaderPresets.ANIME4K_A,
        quality = ShaderQuality.High,
        applyAlways = true,
        entries = emptyList(),
        options = MpvVideoOptions(),
    )

    @Test
    fun frameDropsEscalateQualityThenApplyAlwaysThenShaders() {
        val guard = ShaderPerformanceGuard(frames = FrameDropMonitor(cooldownMs = 0))
        var inputs = ShaderRuntimeInputs()
        var current = plan
        var t = 0L
        val steps = mutableListOf<GuardStep>()
        while (steps.size < 4 && t < 400) {
            val result = guard.onSample(current, inputs, ShaderQuality.High, headroom = 0.3f, thermalStatus = null, frame = frame(t, 0, passes = 60.0))
            t++
            if (result != null) {
                inputs = result.first
                steps += result.second
                current = ShaderController.plan(ShaderSettings(enabled = true, quality = ShaderQuality.High), inputs)
            }
        }
        assertEquals(
            listOf(
                GuardStep.QualityLowered(ShaderQuality.Balanced, byHeat = false),
                GuardStep.QualityLowered(ShaderQuality.Fast, byHeat = false),
                GuardStep.ApplyAlwaysSuspended,
                GuardStep.ShadersDisabled,
            ),
            steps,
        )
        assertTrue(inputs.applyAlwaysSuspended)
        assertEquals(ShaderSuspension.Performance, inputs.suspension)
        assertFalse(current.active)
    }

    @Test
    fun heatLowersAndCoolingRestoresUpToTheSetting() {
        val guard = ShaderPerformanceGuard()
        val settings = ShaderSettings(enabled = true, quality = ShaderQuality.Balanced)
        var inputs = ShaderRuntimeInputs()
        val first = guard.onSample(ShaderController.plan(settings, inputs), inputs, settings.quality, 0.9f, null, frame(0, 0))!!
        assertEquals(GuardStep.QualityLowered(ShaderQuality.Fast, byHeat = true), first.second)
        inputs = first.first
        assertEquals(ShaderQuality.Fast, inputs.qualityCap)
        var raised: Pair<ShaderRuntimeInputs, GuardStep>? = null
        var t = 1L
        while (raised == null && t < 200) {
            raised = guard.onSample(ShaderController.plan(settings, inputs), inputs, settings.quality, 0.5f, null, frame(t, 0))
            t++
        }
        assertEquals(GuardStep.QualityRaised(ShaderQuality.Balanced), raised!!.second)
        // Back at the user's quality, the cap is gone.
        assertNull(raised.first.qualityCap)
    }

    @Test
    fun inactiveShadersAreLeftAlone() {
        val guard = ShaderPerformanceGuard()
        assertNull(guard.onSample(plan.copy(active = false), ShaderRuntimeInputs(), ShaderQuality.High, 1.0f, 4, frame(0, 1000, passes = 100.0)))
    }
}
