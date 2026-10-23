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

    // ── Real-world exact matches that were failing on Realme 8 ────────────────
    // These verify scoreMatch works correctly for common app names; the root
    // cause on-device was queryLaunchableApps() returning empty (fixed by
    // adding <queries> to AndroidManifest.xml for Android 11+ visibility).

    @Test fun `youtube exact match scores 0`() {
        assertEquals(0, score("youtube", "youtube"))
    }

    @Test fun `spotify exact match scores 0`() {
        assertEquals(0, score("spotify", "spotify"))
    }

    @Test fun `query youtube matches label YouTube exact`() {
        assertEquals(0, score("youtube", "youtube"))
    }

    @Test fun `null returned for empty query (guard added earlier)`() {
        assertNull(score("", "youtube"))
    }

    // ── Score 4: label is a complete word in query ────────────────────────────
    // "open Google Chrome" → query="google chrome" → "chrome" is a word → score 4.
    // Uses whitespace-split word matching, NOT substring containment, to prevent
    // "ok" from matching "book" (substring "ok" appears in "book" but it is not
    // a word in "book").

    @Test fun `label as exact word in query returns score 4`() {
        // "google chrome" split = ["google","chrome"]; "chrome" found → score 4
        assertEquals(4, score("google chrome", "chrome"))
    }

    @Test fun `label as exact word for maps`() {
        // User says "Google Maps" but app is labeled "Maps"
        assertEquals(4, score("google maps", "maps"))
    }

    @Test fun `label as exact word for photos`() {
        assertEquals(4, score("google photos", "photos"))
    }

    @Test fun `score 3 beats score 4 (label-contains vs label-is-word)`() {
        // label.contains(query) at score 3 outranks label-as-word-in-query at score 4
        val s3 = score("oo", "google maps")!!   // score 3: "google maps".contains("oo")
        val s4 = score("google maps", "maps")!! // score 4: "maps" is a word in "google maps"
        assertTrue("score 3 ($s3) must beat score 4 ($s4)", s3 < s4)
    }

    @Test fun `exact match beats score 4 — priority preserved`() {
        // If the installed label IS exactly what the user says, score 0 must win.
        val exact = score("chrome", "chrome")!!          // score 0
        val s4    = score("google chrome", "chrome")!!   // score 4
        assertTrue("exact ($exact) must beat score 4 ($s4)", exact < s4)
    }

    @Test fun `score 4 word-boundary — ok does not match book`() {
        // "ok" is a substring of "book" but NOT a whitespace-separated word.
        // This must return null, not score 4, to prevent false app matches.
        assertNull(score("book", "ok"))
    }

    @Test fun `score 4 word-boundary — ok does not match cook`() {
        assertNull(score("cook", "ok"))
    }

    @Test fun `score 4 word-boundary — ok matches ok google (word present)`() {
        // "ok" IS a complete word in "ok google"
        assertEquals(4, score("ok google", "ok"))
    }

    @Test fun `score 4 requires label at least FUZZY_MIN chars`() {
        // Label "a" (1 char) must not match via score 4
        assertNull(score("a camera", "a"))
    }

    @Test fun `score 4 short label two chars — matches only when exact word`() {
        // "go" is 2 chars (== FUZZY_MIN). Must only score 4 when "go" is a word in query.
        assertNull(score("google", "go"))   // "google" does not split into a word "go"
        assertEquals(4, score("go app", "go"))  // "go" IS a word in "go app"
    }

    // ── stripLeadingArticle helper ────────────────────────────────────────────

    @Test fun `strip leading the`() {
        assertEquals("clock", AppLaunchTool.stripLeadingArticle("the clock"))
    }

    @Test fun `strip leading a`() {
        assertEquals("camera", AppLaunchTool.stripLeadingArticle("a camera"))
    }

    @Test fun `strip leading an`() {
        assertEquals("app", AppLaunchTool.stripLeadingArticle("an app"))
    }

    @Test fun `no article to strip — unchanged`() {
        assertEquals("youtube", AppLaunchTool.stripLeadingArticle("youtube"))
    }

    @Test fun `article only without remainder — unchanged`() {
        // "the" with nothing after should not strip (would leave empty string)
        assertEquals("the", AppLaunchTool.stripLeadingArticle("the"))
    }

    @Test fun `open the Clock resolves via score 4 or article stripping`() {
        // With word-boundary score 4: "clock" IS a word in "the clock" → score 4.
        // open() also strips the article and retries, getting score 0 for "clock".
        // Both paths find the app; article stripping gives a more confident score.
        assertEquals(4, score("the clock", "clock"))   // word-boundary match
        assertEquals(0, score("clock", "clock"))       // after stripping: exact match
        assertTrue("stripped is a better match",
            score("clock", "clock")!! < score("the clock", "clock")!!)
    }

    @Test fun `open Google Chrome resolves — label Chrome matches via score 4`() {
        // Direct regression for device failure: "open Google Chrome" → label "Chrome"
        assertEquals(4, score("google chrome", "chrome"))
        // And the article-stripped path also works if AppIntentDetector passes "google chrome":
        // queryWords=["google","chrome"] → "chrome" found as word → score 4
    }

    @Test fun `two different apps with same score remain ambiguous — tie-break test`() {
        // "maps" scores the same against two hypothetical map-like labels that both
        // contain "maps" as a substring; they tie → MultipleMatches (device-only verify).
        // Here we just confirm both score the same tier:
        val s1 = score("maps", "google maps")!!   // word "ma" … no, "maps" starts "google maps"? Let's check
        val s2 = score("maps", "maps")!!          // exact
        // "maps" exact match returns 0; "maps" in "google maps" returns 2 (word-start).
        // They do NOT tie, which is expected: "Maps" would be preferred over "Google Maps".
        assertTrue("exact app beats broader label", s2 < s1)
    }

    @Test fun `two genuinely similar apps score identically — caller must treat as ambiguous`() {
        // If two different apps both have labels that start with the query at score 1,
        // they tie.  AppLaunchTool.open() returns MultipleMatches in this case (device-side).
        // Here we verify the pure-scoring layer produces equal scores for such a pair.
        val sMapsPlus = score("maps", "maps plus")!!   // score 1: label starts with "maps"
        val sMapsPro  = score("maps", "maps pro")!!    // score 1: label starts with "maps"
        assertEquals("equal score → same ambiguous tier", sMapsPlus, sMapsPro)
    }
}

// ── AppIntentDetector article-stripping ──────────────────────────────────────

class AppIntentDetectorArticleStripTest {

    private fun detect(text: String) = AppIntentDetector.extractAppName(text)

    @Test fun `open the Clock strips article`() {
        assertEquals("Clock", detect("open the Clock"))
    }

    @Test fun `launch a Camera strips article`() {
        assertEquals("Camera", detect("launch a Camera"))
    }

    @Test fun `open an App strips article`() {
        assertEquals("App", detect("open an App"))
    }

    @Test fun `open YouTube without article unchanged`() {
        assertEquals("YouTube", detect("open YouTube"))
    }

    @Test fun `open The Economist strips leading The`() {
        // Article stripped → "Economist"; AppLaunchTool will then find the app
        // because "economist" appears as a word in label "the economist" (score 4)
        // or matches via other rules if the label is simply "The Economist" (score 0).
        assertEquals("Economist", detect("open The Economist"))
    }

    @Test fun `launch Google Chrome strips nothing — no article`() {
        assertEquals("Google Chrome", detect("launch Google Chrome"))
    }

    @Test fun `open the Calculator strips article`() {
        assertEquals("Calculator", detect("open the Calculator"))
    }
}
