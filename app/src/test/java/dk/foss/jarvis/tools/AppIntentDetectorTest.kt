package dk.foss.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [AppIntentDetector].
 *
 * Verifies the deterministic fast-path for app-opening commands.
 * All cases that fail here fall through to the AI in ConversationViewModel.
 */
class AppIntentDetectorTest {

    private fun detect(text: String) = AppIntentDetector.extractAppName(text)

    // ── Cases that SHOULD match ───────────────────────────────────────────────

    @Test fun `open YouTube returns YouTube`() {
        assertEquals("YouTube", detect("open YouTube"))
    }

    @Test fun `launch Chrome returns Chrome`() {
        assertEquals("Chrome", detect("launch Chrome"))
    }

    @Test fun `start WhatsApp returns WhatsApp`() {
        assertEquals("WhatsApp", detect("start WhatsApp"))
    }

    @Test fun `open Google Maps returns Google Maps`() {
        assertEquals("Google Maps", detect("open Google Maps"))
    }

    @Test fun `open with trailing punctuation stripped`() {
        assertEquals("YouTube", detect("open YouTube."))
    }

    @Test fun `case preserved in returned name`() {
        assertEquals("Gmail", detect("open Gmail"))
    }

    @Test fun `please open prefix works`() {
        assertEquals("Spotify", detect("please open Spotify"))
    }

    @Test fun `can you open prefix works`() {
        assertEquals("Maps", detect("can you open Maps"))
    }

    @Test fun `run prefix works`() {
        assertEquals("Calculator", detect("run Calculator"))
    }

    @Test fun `two-word app name`() {
        assertEquals("Google Photos", detect("open Google Photos"))
    }

    // ── Cases that SHOULD NOT match ───────────────────────────────────────────

    @Test fun `blank input returns null`() {
        assertNull(detect(""))
        assertNull(detect("   "))
    }

    @Test fun `plain text without prefix returns null`() {
        assertNull(detect("What time is it"))
        assertNull(detect("Call Maa"))
    }

    @Test fun `open up alone returns null`() {
        assertNull(detect("open up"))
    }

    @Test fun `open it returns null`() {
        assertNull(detect("open it"))
    }

    @Test fun `sentence after prefix rejected — contains indicator word`() {
        // "start the presentation tomorrow" → "tomorrow" is a sentence indicator
        assertNull(detect("start the presentation tomorrow"))
    }

    @Test fun `open with too many words rejected`() {
        // 5 words after prefix — too many for an app name
        assertNull(detect("open the big blue video app please"))
    }

    @Test fun `open after my meeting rejected — 'my' is sentence indicator`() {
        assertNull(detect("open my calendar for today"))
    }

    // ── Boundary checks ───────────────────────────────────────────────────────

    @Test fun `single character app name rejected (too short)`() {
        assertNull(detect("open X"))  // X alone < 2 chars... wait X = 1 char
    }

    @Test fun `two character app name accepted`() {
        // Edge: app name = 2 chars is the minimum
        assertEquals("TV", detect("open TV"))
    }

    @Test fun `four-word app name accepted (boundary)`() {
        assertEquals("Google Play Music App", detect("open Google Play Music App"))
    }

    @Test fun `five-word app name rejected`() {
        assertNull(detect("open Google Play Music Player App"))
    }
}
