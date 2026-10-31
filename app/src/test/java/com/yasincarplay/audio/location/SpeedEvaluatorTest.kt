package com.yasincarplay.audio.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEvaluatorTest {

    private val now = 100_000L

    private fun evaluate(
        fine: Boolean = true,
        coarse: Boolean = true,
        hasGps: Boolean = true,
        enabled: Boolean = true,
        fix: GpsFix? = GpsFix(10f, now),
    ) = SpeedEvaluator.evaluate(fine, coarse, hasGps, enabled, fix, now)

    @Test
    fun withoutFinePermissionNoNumberIsShown() {
        assertEquals(SpeedReading.NoPermission(approximateOnly = false), evaluate(fine = false, coarse = false))
    }

    @Test
    fun approximateOnlyPermissionIsReportedSeparately() {
        assertEquals(SpeedReading.NoPermission(approximateOnly = true), evaluate(fine = false, coarse = true))
    }

    @Test
    fun permissionProblemWinsOverEverythingElse() {
        val r = evaluate(fine = false, coarse = false, hasGps = false, enabled = false, fix = null)
        assertEquals(SpeedReading.NoPermission(approximateOnly = false), r)
    }

    @Test
    fun deviceWithoutGps() {
        assertEquals(SpeedReading.NoGps, evaluate(hasGps = false, enabled = false))
    }

    @Test
    fun gpsSwitchedOff() {
        assertEquals(SpeedReading.ProviderOff, evaluate(enabled = false))
    }

    @Test
    fun noFixYetMeansSearching() {
        assertEquals(SpeedReading.Searching, evaluate(fix = null))
    }

    @Test
    fun freshFixConvertsMetersPerSecondToKmh() {
        val r = evaluate(fix = GpsFix(10f, now))
        assertTrue(r is SpeedReading.Valid)
        assertEquals(36f, (r as SpeedReading.Valid).kmh, 0.001f)
    }

    @Test
    fun belowStandstillThresholdShowsZero() {
        val r = evaluate(fix = GpsFix(0.2f, now))
        assertEquals(SpeedReading.Valid(0f), r)
    }

    @Test
    fun atStandstillThresholdShowsRealSpeed() {
        val r = evaluate(fix = GpsFix(SpeedEvaluator.STANDSTILL_MPS, now))
        assertEquals(1.8f, (r as SpeedReading.Valid).kmh, 0.001f)
    }

    @Test
    fun fixExactlyAtStaleLimitIsStillValid() {
        val r = evaluate(fix = GpsFix(5f, now - SpeedEvaluator.STALE_AFTER_MS))
        assertTrue(r is SpeedReading.Valid)
    }

    @Test
    fun fixOlderThanStaleLimitMeansSignalLost() {
        val r = evaluate(fix = GpsFix(5f, now - SpeedEvaluator.STALE_AFTER_MS - 1))
        assertEquals(SpeedReading.SignalLost, r)
    }

    @Test
    fun fixWithoutSpeedIsNotShownAsZero() {
        assertEquals(SpeedReading.NoSpeed, evaluate(fix = GpsFix(null, now)))
    }

    @Test
    fun negativeOrNaNSpeedIsRejected() {
        assertEquals(SpeedReading.NoSpeed, evaluate(fix = GpsFix(-1f, now)))
        assertEquals(SpeedReading.NoSpeed, evaluate(fix = GpsFix(Float.NaN, now)))
    }
}
