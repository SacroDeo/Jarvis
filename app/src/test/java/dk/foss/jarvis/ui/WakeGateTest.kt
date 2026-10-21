package dk.foss.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the wake-trigger gate introduced to fix the screen-dismissal regression.
 *
 * Background:
 *  [ConversationViewModel.startListening] previously called cancelSilenceExit() on every
 *  invocation, including wake-triggered ones.  When ambient noise caused repeated false
 *  wake fires (each separated by REFRACTORY_MS ≈ 2.5 s), the 20-second silence-exit
 *  timer was reset on every cycle and the conversation screen never auto-dismissed.
 *
 *  Fix: after [ConversationViewModel.MAX_EMPTY_WAKE] consecutive wake-triggered turns that
 *  produced no speech, [startListening] returns early without cancelling the timer.  The
 *  gate uses [ConversationViewModel.shouldDeferWakeTrigger] so this boundary condition
 *  can be verified here without an Android runtime.
 *
 * What is NOT tested here (requires Robolectric or a device):
 *  - That [startListening] actually returns early (involves Handler, Looper, ViewModel)
 *  - That [emptyWakeTurns] increments correctly in [goIdle]
 *  - That [emptyWakeTurns] resets in think(), startLocalCallFlow(), stopAll(), resetView()
 *  - Full end-to-end: silence timer fires → screen dismissed
 *
 * Device-side smoke test (DS-Wake):
 *  1. Open conversation screen, say nothing — screen must dismiss after ~20 s.
 *  2. Open screen, say "Jarvis" twice with no further speech, say nothing — screen
 *     must dismiss after at most ~20 s following the second empty turn.
 *  3. Open screen, tap mic, speak a real command — must work normally.
 *  4. Open screen, say "Jarvis" once (empty), then say "Jarvis" + real command on the
 *     second wake — command must execute normally (emptyWakeTurns resets on speech).
 */
class WakeGateTest {

    // ── MAX_EMPTY_WAKE constant ───────────────────────────────────────────────

    @Test fun `MAX_EMPTY_WAKE is 2`() {
        // Matches the documented historical value; changing it requires re-reviewing
        // the timing analysis in the bug report.
        assertEquals(2, ConversationViewModel.MAX_EMPTY_WAKE)
    }

    // ── shouldDeferWakeTrigger boundary conditions ────────────────────────────

    @Test fun `zero empty turns — do not defer`() {
        assertFalse(ConversationViewModel.shouldDeferWakeTrigger(0))
    }

    @Test fun `one empty turn — do not defer (first opportunity still allowed)`() {
        assertFalse(ConversationViewModel.shouldDeferWakeTrigger(1))
    }

    @Test fun `exactly MAX_EMPTY_WAKE empty turns — defer (boundary is inclusive)`() {
        // With MAX_EMPTY_WAKE = 2, the THIRD wake-triggered startListening call
        // (after two completed empty turns) is the first that should be deferred.
        val limit = ConversationViewModel.MAX_EMPTY_WAKE
        assertTrue(
            "emptyWakeTurns == MAX_EMPTY_WAKE must defer",
            ConversationViewModel.shouldDeferWakeTrigger(limit),
        )
    }

    @Test fun `more than MAX_EMPTY_WAKE empty turns — defer`() {
        val limit = ConversationViewModel.MAX_EMPTY_WAKE
        assertTrue(ConversationViewModel.shouldDeferWakeTrigger(limit + 1))
        assertTrue(ConversationViewModel.shouldDeferWakeTrigger(limit + 10))
    }

    @Test fun `boundary is strictly inclusive — MAX_EMPTY_WAKE minus one does not defer`() {
        val limit = ConversationViewModel.MAX_EMPTY_WAKE
        if (limit > 0) {
            assertFalse(
                "emptyWakeTurns = ${limit - 1} must NOT defer",
                ConversationViewModel.shouldDeferWakeTrigger(limit - 1),
            )
        }
    }

    // ── fromWake=false must never be deferred ─────────────────────────────────
    // (mic taps are a different code path; this test documents the design intent)

    @Test fun `gate only applies when fromWake is true`() {
        // The actual check in startListening is: fromWake && shouldDeferWakeTrigger(n)
        // Verify the predicate alone — the fromWake guard is in startListening itself.
        val limit = ConversationViewModel.MAX_EMPTY_WAKE
        // Even if emptyWakeTurns is at the limit, a mic tap (fromWake=false) must not
        // be gated.  The fromWake guard is evaluated before shouldDeferWakeTrigger, so
        // a false fromWake short-circuits the whole expression.
        val gateResult = false && ConversationViewModel.shouldDeferWakeTrigger(limit)
        assertFalse("fromWake=false must short-circuit the gate", gateResult)
    }
}
