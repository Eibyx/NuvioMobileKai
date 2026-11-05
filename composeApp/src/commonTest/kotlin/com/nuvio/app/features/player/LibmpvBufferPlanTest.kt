package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibmpvBufferPlanTest {
    private val cacheDir = "/data/user/0/app/cache/mpv-buffer"

    private fun settings(
        custom: Boolean = true,
        seconds: Int = 600,
        forwardMb: Int = 1024,
        backMb: Int = 300,
        onDisk: Boolean = false,
    ) = PlayerSettingsUiState(
        libmpvCustomBufferEnabled = custom,
        libmpvForwardBufferSeconds = seconds,
        libmpvForwardBufferMaxMb = forwardMb,
        libmpvBackBufferMb = backMb,
        libmpvBufferOnDisk = onDisk,
    )

    private fun LibmpvBufferPlan.option(name: String) = options.firstOrNull { it.first == name }?.second

    @Test
    fun offKeepsTheOldBufferBothWays() {
        val plan = libmpvBufferPlan(settings(custom = false), defaultMb = 64, cacheDir = cacheDir, freeDiskMb = 400_000)
        assertEquals("64MiB", plan.option("demuxer-max-bytes"))
        assertEquals("64MiB", plan.option("demuxer-max-back-bytes"))
        assertNull(plan.option("cache-secs"))
        assertNull(plan.option("cache"))
        assertFalse(plan.onDisk)
    }

    @Test
    fun theTypedNumbersGoToMpvInRam() {
        val plan = libmpvBufferPlan(settings(), defaultMb = 64, cacheDir = null, freeDiskMb = null)
        assertEquals("yes", plan.option("cache"))
        assertEquals("yes", plan.option("demuxer-seekable-cache"))
        assertEquals("600", plan.option("cache-secs"))
        assertEquals("600", plan.option("demuxer-readahead-secs"))
        assertEquals("1024MiB", plan.option("demuxer-max-bytes"))
        assertEquals("300MiB", plan.option("demuxer-max-back-bytes"))
        assertNull(plan.option("cache-on-disk"))
    }

    @Test
    fun ramKeepsItsLimitsSoTheAppIsNotKilled() {
        val plan = libmpvBufferPlan(settings(forwardMb = 8_000, backMb = 4_000), 64, null, null)
        assertEquals(LibmpvBufferLimits.MAX_RAM_FORWARD_MB, plan.forwardMb)
        assertEquals(LibmpvBufferLimits.MAX_RAM_BACK_MB, plan.backMb)
    }

    @Test
    fun onStorageARemuxSizedBufferFits() {
        // A 30 GB anime film: 10 minutes ahead (about 2.5 GB), up to 8 GB, 2 GB behind.
        val plan = libmpvBufferPlan(settings(forwardMb = 8_192, backMb = 2_048, onDisk = true), 64, cacheDir, freeDiskMb = 400_000)
        assertTrue(plan.onDisk)
        assertEquals("yes", plan.option("cache-on-disk"))
        assertEquals(cacheDir, plan.option("demuxer-cache-dir"))
        assertEquals("8192MiB", plan.option("demuxer-max-bytes"))
        assertEquals("2048MiB", plan.option("demuxer-max-back-bytes"))
    }

    @Test
    fun withLittleFreeSpaceBothShrinkAndTwoGigabytesStayFree() {
        val plan = libmpvBufferPlan(settings(forwardMb = 8_000, backMb = 2_000, onDisk = true), 64, cacheDir, freeDiskMb = 7_048)
        assertTrue(plan.onDisk)
        assertTrue(plan.forwardMb + plan.backMb <= 7_048 - LibmpvBufferLimits.DISK_SPARE_MB)
        assertEquals(4_000, plan.forwardMb)
        assertEquals(1_000, plan.backMb)
    }

    @Test
    fun withNoRoomOnStorageItFallsBackToRam() {
        val plan = libmpvBufferPlan(settings(forwardMb = 8_000, backMb = 2_000, onDisk = true), 64, cacheDir, freeDiskMb = 1_000)
        assertFalse(plan.onDisk)
        assertNull(plan.option("cache-on-disk"))
        assertEquals(LibmpvBufferLimits.MAX_RAM_FORWARD_MB, plan.forwardMb)
        assertEquals(LibmpvBufferLimits.MAX_RAM_BACK_MB, plan.backMb)
    }

    @Test
    fun secondsOutsideTheRangeAreClamped() {
        assertEquals("10", libmpvBufferPlan(settings(seconds = 1), 64, null, null).option("cache-secs"))
        assertEquals("7200", libmpvBufferPlan(settings(seconds = 99_999), 64, null, null).option("cache-secs"))
    }

    @Test
    fun theCacheLineSaysHowFarAheadAndWhetherASeekIsLoaded() {
        val ranges = listOf(LibmpvCachedRange(300.0, 430.5))
        val line = describeLibmpvCache("seek", 382.4, 430.5, 61L * 1024 * 1024, 1_468_006L, ranges, seekTargetSec = 372.4)
        assertEquals("cache seek pos=382s ahead=48s fw=61MB rate=1.4MB/s ranges=1 [300-430] seek=372s cached", line)
        assertTrue(describeLibmpvCache("seek", 382.0, 430.0, null, null, ranges, seekTargetSec = 200.0).endsWith("seek=200s not-cached"))
        assertEquals("cache periodic ranges=0", describeLibmpvCache("periodic", null, null, null, null, emptyList()))
    }
}
