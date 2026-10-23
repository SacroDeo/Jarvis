package dk.foss.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeepLinkTool].
 *
 * [DeepLinkTool.openUrl], [youtubeSearch], [browserSearch], [spotifyPlay] all require a
 * real [Context] and [ActivityNotFoundException] handling — device-only smoke tests cover
 * those.  The pure [DeepLinkTool.normalizeUrl] helper is fully testable on the JVM.
 *
 * Device-side smoke tests required (run on Realme 8 with the installed debug APK):
 *  DS-DL-1  Say "open YouTube and play Alan Walker Faded" — YouTube search results screen.
 *  DS-DL-2  Say "open Chrome and search for Linux commands" — browser with Google results.
 *  DS-DL-3  Say "open example.com" — browser opens https://example.com.
 *  DS-DL-4  Say "play Alan Walker Faded on Spotify" — Spotify search or browser fallback.
 *  DS-DL-5  Request any deep link with the target app uninstalled — useful fallback message.
 */
class DeepLinkToolNormalizeUrlTest {

    private fun norm(raw: String) = DeepLinkTool.normalizeUrl(raw)

    // ── valid HTTPS/HTTP URLs pass through unchanged ──────────────────────────

    @Test fun `https URL passes through unchanged`() {
        assertEquals("https://example.com", norm("https://example.com"))
    }

    @Test fun `https URL with path passes through unchanged`() {
        assertEquals("https://example.com/path/to/page", norm("https://example.com/path/to/page"))
    }

    @Test fun `http URL passes through unchanged`() {
        assertEquals("http://example.com", norm("http://example.com"))
    }

    @Test fun `https URL with query params passes through`() {
        assertEquals(
            "https://www.google.com/search?q=test",
            norm("https://www.google.com/search?q=test"),
        )
    }

    @Test fun `URL with leading and trailing whitespace is trimmed`() {
        assertEquals("https://example.com", norm("  https://example.com  "))
    }

    // ── bare hostnames are promoted to https ─────────────────────────────────

    @Test fun `bare hostname is promoted to https`() {
        assertEquals("https://example.com", norm("example.com"))
    }

    @Test fun `bare hostname with www is promoted to https`() {
        assertEquals("https://www.bbc.co.uk", norm("www.bbc.co.uk"))
    }

    @Test fun `bare hostname with path is promoted to https`() {
        assertEquals("https://example.com/news", norm("example.com/news"))
    }

    @Test fun `bare hostname with query string is promoted to https`() {
        assertEquals("https://example.com?ref=1", norm("example.com?ref=1"))
    }

    // ── invalid inputs return null ────────────────────────────────────────────

    @Test fun `blank string returns null`() {
        assertNull(norm(""))
        assertNull(norm("   "))
    }

    @Test fun `plain text without dot returns null`() {
        assertNull(norm("just some words"))
    }

    @Test fun `string with spaces returns null even if it has a dot`() {
        // "a b.com" contains a space — not a valid hostname
        assertNull(norm("a b.com"))
    }

    @Test fun `string with only a scheme separator but no host returns null`() {
        // "https://" alone has an empty host
        assertNull(norm("https://"))
    }

    @Test fun `host without a dot returns null`() {
        // "https://localhost" has no dot — rejected
        assertNull(norm("https://localhost"))
    }

    @Test fun `unknown scheme like ftp returns null`() {
        // ftp:// is not accepted; only https and http
        assertNull(norm("ftp://example.com"))
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test fun `youtube com is promoted to https`() {
        assertEquals("https://youtube.com", norm("youtube.com"))
    }

    @Test fun `google com is promoted to https`() {
        assertEquals("https://google.com", norm("google.com"))
    }

    @Test fun `URL with fragment is kept intact`() {
        assertEquals("https://example.com/page#section", norm("https://example.com/page#section"))
    }
}
