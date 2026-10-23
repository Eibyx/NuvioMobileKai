package com.nuvio.app.features.player.shaders

import com.nuvio.app.core.logging.InAppLogger

private const val TAG = "Player/Shaders"

/** What one heat reading asks for. */
enum class ThermalDecision { None, Lower, Raise }

/**
 * Android's thermal headroom (PowerManager.getThermalHeadroom): HIGHER IS HOTTER, 1.0 is where the
 * device starts to throttle, NaN means "no reading" and is ignored. At [dropAt] or above, one
 * quality level goes (again after [dropIntervalMs] if it stays hot); only after [recoverDelayMs]
 * below [recoverBelow] does a level come back, one at a time. Between the two nothing changes.
 */
class ThermalHysteresis(
    private val dropAt: Float = 0.85f,
    private val recoverBelow: Float = 0.70f,
    private val dropIntervalMs: Long = 30_000L,
    private val recoverDelayMs: Long = 60_000L,
) {
    /** Quality levels taken away by heat and not yet given back. */
    var levelsDropped = 0
        private set
    private var lastDropAtMs: Long? = null
    private var coolSinceMs: Long? = null

    fun reset() {
        levelsDropped = 0
        lastDropAtMs = null
        coolSinceMs = null
    }

    fun onHeadroom(headroom: Float, nowMs: Long): ThermalDecision {
        if (headroom.isNaN() || headroom < 0f) return ThermalDecision.None
        if (headroom >= dropAt) {
            coolSinceMs = null
            val last = lastDropAtMs
            if (last != null && nowMs - last < dropIntervalMs) return ThermalDecision.None
            lastDropAtMs = nowMs
            levelsDropped++
            return ThermalDecision.Lower
        }
        if (headroom >= recoverBelow) {
            coolSinceMs = null
            return ThermalDecision.None
        }
        if (levelsDropped == 0) return ThermalDecision.None
        val coolSince = coolSinceMs ?: nowMs.also { coolSinceMs = it }
        if (nowMs - coolSince < recoverDelayMs) return ThermalDecision.None
        levelsDropped--
        coolSinceMs = nowMs
        lastDropAtMs = null
        return ThermalDecision.Raise
    }

    /**
     * PowerManager's thermal status, for devices whose headroom reads NaN: MODERATE (2) and above
     * count as hot, NONE and LIGHT (0, 1) as cool.
     */
    fun onStatus(status: Int, nowMs: Long): ThermalDecision =
        onHeadroom(if (status >= THERMAL_STATUS_MODERATE) 1f else 0f, nowMs)

    companion object {
        const val THERMAL_STATUS_MODERATE = 2
    }
}

/** One second of playback as the frame-drop guard sees it. */
data class FrameSample(
    val nowMs: Long,
    val playing: Boolean,
    /** mpv's frame-drop-count plus vo-delayed-frame-count, both counted since the file opened. */
    val lateFrames: Long,
    /** GPU time of the last frame's passes (vo-passes), in milliseconds. */
    val passTotalMs: Double,
    /** How long a frame may take: 1000 / fps. Null when the frame rate is unknown. */
    val frameTimeMs: Double?,
)

/**
 * Notices when the GPU cannot keep up: dropped or delayed frames rising over several seconds in a
 * row, or the passes taking longer than a frame for several seconds. Paused, buffering or seeking
 * seconds do not count. After it fires it waits [cooldownMs] before judging again.
 */
class FrameDropMonitor(
    private val windowSamples: Int = 6,
    private val minLateFrames: Long = 6,
    private val minRisingSamples: Int = 3,
    private val overBudgetSamples: Int = 4,
    private val cooldownMs: Long = 10_000L,
) {
    private var lastLate: Long? = null
    private val increments = ArrayDeque<Long>()
    private var overBudgetInARow = 0
    private var cooldownUntilMs = 0L

    fun reset() {
        lastLate = null
        increments.clear()
        overBudgetInARow = 0
        cooldownUntilMs = 0L
    }

    /** True when the GPU is struggling. */
    fun onSample(sample: FrameSample): Boolean {
        val previous = lastLate
        lastLate = sample.lateFrames
        if (!sample.playing) {
            increments.clear()
            overBudgetInARow = 0
            return false
        }
        if (sample.nowMs < cooldownUntilMs) return false
        if (previous != null) {
            increments.addLast((sample.lateFrames - previous).coerceAtLeast(0))
            while (increments.size > windowSamples) increments.removeFirst()
        }
        val frameTime = sample.frameTimeMs
        overBudgetInARow = if (frameTime != null && frameTime > 0 && sample.passTotalMs > frameTime) overBudgetInARow + 1 else 0
        val dropping = increments.sum() >= minLateFrames && increments.count { it > 0 } >= minRisingSamples
        if (!dropping && overBudgetInARow < overBudgetSamples) return false
        cooldownUntilMs = sample.nowMs + cooldownMs
        increments.clear()
        overBudgetInARow = 0
        return true
    }
}

/** What the guard did, for the log and a short message on screen. */
sealed interface GuardStep {
    data class QualityLowered(val quality: ShaderQuality, val byHeat: Boolean) : GuardStep
    data class QualityRaised(val quality: ShaderQuality) : GuardStep
    data object ApplyAlwaysSuspended : GuardStep
    data object ShadersDisabled : GuardStep
}

/**
 * Keeps the shaders affordable while a video plays. Heat lowers the quality one level at a time
 * and gives it back once the phone cools. Dropped frames go, in order: one quality level lower,
 * then "Apply always" off for this video, then shaders off for this video with a warning.
 */
class ShaderPerformanceGuard(
    private val thermal: ThermalHysteresis = ThermalHysteresis(),
    private val frames: FrameDropMonitor = FrameDropMonitor(),
) {
    fun reset() {
        thermal.reset()
        frames.reset()
    }

    /**
     * One reading. [plan] is what runs now and [settingsQuality] what the user picked; the
     * returned inputs replace [inputs] when something changed.
     */
    fun onSample(
        plan: ShaderPlan,
        inputs: ShaderRuntimeInputs,
        settingsQuality: ShaderQuality,
        headroom: Float?,
        thermalStatus: Int?,
        frame: FrameSample,
    ): Pair<ShaderRuntimeInputs, GuardStep>? {
        if (!plan.active) {
            frames.reset()
            return null
        }
        val heat = when {
            headroom != null && !headroom.isNaN() -> thermal.onHeadroom(headroom, frame.nowMs)
            thermalStatus != null -> thermal.onStatus(thermalStatus, frame.nowMs)
            else -> ThermalDecision.None
        }
        when (heat) {
            ThermalDecision.Lower -> plan.quality.lower()?.let { lower ->
                return inputs.copy(qualityCap = lower) to GuardStep.QualityLowered(lower, byHeat = true)
            }
            ThermalDecision.Raise -> {
                val raised = ShaderQuality.entries.getOrNull(plan.quality.ordinal + 1)
                if (raised != null && raised.ordinal <= settingsQuality.ordinal) {
                    val cap = raised.takeIf { it != settingsQuality }
                    return inputs.copy(qualityCap = cap) to GuardStep.QualityRaised(raised)
                }
            }
            ThermalDecision.None -> Unit
        }
        if (!frames.onSample(frame)) return null
        plan.quality.lower()?.let { lower ->
            return inputs.copy(qualityCap = lower) to GuardStep.QualityLowered(lower, byHeat = false)
        }
        if (plan.applyAlways) return inputs.copy(applyAlwaysSuspended = true) to GuardStep.ApplyAlwaysSuspended
        return inputs.copy(suspension = ShaderSuspension.Performance) to GuardStep.ShadersDisabled
    }
}

/** Runs the guard for the playing video and tells the user what it changed. */
object ShaderPerformanceGovernor {
    private val guard = ShaderPerformanceGuard()

    fun reset() = guard.reset()

    fun onSample(headroom: Float?, thermalStatus: Int?, frame: FrameSample, batterySaver: Boolean) {
        onBatterySaver(batterySaver)
        val plan = ShaderController.plan.value ?: return
        val settings = ShaderSettingsRepository.settings.value
        val (inputs, step) = guard.onSample(plan, ShaderController.inputs.value, settings.quality, headroom, thermalStatus, frame) ?: return
        val reason = when (step) {
            is GuardStep.QualityLowered -> "quality lowered to ${step.quality} (" +
                (if (step.byHeat) "thermal headroom=${headroom ?: "n/a"} status=${thermalStatus ?: "n/a"}" else "dropped frames, passes=${frame.passTotalMs}ms frame=${frame.frameTimeMs}ms") + ")"
            is GuardStep.QualityRaised -> "quality back to ${step.quality} (cooled down, headroom=${headroom ?: "n/a"})"
            GuardStep.ApplyAlwaysSuspended -> "Apply always off for this video (dropped frames)"
            GuardStep.ShadersDisabled -> "shaders off for this video (dropped frames at the lowest settings)"
        }
        InAppLogger.warn(TAG, "automatic: $reason")
        ShaderController.updateInputs { inputs }
        ShaderPlaybackSession.notify(ShaderWarning.Guard(step))
    }

    private fun onBatterySaver(on: Boolean) {
        val settings = ShaderSettingsRepository.settings.value
        val current = ShaderController.inputs.value.suspension
        val enabled = ShaderController.inputs.value.enabledOverride ?: settings.enabled
        val wanted = on && settings.disableOnBatterySaver && enabled
        if (wanted && current == null) {
            InAppLogger.info(TAG, "shaders paused: battery saver is on")
            ShaderController.updateInputs { it.copy(suspension = ShaderSuspension.BatterySaver) }
            ShaderPlaybackSession.notify(ShaderWarning.BatterySaver)
        } else if (!wanted && current == ShaderSuspension.BatterySaver) {
            InAppLogger.info(TAG, "shaders back: battery saver is off")
            ShaderController.updateInputs { it.copy(suspension = null) }
        }
    }
}
