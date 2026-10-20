package dk.foss.jarvis.contacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Structured-output tool layer for the call-contact feature.
 *
 * The model always answers with a strict JSON envelope:
 *   { "reply": string, "tool": "call_contact"|"none", "contact_name": string }
 *
 * Only [call_contact] is ever executed, and only after exact voice confirmation.
 * All parsing/validation happens on-device; malformed or over-broad content fails
 * closed (Outcome.Invalid — nothing is spoken, nothing is executed).
 */
@Serializable
data class AssistantEnvelope(
    val reply: String = "",
    val tool: String = "none",
    val contact_name: String = "",
)

object ToolRegistry {

    const val TOOL_CALL_CONTACT = "call_contact"
    const val TOOL_NONE = "none"
    private const val MAX_CONTACT_NAME_LEN = 60
    private const val MAX_CONTACT_NAME_DIGITS = 6 // reject strings that look like phone numbers

    /** Groq strict-mode schema: every property required, additionalProperties:false. */
    val CALL_ENVELOPE_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply") {
                put("type", "string")
            }
            putJsonObject("tool") {
                put("type", "string")
                putJsonArray("enum") {
                    add(TOOL_CALL_CONTACT)
                    add(TOOL_NONE)
                }
            }
            putJsonObject("contact_name") {
                put("type", "string")
            }
        }
        putJsonArray("required") {
            add("reply")
            add("tool")
            add("contact_name")
        }
        put("additionalProperties", false)
    }

    val SYSTEM_PROMPT: String = """
        You are Jarvis, a voice assistant on the user's phone.
        Always answer with a single JSON object with exactly these fields:
        "reply" (short spoken sentence), "tool" ("call_contact" or "none"), "contact_name" (string).
        Set tool to "call_contact" ONLY when the user clearly asked to call a specific contact by name.
        Never call anyone for questions, lookups, or vague requests.
        Never invent or guess phone numbers — the device resolves them locally.
        If the user's request is ambiguous, set tool to "none" and use reply to ask a short clarifying question.
        Keep every reply short and natural, as it will be spoken aloud.
        contact_name must contain only the contact's name, never a phone number.
    """.trimIndent()

    /** Outcome of parsing one model response. */
    sealed class Outcome {
        /** Plain conversational reply — speak/persist [text]. */
        data class Reply(val text: String) : Outcome()

        /** Model wants to call [contactName]; [ack] is the short lead-in spoken before confirmation. */
        data class ToolCall(val contactName: String, val ack: String) : Outcome()

        /** Malformed / over-broad response — fail closed, speak nothing, execute nothing. */
        object Invalid : Outcome()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Parse a raw model response into an [Outcome].
     *
     * @param allowTool when false (round-trip clarification turn), a tool request is
     *   downgraded to a plain [Outcome.Reply] using the envelope's reply text.
     */
    fun parse(content: String, allowTool: Boolean): Outcome {
        val cleaned = stripFences(content)
        if (cleaned.isBlank()) return Outcome.Invalid

        val envelope = try {
            json.decodeFromString(AssistantEnvelope.serializer(), cleaned)
        } catch (_: Exception) {
            return Outcome.Invalid
        }

        val reply = envelope.reply.trim()
        val tool = envelope.tool.trim()

        when {
            tool.isEmpty() || tool == TOOL_NONE -> {
                return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
            }
            tool == TOOL_CALL_CONTACT -> {
                val name = envelope.contact_name.trim()
                if (!isValidContactName(name)) return Outcome.Invalid
                if (!allowTool) {
                    // Round-trip turn: never execute; fall back to the spoken reply.
                    return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
                }
                return Outcome.ToolCall(contactName = name, ack = reply)
            }
            else -> return Outcome.Invalid // unknown tool — fail closed
        }
    }

    fun isValidContactName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_CONTACT_NAME_LEN) return false
        if (name.any { it.isISOControl() }) return false
        if (name.count { it.isDigit() } > MAX_CONTACT_NAME_DIGITS) return false
        return true
    }

    private fun stripFences(content: String): String {
        var c = content.trim()
        if (!c.startsWith("```")) return c
        c = c.removePrefix("```").removeSuffix("```").trim()
        val nl = c.indexOf('\n')
        if (nl > 0) {
            val firstLine = c.substring(0, nl).trim()
            if (firstLine.equals("json", ignoreCase = true)) c = c.substring(nl + 1).trim()
        } else if (c.equals("json", ignoreCase = true)) {
            c = ""
        }
        return c
    }
}

/**
 * Detects explicit voice call intents from user speech WITHOUT an AI roundtrip.
 *
 * Recognises clear patterns like "call Maa", "phone Dad", "dial office",
 * "ring Rahul", "call my mom", etc. Returns only the bare contact name.
 *
 * This is intentionally conservative: it only fires on utterances that
 * unambiguously mean "initiate a phone call to <name>".  Anything that
 * could be a question, a vague request, or a multi-word sentence with
 * context after the name is NOT matched and falls through to the AI.
 *
 * All validation uses the same rules as [ToolRegistry.isValidContactName]
 * so the extracted name is safe to pass directly to [ContactResolver].
 */
object CallIntentDetector {

    // Recognised call-initiating verb phrases (lowercase).
    private val CALL_PREFIXES = listOf(
        "call ",
        "phone ",
        "dial ",
        "ring ",
        "call up ",
        "place a call to ",
        "make a call to ",
        "please call ",
        "can you call ",
        "could you call ",
        "i want to call ",
        "i'd like to call ",
        "i want you to call ",
    )

    // "my", "the" are stripped when they precede a contact name so that
    // "call my mum" → "mum" and "ring the office" → "office".
    private val NAME_LEAD_STRIPS = listOf("my ", "the ")

    // Words that indicate the utterance is more than just a call command
    // (e.g. "call dad about the meeting"). If the extracted name CONTAINS
    // any of these as a word boundary, the match is rejected and the
    // utterance falls through to the AI.
    private val STOP_WORDS = setOf(
        "about", "regarding", "for", "to", "because", "and", "but",
        "when", "after", "before", "if", "since", "while", "as",
        "on", "in", "at", "from", "with", "without",
    )

    /**
     * Extract the intended contact name from [text].
     *
     * Returns the trimmed name string, or null when the text does not
     * match a recognised call pattern or the extracted name fails validation.
     */
    fun extractCallTarget(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        for (prefix in CALL_PREFIXES) {
            if (lower.startsWith(prefix) && lower.length > prefix.length) {
                var name = trimmed.substring(prefix.length)
                    .trimEnd('.', '!', '?', ',', ';', ':')
                    .trim()
                if (name.isBlank()) return null

                // Strip leading "my ", "the " etc.
                val lName = name.lowercase()
                for (strip in NAME_LEAD_STRIPS) {
                    if (lName.startsWith(strip) && lName.length > strip.length) {
                        name = name.substring(strip.length).trim()
                        break
                    }
                }
                if (name.isBlank()) return null

                // Reject if the name contains a stop-word at a word boundary
                // (indicates the utterance has trailing context, not a bare name).
                val nameWords = name.lowercase().split(Regex("\\s+"))
                if (nameWords.size > 1) {
                    // First word is a stop-word → the name itself is a preposition or
                    // conjunction, not a person's name (e.g. "call before the meeting",
                    // "call on the way", "call at 5 pm").
                    if (nameWords[0] in STOP_WORDS) return null
                    // A stop-word at position 1+ means the user appended context after
                    // the name (e.g. "call dad about the meeting", "call Priya for the project").
                    if (nameWords.drop(1).any { it in STOP_WORDS }) return null
                }

                if (!ToolRegistry.isValidContactName(name)) return null
                return name
            }
        }
        return null
    }
}

/**
 * Voice-only call-flow policy: confirmation matching, answer normalisation, and
 * prompt builders for clarification / confirmation. Pure functions — no Android deps.
 */
object CallFlowPolicy {

    /** Words that count as an exact confirmation. Never substring-matched. */
    private val CONFIRM_WORDS = setOf(
        "yes", "yes please", "yes call", "yes call them", "yeah", "yep", "yup",
        "ok", "okay", "alright", "all right", "sure", "sure thing", "correct",
        "right", "go ahead", "do it", "call them", "please call",
        "call now", "yes ok", "yes okay", "yeah call", "yep call", "confirm",
    )

    /** Words that count as an explicit denial / cancel. */
    private val DENY_WORDS = setOf(
        "no", "nope", "nah", "no thanks", "no thank you", "cancel", "cancel it",
        "stop", "stop it", "never mind", "nevermind", "don't", "dont", "do not",
        "do not call", "don't call", "dont call", "no do not", "wrong", "incorrect",
        "not them", "not that one", "stop no",
    )

    private val FILLER_PREFIXES = listOf("the ", "a ", "an ", "please ", "number ")

    /**
     * Normalise a recognized answer for exact-match confirmation checks:
     * lowercase, strip trailing punctuation, strip common leading filler words.
     */
    fun normalizeAnswer(text: String): String {
        var t = text.trim().lowercase()
        // Normalise spoken punctuation ("yes, call.") into plain word spacing.
        t = t.replace(Regex("[,.!?;:]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (f in FILLER_PREFIXES) {
                if (t.startsWith(f) && t.length > f.length) {
                    t = t.substring(f.length).trimStart()
                    changed = true
                }
            }
        }
        return t
    }

    /** True only on an exact allowlisted confirmation word/phrase. */
    fun isExactConfirmation(text: String): Boolean = normalizeAnswer(text) in CONFIRM_WORDS

    /** True only on an exact allowlisted denial word/phrase. */
    fun isDenial(text: String): Boolean = normalizeAnswer(text) in DENY_WORDS

    /** Spoken ordinal for option lists ("one", "two", …). Out-of-range falls back to the number. */
    fun ordinal(index: Int): String = when (index) {
        1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
        6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"; 10 -> "ten"
        else -> index.toString()
    }

    /** "1" -> "one" for answer matching. Returns null when [text] is not a bare ordinal. */
    fun parseOrdinal(text: String): Int? {
        val n = normalizeAnswer(text)
        return when (n) {
            "one", "1" -> 1; "two", "2" -> 2; "three", "3" -> 3
            "four", "4" -> 4; "five", "5" -> 5; "six", "6" -> 6
            "seven", "7" -> 7; "eight", "8" -> 8; "nine", "9" -> 9
            "ten", "10" -> 10
            else -> null
        }
    }

    /** Spoken prompt when several contacts matched the requested name. */
    fun clarifyContactsPrompt(names: List<String>): String {
        val options = names.mapIndexed { i, n -> "${ordinal(i + 1)}, $n" }.joinToString(". ")
        return "Which contact did you mean? $options. Say the number."
    }

    /**
     * Spoken prompt when no contact exactly matched but there are plausible alternatives.
     * Distinguishes from an exact-ambiguous disambiguation so the user understands why
     * they are being asked.
     */
    fun fuzzyMatchPrompt(names: List<String>): String {
        val options = names.mapIndexed { i, n -> "${ordinal(i + 1)}, $n" }.joinToString(". ")
        return "I couldn't find an exact match. Did you mean: $options? Say the number, or cancel."
    }

    /**
     * Spoken prompt when one contact has several numbers.
     * Duplicate labels are disambiguated as "<label> number two" etc.
     */
    fun clarifyNumbersPrompt(contactName: String, labels: List<String>): String {
        val seen = mutableMapOf<String, Int>()
        val options = labels.mapIndexed { i, label ->
            val count = seen.merge(label, 1) { a, b -> a + b }!!
            val spoken = if (count > 1) "$label number $count" else "$label"
            "${ordinal(i + 1)}, $spoken"
        }.joinToString(". ")
        val n = labels.size
        val noun = if (n == 2) "two" else if (n == 3) "three" else if (n == 4) "four" else n.toString()
        return "$contactName has $noun numbers. $options. Which number should I call?"
    }

    /** Spoken confirmation before any dial. Never speaks the number digits. */
    fun confirmPrompt(contactName: String, label: String): String =
        "Call $contactName on $label? Say yes to confirm, or no to cancel."
}
