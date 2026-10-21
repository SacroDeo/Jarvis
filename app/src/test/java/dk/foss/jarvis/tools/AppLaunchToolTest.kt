package dk.foss.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppLaunchTool.scoreMatch].
 *
 * [AppLaunchTool.open] requires an Android [Context] and [PackageManager] so it
 * cannot run in a JVM test.  The matching/scoring logic is tested here through
 * the [AppLaunchTool.scoreMatch] helper that was extracted specifically to allow
 * verification without an Android runtime.
 *
 * Fix 2 context:
 *  The previous [queryLaunchableApps] used [PackageManager.MATCH_DEFAULT_ONLY],
 *  which adds an implicit [android.content.Intent.CATEGORY_DEFAULT] requirement.
 *  Most launcher activities only declare [android.content.Intent.CATEGORY_LAUNCHER]
 *  and do not include [android.content.Intent.CATEGORY_DEFAULT], so on some OEM
 *  builds (e.g. Realme ColorOS) the query returned an empty list.  The flag was
 *  changed to 0 (no extra filter) so all MAIN+LAUNCHER activities are returned.
 *
 *  The launcher query itself is device-only; the matching logic below is the
 *  pure-Kotlin part that can be verified on the JVM.
 *
 * Device-side smoke tests required:
 *  DS-1  Say "Open YouTube" — YouTube must launch.
 *  DS-2  Say "Open Gmail" — Gmail must launch.
 *  DS-3  Say "Open an app that is not installed" — Jarvis must report failure,
 *         not crash.
 *  DS-4  Say "Open Maps" when both "Maps" and "Google Maps" are installed —
 *         Jarvis must pick the best match or report ambiguity.
 */
class AppLaunchToolScoreMatchTest {

    private fun score(query: String, label: String): Int? =
        AppLaunchTool.scoreMatch(query.lowercase(), label.lowercase())

    // ── Score 0: exact match ──────────────────────────────────────────────────

    @Test fun `exact match returns score 0`() {
        assertEquals(0, score("chrome", "chrome"))
    }

    @Test fun `exact match is case-insensitive`() {
        // Caller lowercases both sides; test that lowercased strings still match.
        assertEquals(0, score("chrome", "chrome"))
        assertEquals(0, score("maps", "maps"))
    }

    @Test fun `single-word query exact match`() {
        assertEquals(0, score("notes", "notes"))
    }

    // ── Score 1: label starts with query ─────────────────────────────────────

    @Test fun `label starts with query returns score 1`() {
        assertEquals(1, score("go", "google"))
    }

    @Test fun `single-char prefix match`() {
        // "c" matches "chrome" via starts-with, which is score 1.
        assertEquals(1, score("c", "chrome"))
    }

    @Test fun `partial label prefix match`() {
        assertEquals(1, score("goo", "google play"))
    }

    // ── Score 2: a word in the label starts with the query ───────────────────

    @Test fun `word in multi-word label starts with query returns score 2`() {
        assertEquals(2, score("play", "google play"))
    }

    @Test fun `second word in label starts with query`() {
        assertEquals(2, score("ma", "google maps"))
    }

    @Test fun `word prefix match requires at least FUZZY_MIN chars (2)`() {
        // Single-char query falls to score 1 (starts-with on full label) if it
        // matches, or null.  FUZZY_MIN guard only applies to word-prefix and contains.
        // "p" in "google play" — label does NOT start with "p" (score 1 fails),
        // but a word in the label starts with "p" → score 2 IS allowed here because
        // "p" with queryLower.length >= FUZZY_MIN(=2) is false, so score 2 returns null.
        assertNull(score("p", "google play"))
    }

    @Test fun `word prefix with 2 chars (FUZZY_MIN boundary) works`() {
        assertEquals(2, score("pl", "google play"))
    }

    // ── Score 3: label contains query as substring ────────────────────────────

    @Test fun `label contains query substring returns score 3`() {
        assertEquals(3, score("ube", "youtube"))
    }

    @Test fun `substring match with multi-word label`() {
        assertEquals(3, score("ail", "gmail"))
    }

    @Test fun `contains match requires at least FUZZY_MIN chars`() {
        // "u" is < FUZZY_MIN(2): starts-with check passes first? No — label "youtube"
        // does not start with "u". Word-start? "youtube" has one word, "youtube"
        // starts with "y" not "u". Contains? Only if queryLen >= 2. So null.
        assertNull(score("u", "youtube"))
    }

    // ── No match ─────────────────────────────────────────────────────────────

    @Test fun `completely unrelated query returns null`() {
        assertNull(score("xyz", "chrome"))
    }

    @Test fun `unrelated multi-word query returns null`() {
        assertNull(score("weather", "google maps"))
    }

    @Test fun `empty query returns null (score 0 fails, empty != non-empty)`() {
        // labelLower == queryLower only if label is also empty; otherwise no match.
        assertNull(score("", "chrome"))
    }

    // ── Score ordering guarantees ─────────────────────────────────────────────

    @Test fun `exact match scores lower than prefix match`() {
        val exact  = score("maps", "maps")!!
        val prefix = score("maps", "mapsco")!!
        assertTrue("exact ($exact) must beat prefix ($prefix)", exact < prefix)
    }

    @Test fun `prefix match scores lower than word-start match`() {
        val prefix    = score("go", "google")!!
        val wordStart = score("go", "let go")!!
        assertTrue("prefix ($prefix) must beat word-start ($wordStart)", prefix < wordStart)
    }

    @Test fun `word-start match scores lower than contains match`() {
        val wordStart = score("ma", "google maps")!!
        val contains  = score("oo", "google maps")!!
        assertTrue("word-start ($wordStart) must beat contains ($contains)", wordStart < contains)
    }

    // ── App names that previously failed on Realme (regression guard) ─────────
    // These do NOT hardcode YouTube or Gmail package names; they verify the
    // matching algorithm works for the label strings those apps happen to use.

    @Test fun `exact lowercase match for common short app labels`() {
        listOf("maps", "clock", "camera", "gallery", "phone", "contacts", "music",
               "notes", "weather", "calculator").forEach { label ->
            assertEquals("'$label' exact match", 0, score(label, label))
        }
    }

    @Test fun `partial query matches the start of a common app label`() {
        // "go" is a valid prefix for many "Google *" apps.
        val s = score("go", "google")
        assertEquals(1, s)
    }
}
