package dk.foss.jarvis.contacts

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

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

    /** No exact or approximate match was found. */
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

    private val ALLOWED_NUMBER_CHARS = setOf(
        '+', '*', '#', '(', ')', '-', '.', ' ',
    )

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
     * Structured lookup distinguishing exact from approximate matches.
     *
     * Uses a SQL LIKE pre-filter (`%query%`) so only plausibly-matching rows are
     * loaded from the ContentProvider; this avoids the previous hard cap of 40 rows
     * that silently failed on phones with many contacts.
     *
     * Exact — trimmed display name == query (case-insensitive).
     * Fuzzy — display name starts with query, query starts with display name, or any
     *         word in the display name starts with query (prefix-only; no substring).
     *
     * All numbers are loaded only for the candidates that are returned.
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

        // For the reverse-prefix fuzzy case (query starts with a display name that is
        // NOT a substring of itself), we need a second scan limited to short names.
        // E.g. the user says "Maaji" but the contact is "Maa".  The LIKE '%Maaji%'
        // filter above would miss "Maa" entirely.  Run a narrow supplemental query
        // only when the primary scan found no exact matches and the query is short.
        if (exactPairs.isEmpty() && q.length <= 20) {
            tryReversePrefixScan(context, q, fuzzyPairs)
        }

        fun load(pairs: List<Pair<Long, String>>): List<ContactCandidate> =
            pairs.map { (id, name) -> ContactCandidate(id, name, loadNumbers(context, id)) }

        return when {
            exactPairs.size == 1  -> LookupResult.ExactUnique(load(exactPairs)[0])
            exactPairs.size > 1   -> LookupResult.ExactAmbiguous(load(exactPairs.take(MAX_RESULTS)))
            fuzzyPairs.isNotEmpty() -> LookupResult.FuzzyMatches(load(fuzzyPairs))
            else -> LookupResult.NotFound
        }
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
