package dk.foss.jarvis.contacts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ─────────────────────────────────────────────────────────────────────────────
// Wire model — the strict JSON envelope the model always returns
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Flat JSON envelope that the model returns for every voice/chat request.
 *
 * All fields are required by the strict schema; unused fields are left at their
 * defaults ("" / 0) — only the fields relevant to the active tool carry data.
 *
 * Fields by tool:
 *  call_contact → contact_name
 *  set_alarm    → alarm_hour (0–23), alarm_minute (0–59), alarm_label
 *  open_app     → app_name
 *  none         → reply only
 */
@Serializable
data class AssistantEnvelope(
    val reply: String = "",
    val tool: String = "none",
    // call_contact
    val contact_name: String = "",
    // set_alarm
    val alarm_hour: Int = 0,
    val alarm_minute: Int = 0,
    val alarm_label: String = "",
    // open_app
    val app_name: String = "",
)

// ─────────────────────────────────────────────────────────────────────────────
// ToolRegistry — schema, system prompt, and response parsing
// ─────────────────────────────────────────────────────────────────────────────

object ToolRegistry {

    // ── tool name constants ───────────────────────────────────────────────────

    const val TOOL_NONE          = "none"
    const val TOOL_CALL_CONTACT  = "call_contact"
    const val TOOL_SET_ALARM     = "set_alarm"
    const val TOOL_OPEN_APP      = "open_app"

    private const val MAX_CONTACT_NAME_LEN   = 60
    private const val MAX_CONTACT_NAME_DIGITS = 6
    private const val MAX_LABEL_LEN           = 120
    private const val MAX_APP_NAME_LEN        = 80

    // ── JSON schema ───────────────────────────────────────────────────────────

    /**
     * Multi-tool strict JSON schema.  Every field is required so the model
     * always produces a fully parseable envelope; unused fields carry defaults.
     * `additionalProperties: false` prevents hallucinated extra fields.
     */
    val MULTI_TOOL_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply")        { put("type", "string") }
            putJsonObject("tool") {
                put("type", "string")
                putJsonArray("enum") {
                    add(TOOL_NONE); add(TOOL_CALL_CONTACT)
                    add(TOOL_SET_ALARM); add(TOOL_OPEN_APP)
                }
            }
            putJsonObject("contact_name") { put("type", "string") }
            putJsonObject("alarm_hour")   { put("type", "integer") }
            putJsonObject("alarm_minute") { put("type", "integer") }
            putJsonObject("alarm_label")  { put("type", "string") }
            putJsonObject("app_name")     { put("type", "string") }
        }
        putJsonArray("required") {
            add("reply"); add("tool"); add("contact_name")
            add("alarm_hour"); add("alarm_minute"); add("alarm_label")
            add("app_name")
        }
        put("additionalProperties", false)
    }

    /**
     * Legacy single-tool schema kept so [roundTripReask] continues to compile.
     * New code should use [MULTI_TOOL_SCHEMA].
     */
    val CALL_ENVELOPE_SCHEMA: JsonObject = MULTI_TOOL_SCHEMA

    // ── system prompts ────────────────────────────────────────────────────────

    /**
     * System prompt for the voice / chat orchestration layer.
     * Describes every available tool so the model can select the right one.
     * Keep it concise — it is prepended to every request.
     */
    val MULTI_TOOL_SYSTEM_PROMPT: String = """
        You are Jarvis, a personal voice assistant running on an Android phone.

        You can perform these actions:
        • call_contact — start a phone call to a named person
        • set_alarm    — set a timed alarm or reminder
        • open_app     — open an installed Android application
        • none         — reply conversationally without taking an action

        Always respond with a single JSON object containing ALL of these fields:
          "reply"        — short spoken sentence (always required)
          "tool"         — one of the four values above
          "contact_name" — the contact's name (call_contact only, else "")
          "alarm_hour"   — hour 0–23 as an integer (set_alarm only, else 0)
          "alarm_minute" — minute 0–59 as an integer (set_alarm only, else 0)
          "alarm_label"  — alarm description (set_alarm only, else "")
          "app_name"     — app name to open (open_app only, else "")

        Rules:
        - Use call_contact only when the user explicitly wants to phone a specific person.
        - Use set_alarm only when a time is clearly specified; if the time is missing, ask
          for it in reply and set tool to "none".
        - Use open_app only when the user wants to launch an app.
        - For all other requests use tool "none" and reply conversationally.
        - Phone numbers and contact details never appear in your output — the device
          resolves them locally.
        - Keep every reply short and natural — it will be spoken aloud.
        - If a request is ambiguous, ask for clarification in reply.
        - For set_alarm and open_app: the system tells you whether the Android intent was
          accepted, NOT whether the action was confirmed by the app. Reply honestly:
          say "I've asked your Clock app to set an alarm" rather than "Your alarm is set."
        - If the tool result says it failed, explain that honestly rather than claiming success.
    """.trimIndent()

    /** Backward-compat alias used by legacy [roundTripReask] references. */
    val SYSTEM_PROMPT: String = MULTI_TOOL_SYSTEM_PROMPT

    // ── parsed outcome ────────────────────────────────────────────────────────

    /** Typed result of parsing one model response. */
    sealed class Outcome {
        /** Plain conversational reply — speak/persist [text]. */
        data class Reply(val text: String) : Outcome()

        /** Model wants to call a contact. */
        data class ToolCall(val contactName: String, val ack: String) : Outcome()

        /** Model wants to set an alarm/reminder. */
        data class SetAlarmCall(
            val hour: Int,
            val minute: Int,
            val label: String,
            val ack: String,
        ) : Outcome()

        /** Model wants to open an app. */
        data class OpenAppCall(val appName: String, val ack: String) : Outcome()

        /** Malformed / policy-violating response — fail closed. */
        object Invalid : Outcome()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Parse a raw model response into an [Outcome].
     *
     * @param allowTool  when false (continuation / round-trip turn), any tool
     *   request is downgraded to [Outcome.Reply] using the envelope's reply text.
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
        val tool  = envelope.tool.trim()

        // ── none / empty ──────────────────────────────────────────────────────
        if (tool.isEmpty() || tool == TOOL_NONE) {
            return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
        }

        // ── call_contact ──────────────────────────────────────────────────────
        if (tool == TOOL_CALL_CONTACT) {
            val name = envelope.contact_name.trim()
            if (!isValidContactName(name)) return Outcome.Invalid
            if (!allowTool) return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
            return Outcome.ToolCall(contactName = name, ack = reply)
        }

        // ── set_alarm ─────────────────────────────────────────────────────────
        if (tool == TOOL_SET_ALARM) {
            val hour   = envelope.alarm_hour
            val minute = envelope.alarm_minute
            val label  = envelope.alarm_label.trim()
            if (hour !in 0..23 || minute !in 0..59) return Outcome.Invalid
            // Empty label is allowed: AlarmTool.set() substitutes "Alarm" for blank labels,
            // so the parser does not need to reject it. Only genuinely over-length labels
            // are rejected to prevent a runaway string from reaching the intent extra.
            if (label.length > MAX_LABEL_LEN) return Outcome.Invalid
            if (!allowTool) return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
            return Outcome.SetAlarmCall(hour = hour, minute = minute, label = label, ack = reply)
        }

        // ── open_app ──────────────────────────────────────────────────────────
        if (tool == TOOL_OPEN_APP) {
            val appName = envelope.app_name.trim()
            if (appName.isEmpty() || appName.length > MAX_APP_NAME_LEN) return Outcome.Invalid
            if (!allowTool) return if (reply.isNotEmpty()) Outcome.Reply(reply) else Outcome.Invalid
            return Outcome.OpenAppCall(appName = appName, ack = reply)
        }

        // Unknown tool — fail closed.
        return Outcome.Invalid
    }

    // ── validators ────────────────────────────────────────────────────────────

    fun isValidContactName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_CONTACT_NAME_LEN) return false
        if (name.any { it.isISOControl() }) return false
        if (name.count { it.isDigit() } > MAX_CONTACT_NAME_DIGITS) return false
        return true
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
// CallIntentDetector — local fast-path for explicit call phrases
// ─────────────────────────────────────────────────────────────────────────────

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

    private val NAME_LEAD_STRIPS = listOf("my ", "the ")

    private val STOP_WORDS = setOf(
        "about", "regarding", "for", "to", "because", "and", "but",
        "when", "after", "before", "if", "since", "while", "as",
        "on", "in", "at", "from", "with", "without",
    )

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

                val lName = name.lowercase()
                for (strip in NAME_LEAD_STRIPS) {
                    if (lName.startsWith(strip) && lName.length > strip.length) {
                        name = name.substring(strip.length).trim()
                        break
                    }
                }
                if (name.isBlank()) return null

                val nameWords = name.lowercase().split(Regex("\\s+"))
                if (nameWords.size > 1) {
                    if (nameWords[0] in STOP_WORDS) return null
                    if (nameWords.drop(1).any { it in STOP_WORDS }) return null
                }

                if (!ToolRegistry.isValidContactName(name)) return null
                return name
            }
        }
        return null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CallFlowPolicy — disambiguation / confirmation prompts (voice-only)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pure-function utilities for the voice-only call-disambiguation flow.
 * No Android dependencies; fully unit-testable.
 */
object CallFlowPolicy {

    private val CONFIRM_WORDS = setOf(
        "yes", "yes please", "yes call", "yes call them", "yeah", "yep", "yup",
        "ok", "okay", "alright", "all right", "sure", "sure thing", "correct",
        "right", "go ahead", "do it", "call them", "please call",
        "call now", "yes ok", "yes okay", "yeah call", "yep call", "confirm",
    )

    private val DENY_WORDS = setOf(
        "no", "nope", "nah", "no thanks", "no thank you", "cancel", "cancel it",
        "stop", "stop it", "never mind", "nevermind", "don't", "dont", "do not",
        "do not call", "don't call", "dont call", "no do not", "wrong", "incorrect",
        "not them", "not that one", "stop no",
    )

    private val FILLER_PREFIXES = listOf("the ", "a ", "an ", "please ", "number ")

    fun normalizeAnswer(text: String): String {
        var t = text.trim().lowercase()
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

    fun isExactConfirmation(text: String): Boolean = normalizeAnswer(text) in CONFIRM_WORDS

    fun isDenial(text: String): Boolean = normalizeAnswer(text) in DENY_WORDS

    fun ordinal(index: Int): String = when (index) {
        1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
        6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"; 10 -> "ten"
        else -> index.toString()
    }

    fun parseOrdinal(text: String): Int? {
        val n = normalizeAnswer(text)
        return when (n) {
            "one", "1"   -> 1; "two", "2"   -> 2; "three", "3" -> 3
            "four", "4"  -> 4; "five", "5"  -> 5; "six", "6"   -> 6
            "seven", "7" -> 7; "eight", "8" -> 8; "nine", "9"  -> 9
            "ten", "10"  -> 10
            else -> null
        }
    }

    fun clarifyContactsPrompt(names: List<String>): String {
        val options = names.mapIndexed { i, n -> "${ordinal(i + 1)}, $n" }.joinToString(". ")
        return "Which contact did you mean? $options. Say the number."
    }

    fun fuzzyMatchPrompt(names: List<String>): String {
        val options = names.mapIndexed { i, n -> "${ordinal(i + 1)}, $n" }.joinToString(". ")
        return "I couldn't find an exact match. Did you mean: $options? Say the number, or cancel."
    }

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

    fun confirmPrompt(contactName: String, label: String): String =
        "Call $contactName on $label? Say yes to confirm, or no to cancel."
}
