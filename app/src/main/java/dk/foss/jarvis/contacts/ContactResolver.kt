package dk.foss.jarvis.contacts

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import java.text.Normalizer

/**
 * One phone number belonging to a [ContactCandidate], with a human-readable label
 * (mobile / work / home / …) suitable for spoken confirmation.
 */
data class PhoneNumber(
    val number: String,
    val label: String,
)

/**
 * A resolved local contact. Never leaves the device; never sent to the Worker/model.
 */
data class ContactCandidate(
    val contactId: Long,
    val displayName: String,
    val numbers: List<PhoneNumber>,
)

/**
 * Result of a structured local contact lookup, separating exact from approximate matches.
 * Only [ExactUnique] may be dialled without first asking the user to disambiguate.
 * [PhoneticMatches] is always presented to the user for confirmation — never auto-dialled.
 */
sealed class LookupResult {
    /** Exactly one contact whose trimmed display name matches the query (case-insensitive). */
    data class ExactUnique(val contact: ContactCandidate) : LookupResult()

    /** Two or more contacts share the same exact name — the user must choose one. */
    data class ExactAmbiguous(val contacts: List<ContactCandidate>) : LookupResult()

    /**
     * No exact match, but one or more contacts plausibly match:
     * the display name starts with the query, the query starts with the name, or
     * any word in the name starts with the query.  These are deterministic prefix
     * checks — broad substring matching is intentionally excluded to avoid
     * unrelated suggestions.
     */
    data class FuzzyMatches(val contacts: List<ContactCandidate>) : LookupResult()

    /**
     * No exact or prefix match, but one or more contacts matched via bounded
     * Levenshtein edit-distance after Unicode diacritic normalization.
     *
     * These are LOW-CONFIDENCE matches.  The caller MUST ask the user to confirm
     * any candidate before placing a call — even when [contacts] has only one entry.
     * Auto-dialling is never permitted from this tier.
     *
     * Typical causes: STT returned "pappa" for a contact saved as "Papa", or the user
     * added/dropped a letter ("Rahul" vs "Rahu", "paapa" vs "Papa").
     */
    data class PhoneticMatches(val contacts: List<ContactCandidate>) : LookupResult()

    /** No exact, prefix, or phonetic match was found. */
    object NotFound : LookupResult()
}

/**
 * Synchronous, bounded local contact lookup. Must be called on a background thread
 * (the caller dispatches); every failure path returns an empty list rather than throw.
 */
object ContactResolver {

    private const val TAG = "ContactResolver"

    private const val MAX_RESULTS = 5
    // Secondary in-memory cap applied after the SQL LIKE filter.  Far lower than the
    // total contacts count; the LIKE pre-filter already narrows results significantly.
    private const val MAX_SCANNED_ROWS = 200
    private const val MAX_NUMBERS_PER_CONTACT = 4

    /**
     * Minimum character count required for each side of a fuzzy-match check.
     * Prevents single-character queries from producing suggestions for every contact
     * whose name starts with that letter, and prevents single-character display names
     * (e.g., a contact saved as "M") from matching every query that starts with "M".
     * Exact matching is unaffected — it has no minimum length.
     */
    private const val FUZZY_MIN_LEN = 2

    /**
     * Minimum query length for the phonetic / edit-distance scan.
     * Very short queries (1–2 chars) produce too many false matches even with a tight
     * edit-distance threshold; phonetic matching only runs for queries of at least 3 chars.
     */
    private const val PHONETIC_MIN_QUERY_LEN = 3

    /**
     * Maximum rows scanned by the supplemental phonetic prefix pass.
     * The prefix LIKE filter already narrows candidates; this cap is a safety valve
     * against pathological contact databases.
     */
    private const val PHONETIC_SCAN_LIMIT = 50

    /**
     * Maximum Levenshtein distance allowed per unit of query length.
     * A threshold of 1 per 3 characters (minimum 1, maximum 3) is conservative:
     * - 3-char query ("Ali"):  threshold 1  — "Aly", "Aali" match; "Bob" does not.
     * - 5-char query ("pappa"): threshold 1 — "Papa", "Pappa" match; "Bobby" does not.
     * - 8-char query ("Abhishek"): threshold 2 — "Abhishek" variants; far-off names do not.
     * Capped at 3 to avoid broad matches on very long names.
     */
    private const val PHONETIC_MAX_THRESHOLD = 3

    private val ALLOWED_NUMBER_CHARS = setOf(
        '+', '*', '#', '(', ')', '-', '.', ' ',
    )

    // ── Phonetic / edit-distance helpers ─────────────────────────────────────

    /**
     * Normalize a name for phonetic comparison:
     *  1. Decompose Unicode to NFD form so that accented characters become base + combining mark.
     *  2. Strip all combining marks (diacritics), mapping é→e, ä→a, ñ→n, etc.
     *  3. Lowercase and trim whitespace.
     *
     * This allows "pappa" (spoken/STT) to match "Papa" (stored), and "maria" to match "María".
     * Note: if the *stored* name contains diacritics and the SQL LIKE filter uses the raw query,
     * the LIKE scan may not surface the row at all (SQLite LIKE is only case-insensitive for
     * ASCII). The phonetic scan therefore uses the first two chars of the **raw** query to keep
     * the prefix filter wide enough while bounding the scan to a small candidate set.
     *
     * Pure JDK (java.text.Normalizer is available on Android); no external dependencies.
     * Exposed as `internal` for unit tests.
     */
    internal fun normalizeForMatching(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return nfd
            .replace(Regex("\\p{M}"), "")  // strip combining marks / diacritics
            .lowercase()
            .trim()
    }

    /**
     * Standard Levenshtein distance (edit distance) between two strings.
     *
     * O(m × n) time and space.  Both strings are capped at 40 characters before
     * comparison to bound worst-case cost (names longer than 40 chars are pathological).
     *
     * Exposed as `internal` for unit tests.
     */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val sa = a.take(40)
        val sb = b.take(40)
        val m = sa.length
        val n = sb.length
        // Use two rows to reduce memory allocation.
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (sa[i - 1] == sb[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    /**
     * Returns true when [displayName] is a plausible phonetic match for [query].
     *
     * Both sides are first normalized via [normalizeForMatching].  Matching is then
     * performed as follows (from highest to lowest sensitivity):
     *  1. Full-name edit distance ≤ threshold.
     *  2. Any whitespace-separated token in the name has edit distance ≤ threshold
     *     against the full normalized query (handles first-name-only queries vs
     *     "First Last" stored names).
     *
     * The threshold is `max(1, queryLen / 3)` capped at [PHONETIC_MAX_THRESHOLD].
     * This keeps matching conservative: a 5-char query allows 1 edit (pappa→papa),
     * an 8-char query allows 2 edits, a 12-char query allows 3 edits (the max).
     *
     * Guards:
     * - Queries shorter than [PHONETIC_MIN_QUERY_LEN] always return false.
     * - Display names shorter than [PHONETIC_MIN_QUERY_LEN] are skipped for token
     *   matching to avoid single-character names generating false matches.
     * - If the normalized strings are identical, returns false: that case is an exact
     *   match handled upstream (avoids duplicating ExactUnique results as PhoneticMatches).
     *
     * Exposed as `internal` for unit tests.  [nQuery] may be pre-computed by the caller
     * to avoid redundant normalization when matching many candidates.
     */
    internal fun isPhoneticMatch(
        displayName: String,
        query: String,
        nQuery: String? = null,
    ): Boolean {
        if (displayName.isEmpty() || query.isEmpty()) return false
        if (query.length < PHONETIC_MIN_QUERY_LEN) return false

        val nDn = normalizeForMatching(displayName)
        val nQ  = nQuery ?: normalizeForMatching(query)

        if (nDn.isEmpty() || nQ.isEmpty()) return false
        // Exact match after normalization — already handled by the exact tier.
        if (nDn == nQ) return false

        val threshold = (nQ.length / 3).coerceIn(1, PHONETIC_MAX_THRESHOLD)

        // Full name comparison.
        if (levenshtein(nDn, nQ) <= threshold) return true

        // Token comparison: compare each word in the stored name against the full query.
        // This lets "pappa" match the first name in "Papa Sharma", for example.
        val tokens = nDn.split(Regex("\\s+"))
        return tokens.any { token ->
            token.length >= PHONETIC_MIN_QUERY_LEN &&
                levenshtein(token, nQ) <= threshold
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Find up to [MAX_RESULTS] contacts whose display name matches [query]
     * (exact, then prefix, then contains — case-insensitive), each with up to
     * [MAX_NUMBERS_PER_CONTACT] phone numbers.
     *
     * Returns an empty list on no match, missing permission, or any provider error.
     */
    fun findContacts(context: Context, query: String): List<ContactCandidate> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val nameMatches = mutableListOf<ContactCandidate>()
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                // Filter at the database level to avoid iterating all contacts.
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_SCANNED_ROWS) {
                    scanned++
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    if (matchesLoosely(name, q)) {
                        nameMatches.add(ContactCandidate(id, name, emptyList()))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "findContacts: permission denied", e)
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "findContacts: provider error", e)
            return emptyList()
        }

        // Rank: exact match first, then prefix, then contains — then cap.
        val ranked = nameMatches
            .sortedWith(compareBy(
                { !it.displayName.equals(q, ignoreCase = true) },
                { !it.displayName.startsWith(q, ignoreCase = true) },
            ))
            .take(MAX_RESULTS)

        return ranked.map { candidate ->
            candidate.copy(numbers = loadNumbers(context, candidate.contactId))
        }
    }

    /**
     * Structured lookup distinguishing exact, prefix-fuzzy, and phonetic matches.
     *
     * Resolution tiers (highest confidence first):
     *  1. Exact — trimmed display name == query (case-insensitive).
     *  2. Fuzzy — display name / query prefix or word-start matches.
     *     Single fuzzy match auto-proceeds; multiple fuzzy matches require user choice.
     *  3. Phonetic — no exact/fuzzy match found, but edit-distance ≤ threshold after
     *     Unicode normalization.  ALWAYS requires user confirmation, even for one result.
     *
     * Contact names and phone numbers never leave the device.
     * Returns [LookupResult.NotFound] on empty query, missing permission, or provider error.
     */
    fun lookup(context: Context, query: String): LookupResult {
        val q = query.trim()
        if (q.isEmpty()) return LookupResult.NotFound

        // (contactId, trimmedDisplayName)
        val exactPairs = mutableListOf<Pair<Long, String>>()
        val fuzzyPairs = mutableListOf<Pair<Long, String>>()

        try {
            // The LIKE '%q%' filter at the database level means we only iterate rows
            // that contain the query string, dramatically reducing in-memory work
            // compared to scanning all contacts.  The secondary MAX_SCANNED_ROWS cap
            // is kept as a safety valve against pathological data.
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idIdx   = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_SCANNED_ROWS) {
                    scanned++
                    val id = cursor.getLong(idIdx)
                    val dn = (cursor.getString(nameIdx) ?: continue).trim()
                    when {
                        dn.equals(q, ignoreCase = true) ->
                            exactPairs.add(id to dn)
                        fuzzyPairs.size < MAX_RESULTS && isFuzzyMatch(dn, q) ->
                            fuzzyPairs.add(id to dn)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "lookup: permission denied", e)
            return LookupResult.NotFound
        } catch (e: Exception) {
            Log.w(TAG, "lookup: provider error", e)
            return LookupResult.NotFound
        }

        // Supplemental reverse-prefix scan for the "query starts with display name" arm.
        // Example: user says "Maaji" but the contact is "Maa"; LIKE '%Maaji%' misses "Maa".
        if (exactPairs.isEmpty() && q.length <= 20) {
            tryReversePrefixScan(context, q, fuzzyPairs)
        }

        // If the standard tiers found nothing, try the phonetic (edit-distance) tier.
        // Runs only when there are no exact or fuzzy candidates — it is the last resort.
        if (exactPairs.isEmpty() && fuzzyPairs.isEmpty() && q.length >= PHONETIC_MIN_QUERY_LEN) {
            val existingIds = HashSet<Long>() // both exactPairs and fuzzyPairs are empty here
            val phoneticPairs = mutableListOf<Pair<Long, String>>()
            tryPhoneticScan(context, q, phoneticPairs, existingIds)
            if (phoneticPairs.isNotEmpty()) {
                val phonetic = phoneticPairs.map { (id, name) ->
                    ContactCandidate(id, name, loadNumbers(context, id))
                }
                Log.i(TAG, "lookup(len=${q.length}): phonetic=${phoneticPairs.size} result=PhoneticMatches")
                return LookupResult.PhoneticMatches(phonetic)
            }
        }

        fun load(pairs: List<Pair<Long, String>>): List<ContactCandidate> =
            pairs.map { (id, name) -> ContactCandidate(id, name, loadNumbers(context, id)) }

        val result = when {
            exactPairs.size == 1  -> LookupResult.ExactUnique(load(exactPairs)[0])
            exactPairs.size > 1   -> LookupResult.ExactAmbiguous(load(exactPairs.take(MAX_RESULTS)))
            fuzzyPairs.isNotEmpty() -> LookupResult.FuzzyMatches(load(fuzzyPairs))
            else -> LookupResult.NotFound
        }
        // Diagnostic log — reports result tier and match counts without exposing names.
        // Use adb logcat tag JarvisContacts to diagnose "contact not found" failures.
        Log.i(TAG, "lookup(len=${q.length}): exactMatches=${exactPairs.size} " +
            "fuzzyMatches=${fuzzyPairs.size} result=${result::class.simpleName}")
        return result
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Phone numbers for one contact, capped, labels mapped for speech. */
    private fun loadNumbers(context: Context, contactId: Long): List<PhoneNumber> {
        val numbers = mutableListOf<PhoneNumber>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext() && numbers.size < MAX_NUMBERS_PER_CONTACT) {
                    val raw = cursor.getString(numberIdx) ?: continue
                    val number = raw.trim()
                    if (!isValidNumber(number)) continue
                    val type = cursor.getInt(typeIdx)
                    numbers.add(PhoneNumber(number, labelFor(type)))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "loadNumbers: permission denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "loadNumbers: provider error", e)
        }
        return numbers
    }

    private fun matchesLoosely(displayName: String, query: String): Boolean {
        val dn = displayName.trim()
        if (dn.isEmpty()) return false
        return dn.contains(query, ignoreCase = true) ||
            query.contains(dn, ignoreCase = true) ||
            dn.split(Regex("\\s+")).any { it.startsWith(query, ignoreCase = true) }
    }

    /**
     * Conservative number validation before ACTION_DIAL:
     * allowed characters only, 3–15 digits, total length ≤ 30.
     */
    fun isValidNumber(number: String): Boolean {
        if (number.isBlank() || number.length > 30) return false
        val digitCount = number.count { it.isDigit() }
        if (digitCount < 3 || digitCount > 15) return false
        return number.all { it.isDigit() || it in ALLOWED_NUMBER_CHARS }
    }

    private fun labelFor(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "home fax"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "work fax"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
        else -> "other"
    }

    /**
     * Supplemental pass for the "query starts with display name" fuzzy arm.
     *
     * Example: user says "Maaji" but the contact is saved as "Maa". The primary
     * LIKE '%Maaji%' scan misses "Maa". This pass queries contacts whose names
     * START with the first two characters of the query to cheaply catch short-name
     * contacts that may be a prefix of the query.
     *
     * Results are de-duplicated against what is already in [fuzzyPairs] by contactId.
     */
    private fun tryReversePrefixScan(
        context: Context,
        query: String,
        fuzzyPairs: MutableList<Pair<Long, String>>,
    ) {
        if (query.length < FUZZY_MIN_LEN) return
        val existingIds = fuzzyPairs.map { it.first }.toHashSet()
        val prefix2 = query.take(2)
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("$prefix2%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idIdx   = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var scanned = 0
                while (cursor.moveToNext() && scanned < 50 && fuzzyPairs.size < MAX_RESULTS) {
                    scanned++
                    val id = cursor.getLong(idIdx)
                    if (id in existingIds) continue
                    val dn = (cursor.getString(nameIdx) ?: continue).trim()
                    // Only the reverse-prefix arm: query starts with display name.
                    if (dn.length >= FUZZY_MIN_LEN &&
                        query.startsWith(dn, ignoreCase = true) &&
                        !dn.equals(query, ignoreCase = true)
                    ) {
                        fuzzyPairs.add(id to dn)
                        existingIds.add(id)
                    }
                }
            }
        } catch (_: Exception) {
            // Supplemental scan — any failure is safe to ignore.
        }
    }

    /**
     * Phonetic / edit-distance supplemental scan.
     *
     * Uses the first two characters of the raw query as a LIKE prefix to narrow
     * the candidate set before applying [isPhoneticMatch].  Only runs when the
     * standard exact and fuzzy tiers found nothing.
     *
     * The prefix approach means contacts whose names start with a different letter
     * than the query will not be found (e.g., "John" won't match "pappa").
     * This is intentional: it keeps the scan bounded and prevents false matches.
     *
     * Limitation: if the stored name starts with a diacritic character (e.g., "Päppä")
     * and the user speaks the unaccented form ("pappa"), the LIKE prefix 'pa%' will NOT
     * find "Päppä" because SQLite LIKE is only case-insensitive for ASCII.  The normalized
     * edit distance would match, but the row is never surfaced.  This edge case is accepted;
     * it affects only contacts whose stored names begin with a non-ASCII character AND whose
     * spoken query begins with a different ASCII character.
     */
    private fun tryPhoneticScan(
        context: Context,
        query: String,
        phoneticPairs: MutableList<Pair<Long, String>>,
        existingIds: MutableSet<Long>,
    ) {
        if (query.length < PHONETIC_MIN_QUERY_LEN) return
        // Use the first 2 lowercase chars of the raw query as the LIKE prefix.
        // Lower-casing is redundant (SQLite LIKE is case-insensitive for ASCII) but
        // makes the intent explicit.
        val prefix2 = query.take(2).lowercase()
        val nQuery = normalizeForMatching(query)
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("$prefix2%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idIdx   = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var scanned = 0
                while (cursor.moveToNext() &&
                    scanned < PHONETIC_SCAN_LIMIT &&
                    phoneticPairs.size < MAX_RESULTS
                ) {
                    scanned++
                    val id = cursor.getLong(idIdx)
                    if (id in existingIds) continue
                    val dn = (cursor.getString(nameIdx) ?: continue).trim()
                    if (isPhoneticMatch(dn, query, nQuery)) {
                        phoneticPairs.add(id to dn)
                        existingIds.add(id)
                    }
                }
            }
        } catch (_: Exception) {
            // Supplemental scan — any failure is safe to ignore.
        }
    }

    /**
     * Conservative prefix-based fuzzy match.
     * Avoids broad substring matching to prevent unrelated contacts being suggested.
     *
     * Both the query and (for the reverse-prefix arm) the display name must be at least
     * [FUZZY_MIN_LEN] characters.  This prevents:
     *   - Single-character queries ("A") matching every contact whose name begins with "A".
     *   - Single-character display names ("M") matching every query that starts with "M"
     *     via the reverse-prefix arm ("`query`.startsWith(`displayName`)").
     * Exact matches are handled upstream and are unaffected by this guard.
     */
    private fun isFuzzyMatch(displayName: String, query: String): Boolean {
        if (displayName.isEmpty() || query.isEmpty()) return false
        // Single-character queries produce too many unrelated suggestions — skip fuzzy entirely.
        if (query.length < FUZZY_MIN_LEN) return false
        // Display name starts with query: "Ma" → "Maa", "Maria"
        if (displayName.startsWith(query, ignoreCase = true)) return true
        // Query starts with display name (user over-spelled): guard the name length so
        // a single-character name such as "M" cannot match every query starting with "M".
        if (displayName.length >= FUZZY_MIN_LEN && query.startsWith(displayName, ignoreCase = true)) return true
        // Any word in the display name starts with query: "Ma" → "Maria Santos"
        return displayName.split(Regex("\\s+")).any { it.startsWith(query, ignoreCase = true) }
    }

    // Exposed for testing (it's pure logic, no Android deps).
    internal fun isFuzzyMatchPublic(displayName: String, query: String): Boolean =
        isFuzzyMatch(displayName, query)
}
