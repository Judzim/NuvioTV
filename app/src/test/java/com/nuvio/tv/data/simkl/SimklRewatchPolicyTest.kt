package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewatch flag only belongs on a finished stop of a Pro/VIP account, and the prompt is only worth
 * showing when Simkl can actually open a session for the item.
 */
class SimklRewatchPolicyTest {
    @Test
    fun `only automatic puts the flag on the scrobble`() {
        assertTrue(shouldRecordSimklRewatchOnStop(AUTOMATIC, "pro", STOP, 80.0))
        assertTrue(shouldRecordSimklRewatchOnStop(AUTOMATIC, "vip", STOP, 100.0))
        assertTrue(shouldRecordSimklRewatchOnStop(AUTOMATIC, " PRO ", STOP, 96.5))
        assertFalse(shouldRecordSimklRewatchOnStop(MANUAL, "pro", STOP, 100.0))
        assertFalse(shouldRecordSimklRewatchOnStop(OFF, "pro", STOP, 100.0))
    }

    @Test
    fun `free and unknown plans never put the flag on the scrobble`() {
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "free", STOP, 100.0))
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, null, STOP, 100.0))
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "", STOP, 100.0))
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "basic", STOP, 100.0))
    }

    @Test
    fun `start and pause never carry the flag`() {
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "pro", START, 100.0))
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "pro", PAUSE, 100.0))
    }

    @Test
    fun `a stop below the watch threshold never carries the flag`() {
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "pro", STOP, 79.9))
        assertFalse(shouldRecordSimklRewatchOnStop(AUTOMATIC, "pro", STOP, 0.0))
    }

    @Test
    fun `manual asks only for an already watched title on a paid plan`() {
        val now = NOW

        assertTrue(shouldOfferSimklRewatchPrompt(MANUAL, "pro", STOP, 95.0, OLD_WATCH, now))
        assertTrue(shouldOfferSimklRewatchPrompt(MANUAL, "vip", STOP, 80.0, OLD_WATCH, now))
        assertFalse(shouldOfferSimklRewatchPrompt(AUTOMATIC, "pro", STOP, 95.0, OLD_WATCH, now))
        assertFalse(shouldOfferSimklRewatchPrompt(OFF, "pro", STOP, 95.0, OLD_WATCH, now))
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "free", STOP, 95.0, OLD_WATCH, now))
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, null, STOP, 95.0, OLD_WATCH, now))
    }

    @Test
    fun `manual asks only on a finished stop`() {
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "pro", START, 95.0, OLD_WATCH, NOW))
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "pro", PAUSE, 95.0, OLD_WATCH, NOW))
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "pro", STOP, 79.9, OLD_WATCH, NOW))
    }

    @Test
    fun `manual stays silent for a title that was never watched`() {
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "pro", STOP, 95.0, null, NOW))
    }

    @Test
    fun `manual waits for the two day gap Simkl needs`() {
        val exactlyTwoDays = NOW - SIMKL_REWATCH_MIN_GAP_MS

        assertTrue(shouldOfferSimklRewatchPrompt(MANUAL, "pro", STOP, 95.0, exactlyTwoDays, NOW))
        assertFalse(
            shouldOfferSimklRewatchPrompt(
                MANUAL,
                "pro",
                STOP,
                95.0,
                NOW - SIMKL_REWATCH_MIN_GAP_MS + 1L,
                NOW
            )
        )
        assertFalse(shouldOfferSimklRewatchPrompt(MANUAL, "pro", STOP, 95.0, NOW, NOW))
    }

    @Test
    fun `plan eligibility accepts only pro and vip`() {
        assertTrue(isSimklRewatchPlanEligible("pro"))
        assertTrue(isSimklRewatchPlanEligible("vip"))
        assertTrue(isSimklRewatchPlanEligible(" VIP "))
        assertFalse(isSimklRewatchPlanEligible("free"))
        assertFalse(isSimklRewatchPlanEligible(""))
        assertFalse(isSimklRewatchPlanEligible(null))
    }

    @Test
    fun `mode selectability keeps off available and gates the paid modes`() {
        assertTrue(isSimklRewatchModeSelectable(OFF, null))
        assertTrue(isSimklRewatchModeSelectable(MANUAL, "pro"))
        assertTrue(isSimklRewatchModeSelectable(AUTOMATIC, "vip"))
        assertFalse(isSimklRewatchModeSelectable(MANUAL, "free"))
        assertFalse(isSimklRewatchModeSelectable(AUTOMATIC, null))
    }

    @Test
    fun `mode storage round trip falls back to off`() {
        assertEquals(OFF, SimklRewatchMode.fromStorage(null))
        assertEquals(OFF, SimklRewatchMode.fromStorage("unknown"))
        assertEquals(MANUAL, SimklRewatchMode.fromStorage("manual"))
        assertEquals(MANUAL, SimklRewatchMode.fromStorage("  MANUAL  "))
        assertEquals(AUTOMATIC, SimklRewatchMode.fromStorage("AUTOMATIC"))
        assertFalse(OFF.isEnabled)
        assertTrue(SimklRewatchMode.MANUAL.isEnabled)
    }

    @Test
    fun `every documented rewatch status parses and unknown values stay unknown`() {
        assertEquals(SimklRewatchStatus.ACTIVE, SimklRewatchStatus.fromWire("active"))
        assertEquals(SimklRewatchStatus.COMPLETED, SimklRewatchStatus.fromWire("completed"))
        assertEquals(SimklRewatchStatus.CLOSED, SimklRewatchStatus.fromWire("closed"))
        assertEquals(SimklRewatchStatus.FIRST_WATCH, SimklRewatchStatus.fromWire("first_watch"))
        assertEquals(SimklRewatchStatus.TOO_SOON, SimklRewatchStatus.fromWire("too_soon"))
        assertEquals(SimklRewatchStatus.NOT_ELIGIBLE, SimklRewatchStatus.fromWire("not_eligible"))
        assertEquals(SimklRewatchStatus.PRO_REQUIRED, SimklRewatchStatus.fromWire("pro_required"))
        assertEquals(SimklRewatchStatus.UNKNOWN, SimklRewatchStatus.fromWire("something_new"))
        assertEquals(SimklRewatchStatus.COMPLETED, SimklRewatchStatus.fromWire(" COMPLETED "))
        assertNull(SimklRewatchStatus.fromWire(null))
        assertNull(SimklRewatchStatus.fromWire("  "))
    }

    @Test
    fun `only recorded statuses count as a stored rewatch`() {
        assertTrue(SimklRewatchStatus.ACTIVE.isRecorded)
        assertTrue(SimklRewatchStatus.COMPLETED.isRecorded)
        assertTrue(SimklRewatchStatus.CLOSED.isRecorded)
        assertFalse(SimklRewatchStatus.FIRST_WATCH.isRecorded)
        assertFalse(SimklRewatchStatus.TOO_SOON.isRecorded)
        assertFalse(SimklRewatchStatus.NOT_ELIGIBLE.isRecorded)
        assertFalse(SimklRewatchStatus.PRO_REQUIRED.isRecorded)
        assertFalse(SimklRewatchStatus.UNKNOWN.isRecorded)
    }

    @Test
    fun `the wire contract stays the documented one`() {
        assertEquals(mapOf("allow_rewatch" to "yes"), SIMKL_ALLOW_REWATCH_QUERY)
        assertEquals(80.0, SIMKL_REWATCH_MIN_PROGRESS_PERCENT, 0.0)
        assertEquals(172_800_000L, SIMKL_REWATCH_MIN_GAP_MS)
    }

    private companion object {
        private const val NOW = 1_700_000_000_000L
        private const val OLD_WATCH = NOW - SIMKL_REWATCH_MIN_GAP_MS - 1L
        private val STOP = TrackingScrobbleAction.STOP
        private val START = TrackingScrobbleAction.START
        private val PAUSE = TrackingScrobbleAction.PAUSE
        private val OFF = SimklRewatchMode.OFF
        private val MANUAL = SimklRewatchMode.MANUAL
        private val AUTOMATIC = SimklRewatchMode.AUTOMATIC
    }
}
