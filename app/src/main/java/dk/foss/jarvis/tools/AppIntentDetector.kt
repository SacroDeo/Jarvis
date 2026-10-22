package dk.foss.jarvis.tools

/**
 * Deterministic detector for explicit app-opening commands.
 *
 * Mirrors [dk.foss.jarvis.contacts.CallIntentDetector] for phone calls:
 * when the user says something unambiguously like "open YouTube" or
 * "launch Chrome", this extracts the app name so [AppLaunchTool] can
 * act immediately without an AI roundtrip.
 *
 * Design boundaries:
 *  - Only fires on short, clear open/launch/start phrases (≤ 4 words after prefix).
 *  - Phrases that look like sentences ("start the presentation tomorrow") are
 *    rejected and fall through to the AI.
 *  - No app-specific knowledge or hardcoded names — resolution is left entirely
 *    to [AppLaunchTool.open] and its fuzzy label-matching logic.
 */
object AppIntentDetector {

    private val OPEN_PREFIXES = listOf(
        "open ",
        "launch ",
        "start ",
        "run ",
        "show ",
        "please open ",
        "can you open ",
        "open up ",
    )

    /**
     * Words that, when they appear alone after the prefix, indicate this is
     * NOT an app command ("open it", "open up", "start again").
     */
    private val SOLO_SKIP = setOf(
        "it", "this", "that", "them", "up", "again", "now",
    )

    /**
     * Words that, if present anywhere in the candidate, suggest a sentence
     * rather than an app name and should send the request to the AI instead.
     */
    private val SENTENCE_INDICATORS = setOf(
        "and", "then", "after", "before", "when", "while", "because",
        "tomorrow", "today", "tonight", "morning", "evening", "later",
        "please", "for", "to", "me", "my",
    )

    /**
     * Returns the app name if the utterance is clearly "open <app>", else null.
     *
     * Null means: not an app-open command, fall through to the AI.
     */
    fun extractAppName(text: String): String? {
        val lower = text.trim().lowercase()

        for (prefix in OPEN_PREFIXES) {
            if (!lower.startsWith(prefix) || lower.length <= prefix.length) continue

            val candidate = text.trim()
                .substring(prefix.length)
                .trimEnd('.', '!', '?', ',', ';', ':')
                .trim()

            if (candidate.isBlank() || candidate.length < 2) continue

            val words = candidate.lowercase().split(Regex("\\s+"))

            // Single-word solo skip list
            if (words.size == 1 && words[0] in SOLO_SKIP) continue

            // Too many words — likely a sentence, not an app name
            if (words.size > 4) continue

            // Sentence indicator in any word — fall through to AI
            if (words.any { it in SENTENCE_INDICATORS }) continue

            return candidate
        }
        return null
    }
}
