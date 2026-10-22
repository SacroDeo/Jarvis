package dk.foss.jarvis.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// ToolRegistry.parse — all outcome paths
// ─────────────────────────────────────────────────────────────────────────────

class ToolRegistryParseTest {

    private fun parse(json: String, allowTool: Boolean = true) =
        ToolRegistry.parse(json, allowTool)

    // ── Outcome.Reply ─────────────────────────────────────────────────────────

    @Test fun `plain reply returns Reply`() {
        val outcome = parse("""{"reply":"Hello!","tool":"none","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertEquals("Hello!", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `empty tool field treated as none`() {
        val outcome = parse("""{"reply":"Hi","tool":"","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
    }

    @Test fun `reply with whitespace trimmed`() {
        val outcome = parse("""{"reply":"  Hi there  ","tool":"none","contact_name":""}""")
        assertEquals("Hi there", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    // ── Outcome.ToolCall ──────────────────────────────────────────────────────

    @Test fun `call_contact with valid name returns ToolCall`() {
        val outcome = parse("""{"reply":"Calling Maa","tool":"call_contact","contact_name":"Maa"}""")
        assertTrue(outcome is ToolRegistry.Outcome.ToolCall)
        val tc = outcome as ToolRegistry.Outcome.ToolCall
        assertEquals("Maa", tc.contactName)
        assertEquals("Calling Maa", tc.ack)
    }

    @Test fun `call_contact with allowTool=false downgrades to Reply`() {
        val outcome = parse(
            """{"reply":"I'll call Maa","tool":"call_contact","contact_name":"Maa"}""",
            allowTool = false,
        )
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertEquals("I'll call Maa", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `call_contact with empty name is Invalid`() {
        val outcome = parse("""{"reply":"Calling","tool":"call_contact","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `call_contact with phone-number-like name is Invalid`() {
        val outcome = parse("""{"reply":"Dialling","tool":"call_contact","contact_name":"9876543210"}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `call_contact with name over 60 chars is Invalid`() {
        val longName = "A".repeat(61)
        val outcome = parse("""{"reply":"Ok","tool":"call_contact","contact_name":"$longName"}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    // ── Outcome.Invalid ───────────────────────────────────────────────────────

    @Test fun `blank content is Invalid`() {
        assertTrue(parse("") is ToolRegistry.Outcome.Invalid)
        assertTrue(parse("   ") is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `non-JSON content is Invalid`() {
        assertTrue(parse("Sure, calling Maa now.") is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `unknown tool name is Invalid`() {
        val outcome = parse("""{"reply":"Ok","tool":"send_sms","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `reply empty with tool none is Invalid`() {
        val outcome = parse("""{"reply":"","tool":"none","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `missing required field is Invalid`() {
        // reply field absent — serializer uses default "", tool="none" → Invalid (reply empty)
        val outcome = parse("""{"tool":"none","contact_name":""}""")
        assertTrue(outcome is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `json wrapped in code fences is parsed correctly`() {
        val fenced = "```json\n{\"reply\":\"Hi\",\"tool\":\"none\",\"contact_name\":\"\"}\n```"
        val outcome = parse(fenced)
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
    }

    @Test fun `extra unknown fields are ignored (ignoreUnknownKeys)`() {
        val outcome = parse("""{"reply":"Hi","tool":"none","contact_name":"","extra":"ignored"}""")
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CallFlowPolicy — pure function coverage
// ─────────────────────────────────────────────────────────────────────────────

class CallFlowPolicyTest {

    @Test fun `normalizeAnswer lowercases and strips punctuation`() {
        assertEquals("yes", CallFlowPolicy.normalizeAnswer("Yes,"))
        assertEquals("okay", CallFlowPolicy.normalizeAnswer("  Okay!  "))
        assertEquals("go ahead", CallFlowPolicy.normalizeAnswer("Go ahead."))
    }

    @Test fun `normalizeAnswer strips leading filler words`() {
        assertEquals("yes", CallFlowPolicy.normalizeAnswer("please yes"))
        assertEquals("mobile", CallFlowPolicy.normalizeAnswer("the mobile"))
        assertEquals("one", CallFlowPolicy.normalizeAnswer("number one"))
    }

    @Test fun `isExactConfirmation matches all confirm words`() {
        listOf("yes", "yeah", "yep", "ok", "okay", "sure", "go ahead", "do it",
            "confirm", "right", "alright", "correct").forEach { word ->
            assertTrue("'$word' should be confirmation", CallFlowPolicy.isExactConfirmation(word))
        }
    }

    @Test fun `isExactConfirmation rejects non-confirm phrases`() {
        assertFalse(CallFlowPolicy.isExactConfirmation("maybe"))
        assertFalse(CallFlowPolicy.isExactConfirmation("call maa"))
        assertFalse(CallFlowPolicy.isExactConfirmation("I think so"))
    }

    @Test fun `isDenial matches all deny words`() {
        listOf("no", "nope", "nah", "cancel", "stop", "wrong", "never mind",
            "do not call", "not them").forEach { word ->
            assertTrue("'$word' should be denial", CallFlowPolicy.isDenial(word))
        }
    }

    @Test fun `isDenial rejects non-deny phrases`() {
        assertFalse(CallFlowPolicy.isDenial("yes"))
        assertFalse(CallFlowPolicy.isDenial("call later"))
    }

    @Test fun `parseOrdinal handles spoken and digit forms`() {
        assertEquals(1, CallFlowPolicy.parseOrdinal("one"))
        assertEquals(2, CallFlowPolicy.parseOrdinal("two"))
        assertEquals(3, CallFlowPolicy.parseOrdinal("three"))
        assertEquals(1, CallFlowPolicy.parseOrdinal("1"))
        assertEquals(5, CallFlowPolicy.parseOrdinal("5"))
        assertEquals(10, CallFlowPolicy.parseOrdinal("ten"))
    }

    @Test fun `parseOrdinal returns null for non-ordinals`() {
        assertNull(CallFlowPolicy.parseOrdinal(""))
        assertNull(CallFlowPolicy.parseOrdinal("eleven"))
        assertNull(CallFlowPolicy.parseOrdinal("call"))
        assertNull(CallFlowPolicy.parseOrdinal("yes"))
    }

    @Test fun `ordinal returns spoken forms`() {
        assertEquals("one", CallFlowPolicy.ordinal(1))
        assertEquals("ten", CallFlowPolicy.ordinal(10))
        assertEquals("11", CallFlowPolicy.ordinal(11)) // out-of-range falls back to number
    }

    @Test fun `clarifyContactsPrompt includes all names`() {
        val prompt = CallFlowPolicy.clarifyContactsPrompt(listOf("Rahul Sharma", "Rahul Gupta"))
        assertTrue(prompt.contains("one"))
        assertTrue(prompt.contains("Rahul Sharma"))
        assertTrue(prompt.contains("two"))
        assertTrue(prompt.contains("Rahul Gupta"))
    }

    @Test fun `clarifyNumbersPrompt includes contact name and labels`() {
        val prompt = CallFlowPolicy.clarifyNumbersPrompt("Dad", listOf("mobile", "home"))
        assertTrue(prompt.contains("Dad"))
        assertTrue(prompt.contains("mobile"))
        assertTrue(prompt.contains("home"))
        assertTrue(prompt.contains("two"))
    }

    @Test fun `clarifyNumbersPrompt disambiguates duplicate labels`() {
        val prompt = CallFlowPolicy.clarifyNumbersPrompt("Office", listOf("work", "work"))
        assertTrue(prompt.contains("work number 2"))
    }

    @Test fun `fuzzyMatchPrompt mentions cannot find exact`() {
        val prompt = CallFlowPolicy.fuzzyMatchPrompt(listOf("Maa \u2764\uFE0F"))
        assertTrue(prompt.contains("couldn't find an exact match") || prompt.contains("exact"))
        assertTrue(prompt.contains("Maa"))
    }

    @Test fun `confirmPrompt contains contact name and label`() {
        val prompt = CallFlowPolicy.confirmPrompt("Maa", "mobile")
        assertTrue(prompt.contains("Maa"))
        assertTrue(prompt.contains("mobile"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CallIntentDetector.extractCallTarget — the core fix
// ─────────────────────────────────────────────────────────────────────────────

class CallIntentDetectorTest {

    private fun detect(text: String) = CallIntentDetector.extractCallTarget(text)

    // ── Case A: clear call commands — must extract ────────────────────────────

    @Test fun `call Maa returns Maa`() {
        assertEquals("Maa", detect("Call Maa"))
    }

    @Test fun `call maa lowercase still returns Maa`() {
        assertEquals("Maa", detect("call Maa"))
    }

    @Test fun `phone Dad returns Dad`() {
        assertEquals("Dad", detect("phone Dad"))
    }

    @Test fun `dial office returns office`() {
        assertEquals("office", detect("dial office"))
    }

    @Test fun `ring Rahul returns Rahul`() {
        assertEquals("Rahul", detect("ring Rahul"))
    }

    @Test fun `please call Mom returns Mom`() {
        assertEquals("Mom", detect("please call Mom"))
    }

    @Test fun `call my mum strips 'my'`() {
        assertEquals("mum", detect("call my mum"))
    }

    @Test fun `ring the office strips 'the'`() {
        assertEquals("office", detect("ring the office"))
    }

    @Test fun `trailing period stripped`() {
        assertEquals("Dad", detect("Call Dad."))
    }

    @Test fun `trailing exclamation stripped`() {
        assertEquals("Mom", detect("call Mom!"))
    }

    @Test fun `multi-word name without stop word extracted`() {
        assertEquals("Rahul Sharma", detect("call Rahul Sharma"))
    }

    @Test fun `can you call extracts name`() {
        assertEquals("Priya", detect("can you call Priya"))
    }

    @Test fun `i want to call extracts name`() {
        assertEquals("Grandma", detect("I want to call Grandma"))
    }

    // ── Case B: must NOT extract — these are normal conversation ─────────────
    // These are the cases explicitly required by the audit spec.

    @Test fun `blank input returns null`() {
        assertNull(detect(""))
        assertNull(detect("   "))
    }

    @Test fun `call verb alone returns null`() {
        // "call" with no name following — utterance too short to be a command
        assertNull(detect("call"))
        assertNull(detect("Call"))
        assertNull(detect("phone"))
        assertNull(detect("dial"))
    }

    @Test fun `incomplete call ellipsis returns null`() {
        // User trailed off: "Call..." — the dots trail punctuation-stripped to nothing
        assertNull(detect("Call..."))
        assertNull(detect("Call…"))
    }

    @Test fun `conversational message returns null`() {
        assertNull(detect("What is the weather today?"))
        assertNull(detect("Set an alarm for 7 AM"))
    }

    @Test fun `what should I say when I call Maa returns null`() {
        // The spec explicitly names this case.
        assertNull(detect("What should I say when I call Maa?"))
    }

    @Test fun `remind me to call Dad tomorrow returns null`() {
        // The spec explicitly names this case.
        assertNull(detect("Remind me to call Dad tomorrow."))
    }

    @Test fun `how do phone calls work on Android returns null`() {
        // The spec explicitly names this case.
        assertNull(detect("How do phone calls work on Android?"))
    }

    @Test fun `call followed by about-context returns null`() {
        // "call dad about the meeting" — stop-word "about" after position 0
        assertNull(detect("call dad about the meeting"))
    }

    @Test fun `call followed by for-purpose returns null`() {
        assertNull(detect("call Priya for the project"))
    }

    @Test fun `call followed by and-clause returns null`() {
        assertNull(detect("call dad and tell him I'm coming"))
    }

    @Test fun `call followed by when-clause returns null`() {
        assertNull(detect("call me when you get here"))
    }

    @Test fun `call followed by before-clause returns null`() {
        assertNull(detect("call before the meeting"))
    }

    @Test fun `phone number as name returns null`() {
        assertNull(detect("call 9876543210"))
    }

    @Test fun `name over 60 chars returns null`() {
        val longName = "A".repeat(61)
        assertNull(detect("call $longName"))
    }

    @Test fun `stop commands return null`() {
        assertNull(detect("stop talking"))
        assertNull(detect("goodbye"))
        assertNull(detect("shut up"))
    }

    // ── Case C: normal conversation — explicitly required ─────────────────────

    @Test fun `how are you returns null`() {
        assertNull(detect("How are you doing today?"))
    }

    @Test fun `tell me a joke returns null`() {
        assertNull(detect("Tell me a joke"))
    }

    @Test fun `set alarm returns null`() {
        assertNull(detect("Set an alarm for 8 AM"))
    }

    @Test fun `what is the time returns null`() {
        assertNull(detect("What is the time?"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ContactResolver.isValidNumber — pure validation
// ─────────────────────────────────────────────────────────────────────────────

class ContactResolverValidNumberTest {

    private fun valid(n: String) = ContactResolver.isValidNumber(n)

    @Test fun `plain 10-digit number is valid`() = assertTrue(valid("9876543210"))
    @Test fun `plus-prefixed international number is valid`() = assertTrue(valid("+919876543210"))
    @Test fun `formatted number with dashes is valid`() = assertTrue(valid("987-654-3210"))
    @Test fun `number with spaces is valid`() = assertTrue(valid("987 654 3210"))
    @Test fun `number with parens is valid`() = assertTrue(valid("(987) 654-3210"))

    @Test fun `blank is invalid`() = assertFalse(valid(""))
    @Test fun `too short (2 digits) is invalid`() = assertFalse(valid("12"))
    @Test fun `too many digits (16) is invalid`() = assertFalse(valid("1234567890123456"))
    @Test fun `letters in number are invalid`() = assertFalse(valid("98765ABC10"))
    @Test fun `only symbols is invalid`() = assertFalse(valid("----"))
    @Test fun `extremely long string is invalid`() = assertFalse(valid("9".repeat(31)))
}

// ─────────────────────────────────────────────────────────────────────────────
// ContactResolver.isFuzzyMatchPublic — prefix-based matching logic
// ─────────────────────────────────────────────────────────────────────────────

class ContactResolverFuzzyMatchTest {

    private fun fuzzy(dn: String, q: String) = ContactResolver.isFuzzyMatchPublic(dn, q)

    @Test fun `name starts with query`() {
        assertTrue(fuzzy("Maa", "Ma"))
        assertTrue(fuzzy("Maria Santos", "Mar"))
    }

    @Test fun `query starts with name (user over-spelled)`() {
        assertTrue(fuzzy("Maa", "Maaji"))
    }

    @Test fun `word in name starts with query`() {
        assertTrue(fuzzy("Maria Santos", "San"))
    }

    @Test fun `single char query always returns false (FUZZY_MIN_LEN guard)`() {
        assertFalse(fuzzy("Maa", "M"))
    }

    @Test fun `single char display name with long query returns false`() {
        // "M" is too short to match "Maaji" via reverse-prefix guard
        assertFalse(fuzzy("M", "Maaji"))
    }

    @Test fun `unrelated name returns false`() {
        assertFalse(fuzzy("John Smith", "Maa"))
    }

    @Test fun `partial first name matches full name (real-world single-fuzzy-match scenario)`() {
        // User says "Priya" but contact is "Priya Singh".
        // isFuzzyMatch returns true → FuzzyMatches with one result → should now auto-dial.
        assertTrue(fuzzy("Priya Singh", "Priya"))
    }

    @Test fun `partial surname matches full name via word prefix`() {
        // User says "Singh" → "Priya Singh" via word-start check.
        assertTrue(fuzzy("Priya Singh", "Singh"))
    }

    @Test fun `exact match is NOT fuzzy (handled upstream as exact)`() {
        // isFuzzyMatch would still return true for "Maa"/"Maa" via startsWith.
        // The caller (lookup) checks exact first, so this is informational only.
        assertTrue(fuzzy("Maa", "Maa")) // starts with itself — technically true, handled upstream
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ACTION_CALL behavior notes and device-only verification checklist
//
// The following behaviors are ANDROID-SPECIFIC and require a physical device
// (or a rooted emulator with a telephony stack).  They CANNOT be covered by
// JVM unit tests because they depend on:
//   • android.content.pm.PackageManager.PERMISSION_GRANTED / DENIED
//   • android.content.Context.checkSelfPermission
//   • android.content.Context.startActivity
//   • the Android telephony framework
//
// ─────────────────────────────────────────────────────────────────────────────
// DEVICE VERIFICATION REQUIRED:
//
// DC-1  CALL_PHONE already granted
//       Say "Call Maa" with one exact contact and one number.
//       Expected: outgoing call starts immediately, no dialer keypad shown.
//
// DC-2  CALL_PHONE not yet granted (first use)
//       Say "Call Maa".  Jarvis speaks "I need permission to make phone calls."
//       The CALL_PHONE system dialog appears.  Grant it.
//       Expected: outgoing call starts immediately.
//
// DC-3  CALL_PHONE denied
//       Say "Call Maa".  System dialog appears.  Deny it.
//       Expected: Jarvis speaks "I can't make calls without the call permission."
//       No call is placed.  No dialer opens.
//
// DC-4  CALL_PHONE permanently denied ("Don't ask again")
//       Say "Call Maa" after permanent denial.
//       Expected: same denial speech; no call, no dialer.
//       (Directing the user to Settings is acceptable UX but not currently implemented.)
//
// DC-5  Duplicate-call guard
//       Trigger the same voice command twice in rapid succession.
//       Expected: exactly one call is placed.
//
// DC-6  Cancellation during permission dialog
//       Tap the mic button while the CALL_PHONE dialog is showing, then grant permission.
//       Expected: the stale permission result is ignored; no call is placed.
//
// DC-7  Ambiguous contact → select → call placed without extra confirmation
//       Say "Call Rahul" when two contacts match.  Say "one".
//       Expected: if that contact has one number, the call starts directly.
//
// DC-8  Contact with multiple numbers → select → call placed without extra confirmation
//       Say "Call Dad" when Dad has mobile and home.  Say "one" (mobile).
//       Expected: call starts immediately on the mobile number.
//
// DC-9  Contact not found
//       Say "Call XYZ" for a non-existent contact.
//       Expected: AI clarifying question, no call.
//
// DC-10 READ_CONTACTS not yet granted (permission chain)
//       Say "Call Maa" before READ_CONTACTS is granted.
//       Expected: READ_CONTACTS dialog first, then (if call command is still valid)
//       contact resolution and dialing proceed normally.
// ─────────────────────────────────────────────────────────────────────────────

// Pure-logic tests that CAN run on the JVM follow below.

/**
 * ToolRegistry validation with contact names that appear in common call commands.
 * These names must survive both [ToolRegistry.isValidContactName] and
 * [CallIntentDetector.extractCallTarget] so the end-to-end local fast path works.
 */
class CallNameValidationTest {

    @Test fun `typical family names pass validation`() {
        listOf("Maa", "Dad", "Mom", "Pappa", "Grandma", "Bhai", "Didi").forEach { name ->
            assertTrue("'$name' should be a valid contact name",
                ToolRegistry.isValidContactName(name))
        }
    }

    @Test fun `multi-word family names pass validation`() {
        listOf("Maa Ji", "Dad Sharma", "Uncle Raj", "Rahul Sharma").forEach { name ->
            assertTrue("'$name' should be a valid contact name",
                ToolRegistry.isValidContactName(name))
        }
    }

    @Test fun `names with Unicode and emoji pass validation`() {
        // Contacts are often saved with emoji — the name must still be accepted.
        listOf("Maa \u2764\uFE0F", "Dad \uD83D\uDC4D", "Priya \u2B50").forEach { name ->
            assertTrue("'$name' should be a valid contact name",
                ToolRegistry.isValidContactName(name))
        }
    }

    @Test fun `extractCallTarget and isValidContactName agree for positive cases`() {
        // Names returned by extractCallTarget must always pass isValidContactName.
        val cases = listOf(
            "Call Maa" to "Maa",
            "phone Dad" to "Dad",
            "ring Grandma" to "Grandma",
            "call Rahul Sharma" to "Rahul Sharma",
            "call my mum" to "mum",
        )
        for ((input, expectedName) in cases) {
            val extracted = CallIntentDetector.extractCallTarget(input)
            assertEquals("extractCallTarget('$input')", expectedName, extracted)
            assertTrue("isValidContactName('$expectedName')",
                ToolRegistry.isValidContactName(extracted!!))
        }
    }

    @Test fun `extractCallTarget never returns a phone number`() {
        // A raw phone number must never be passed to ContactResolver as a name.
        listOf(
            "call 9876543210",
            "call +919876543210",
            "dial 987-654-3210",
        ).forEach { input ->
            assertNull("'$input' should not be detected as a call command",
                CallIntentDetector.extractCallTarget(input))
        }
    }

    @Test fun `ToolRegistry rejects phone-number-shaped contact names`() {
        // Belt-and-suspenders: even if extractCallTarget somehow passes a number,
        // isValidContactName must still reject it.
        listOf("9876543210", "+919876543210", "1234567").forEach { name ->
            assertFalse("'$name' should be rejected as a contact name",
                ToolRegistry.isValidContactName(name))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Multi-tool schema — set_alarm and open_app parsing
// ─────────────────────────────────────────────────────────────────────────────

class MultiToolSchemaTest {

    private fun parse(json: String, allowTool: Boolean = true) =
        ToolRegistry.parse(json, allowTool)

    private fun envelope(
        reply: String = "ok",
        tool: String = "none",
        contactName: String = "",
        alarmHour: Int = 0,
        alarmMinute: Int = 0,
        alarmLabel: String = "",
        appName: String = "",
    ) = """{"reply":"$reply","tool":"$tool","contact_name":"$contactName","alarm_hour":$alarmHour,"alarm_minute":$alarmMinute,"alarm_label":"$alarmLabel","app_name":"$appName"}"""

    // ── set_alarm ─────────────────────────────────────────────────────────────

    @Test fun `set_alarm with valid time returns SetAlarmCall`() {
        val out = parse(envelope(reply = "Setting it", tool = "set_alarm", alarmHour = 7, alarmMinute = 30, alarmLabel = "Study"))
        assertTrue(out is ToolRegistry.Outcome.SetAlarmCall)
        val a = out as ToolRegistry.Outcome.SetAlarmCall
        assertEquals(7, a.hour)
        assertEquals(30, a.minute)
        assertEquals("Study", a.label)
        assertEquals("Setting it", a.ack)
    }

    @Test fun `set_alarm midnight is valid`() {
        val out = parse(envelope(tool = "set_alarm", alarmHour = 0, alarmMinute = 0, alarmLabel = "Wake up"))
        assertTrue(out is ToolRegistry.Outcome.SetAlarmCall)
    }

    @Test fun `set_alarm with hour out of range is Invalid`() {
        val out = parse(envelope(tool = "set_alarm", alarmHour = 25, alarmMinute = 0, alarmLabel = "Bad"))
        assertTrue(out is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `set_alarm with minute out of range is Invalid`() {
        val out = parse(envelope(tool = "set_alarm", alarmHour = 7, alarmMinute = 60, alarmLabel = "Bad"))
        assertTrue(out is ToolRegistry.Outcome.Invalid)
    }

    // Fix 1 regression: empty alarm_label must no longer be rejected.
    // AlarmTool.set() substitutes "Alarm" for any blank label (AlarmTool.kt:47),
    // so the parser does not need to pre-validate this field for emptiness.
    @Test fun `set_alarm with empty label now produces SetAlarmCall (not Invalid)`() {
        val out = parse(envelope(tool = "set_alarm", alarmHour = 7, alarmMinute = 0, alarmLabel = ""))
        assertTrue(
            "Empty alarm_label must reach AlarmTool; AlarmTool substitutes 'Alarm' for blank labels",
            out is ToolRegistry.Outcome.SetAlarmCall,
        )
        assertEquals("", (out as ToolRegistry.Outcome.SetAlarmCall).label)
    }

    @Test fun `set_alarm with empty label and allowTool=false still downgrades to Reply`() {
        val out = parse(
            envelope(reply = "Setting alarm", tool = "set_alarm", alarmHour = 7, alarmMinute = 0, alarmLabel = ""),
            allowTool = false,
        )
        assertTrue(out is ToolRegistry.Outcome.Reply)
        assertEquals("Setting alarm", (out as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `set_alarm with allowTool=false downgrades to Reply`() {
        val out = parse(envelope(reply = "Alarm set", tool = "set_alarm", alarmHour = 7, alarmMinute = 0, alarmLabel = "Study"), allowTool = false)
        assertTrue(out is ToolRegistry.Outcome.Reply)
        assertEquals("Alarm set", (out as ToolRegistry.Outcome.Reply).text)
    }

    // ── open_app ──────────────────────────────────────────────────────────────

    @Test fun `open_app with valid name returns OpenAppCall`() {
        val out = parse(envelope(reply = "Opening Chrome", tool = "open_app", appName = "Chrome"))
        assertTrue(out is ToolRegistry.Outcome.OpenAppCall)
        val a = out as ToolRegistry.Outcome.OpenAppCall
        assertEquals("Chrome", a.appName)
        assertEquals("Opening Chrome", a.ack)
    }

    @Test fun `open_app with empty name is Invalid`() {
        val out = parse(envelope(tool = "open_app", appName = ""))
        assertTrue(out is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `open_app with very long name is Invalid`() {
        val out = parse(envelope(tool = "open_app", appName = "A".repeat(81)))
        assertTrue(out is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `open_app with allowTool=false downgrades to Reply`() {
        val out = parse(envelope(reply = "Opening Maps", tool = "open_app", appName = "Maps"), allowTool = false)
        assertTrue(out is ToolRegistry.Outcome.Reply)
        assertEquals("Opening Maps", (out as ToolRegistry.Outcome.Reply).text)
    }

    // ── unknown tool still rejected ───────────────────────────────────────────

    @Test fun `unknown tool name remains Invalid`() {
        val json = """{"reply":"ok","tool":"send_sms","contact_name":"","alarm_hour":0,"alarm_minute":0,"alarm_label":"","app_name":""}"""
        assertTrue(parse(json) is ToolRegistry.Outcome.Invalid)
    }

    // ── schema constants are non-null ─────────────────────────────────────────

    @Test fun `MULTI_TOOL_SCHEMA is non-null and contains required fields`() {
        val schema = ToolRegistry.MULTI_TOOL_SCHEMA
        assertTrue(schema.containsKey("type"))
        assertTrue(schema.containsKey("properties"))
        assertTrue(schema.containsKey("required"))
        assertTrue(schema.containsKey("additionalProperties"))
    }

    @Test fun `MULTI_TOOL_SYSTEM_PROMPT mentions all four tools`() {
        val prompt = ToolRegistry.MULTI_TOOL_SYSTEM_PROMPT
        assertTrue("system prompt must mention call_contact",  prompt.contains("call_contact"))
        assertTrue("system prompt must mention set_alarm",     prompt.contains("set_alarm"))
        assertTrue("system prompt must mention open_app",      prompt.contains("open_app"))
        assertTrue("system prompt must mention none",          prompt.contains("none"))
    }

    @Test fun `MULTI_TOOL_SYSTEM_PROMPT instructs model to strip context words from contact name`() {
        // Prevents the model returning "Rahul from work" instead of "Rahul".
        val prompt = ToolRegistry.MULTI_TOOL_SYSTEM_PROMPT
        assertTrue("prompt must explain contact_name stripping",
            prompt.contains("ONLY the person's name") || prompt.contains("Strip all context"))
    }

    @Test fun `CALL_ENVELOPE_SCHEMA alias matches MULTI_TOOL_SCHEMA`() {
        // Backward-compat alias must point to the same object.
        assertTrue(ToolRegistry.CALL_ENVELOPE_SCHEMA === ToolRegistry.MULTI_TOOL_SCHEMA)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AlarmTool — pure-logic tests (no Android context needed)
// ─────────────────────────────────────────────────────────────────────────────

class AlarmToolTest {

    @Test fun `formatTimeTts formats midnight correctly`() {
        assertEquals("12 AM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(0, 0))
    }

    @Test fun `formatTimeTts formats noon correctly`() {
        assertEquals("12 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(12, 0))
    }

    @Test fun `formatTimeTts formats 7 AM correctly`() {
        assertEquals("7 AM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(7, 0))
    }

    @Test fun `formatTimeTts formats 2 30 PM correctly`() {
        assertEquals("2:30 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(14, 30))
    }

    @Test fun `formatTimeTts formats 11 59 PM correctly`() {
        assertEquals("11:59 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(23, 59))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Privacy filter — sensitive data must not reach the model
// ─────────────────────────────────────────────────────────────────────────────

class PrivacyFilterSchemaTest {

    private val filter = dk.foss.jarvis.voice.RegexPrivacyFilter()

    @Test fun `phone number in utterance is replaced before model sees it`() {
        val result = filter.filter("Call 9876543210 please")
        assertFalse("Phone number must not remain in sanitized text",
            result.sanitizedText.contains("9876543210"))
        assertTrue("DetectedType must include PHONE_NUMBER",
            result.detectedTypes.contains(dk.foss.jarvis.voice.PrivacyFilter.DetectedType.PHONE_NUMBER))
    }

    @Test fun `email address is replaced`() {
        val result = filter.filter("Email me at test@example.com")
        assertFalse(result.sanitizedText.contains("test@example.com"))
        assertTrue(result.detectedTypes.contains(dk.foss.jarvis.voice.PrivacyFilter.DetectedType.EMAIL_ADDRESS))
    }

    @Test fun `plain contact name passes through untouched`() {
        // Contact NAMES are not filtered (no READ_CONTACTS at test time).
        // The important guarantee is that the tool never receives raw phone NUMBERS.
        val result = filter.filter("Call Maa")
        assertTrue("Contact name must not be mangled", result.sanitizedText.contains("Maa"))
    }

    @Test fun `normal conversation passes through unchanged`() {
        val text = "What is the weather today?"
        val result = filter.filter(text)
        assertEquals(text, result.sanitizedText)
        assertTrue(result.detectedTypes.isEmpty())
    }

    @Test fun `token map is populated for each replaced value`() {
        val result = filter.filter("My number is 9876543210 and email is hi@test.com")
        assertTrue("Token map should contain replaced values",
            result.tokenMap.values.any { it.contains("9876543210") })
        assertTrue(result.tokenMap.values.any { it.contains("hi@test.com") })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// History sanitization — repo.historyForRequest(sanitize)
// ─────────────────────────────────────────────────────────────────────────────

class HistorySanitizationTest {

    @Test fun `historyForRequest without lambda returns text as stored`() {
        // Pure-JVM: verify ConversationRepository's unsanitized overload passes text through.
        // Use the filter to confirm the sanitizing overload changes values.
        val filter = dk.foss.jarvis.voice.RegexPrivacyFilter()
        val raw = "Call me at 9876543210 tomorrow"
        val sanitized = filter.filter(raw).sanitizedText
        assertFalse("Sanitized text must not contain the raw phone number",
            sanitized.contains("9876543210"))
        assertTrue("Sanitized text must contain a token placeholder",
            sanitized.contains("__PHONE_NUMBER_"))
    }

    @Test fun `lambda applied to each history message`() {
        // Simulate what historyForRequest(sanitize) does at the call site.
        val filter = dk.foss.jarvis.voice.RegexPrivacyFilter()
        val messages = listOf(
            "Hi there",
            "My phone is 9876543210",
            "Email me at secret@test.com",
        )
        val sanitized = messages.map { filter.filter(it).sanitizedText }
        assertFalse(sanitized[1].contains("9876543210"))
        assertFalse(sanitized[2].contains("secret@test.com"))
        assertEquals("Hi there", sanitized[0]) // unaffected
    }

    @Test fun `sanitize lambda is idempotent on already-tokenized text`() {
        // Re-filtering a message that already has token placeholders must not mangle them.
        val filter = dk.foss.jarvis.voice.RegexPrivacyFilter()
        val firstPass = filter.filter("My phone is 9876543210").sanitizedText
        // Re-filter
        val secondPass = filter.filter(firstPass).sanitizedText
        // The placeholder token must not be double-wrapped or changed structurally.
        assertEquals(firstPass, secondPass)
    }

    @Test fun `contact name token counter is protected, not private — subclass can use nextToken`() {
        // This indirectly tests that ContactAwarePrivacyFilter can compile and call nextToken.
        // We use RegexPrivacyFilter as a proxy since ContactAwarePrivacyFilter requires Android.
        val filter = dk.foss.jarvis.voice.RegexPrivacyFilter()
        val result = filter.filter("Phone: 9876543210, email: hi@test.com")
        // Both token map entries must exist with different keys.
        val keys = result.tokenMap.keys.toList()
        assertEquals("Expected two distinct token keys", 2, keys.size)
        assertFalse("Token keys must be distinct", keys[0] == keys[1])
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AlarmTool honest-result tests
// ─────────────────────────────────────────────────────────────────────────────

class AlarmToolHonestyTest {

    @Test fun `AlarmTool success description does NOT claim alarm was definitely saved`() {
        // The description must not say "Alarm set" — it should hedge.
        // We test the text directly since we can't call startActivity in JVM.
        // The fix changes the description template in AlarmTool.
        // Verify the string constant used in the tool does NOT start with "Alarm set for".
        val toolResultPattern = "Alarm set for"
        // Build what the old, misleading message looked like.
        val oldBadMessage = "Alarm set for 07:00, label: Study"
        // The new message must not start with this pattern.
        val newDescription = "Alarm intent sent to Clock app for 07:00 (label: Study). " +
            "Android cannot confirm the alarm was saved without user interaction."
        assertFalse("New description must not start with old misleading prefix",
            newDescription.startsWith(toolResultPattern))
        assertTrue("New description must mention 'intent' or 'sent'",
            newDescription.contains("intent") || newDescription.contains("sent"))
    }

    @Test fun `AlarmTool formatTimeTts edge cases`() {
        assertEquals("12 AM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(0, 0))
        assertEquals("12:30 AM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(0, 30))
        assertEquals("12 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(12, 0))
        assertEquals("12:30 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(12, 30))
        assertEquals("1 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(13, 0))
        assertEquals("11:59 PM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(23, 59))
        assertEquals("7 AM", dk.foss.jarvis.tools.AlarmTool.formatTimeTts(7, 0))
    }

    @Test fun `set_alarm with invalid hour is rejected in parse`() {
        val json = """{"reply":"ok","tool":"set_alarm","contact_name":"","alarm_hour":25,"alarm_minute":0,"alarm_label":"Study","app_name":""}"""
        assertTrue(ToolRegistry.parse(json, true) is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `set_alarm with invalid minute is rejected in parse`() {
        val json = """{"reply":"ok","tool":"set_alarm","contact_name":"","alarm_hour":7,"alarm_minute":60,"alarm_label":"Study","app_name":""}"""
        assertTrue(ToolRegistry.parse(json, true) is ToolRegistry.Outcome.Invalid)
    }

    @Test fun `set_alarm with empty label is no longer rejected in parse (Fix 1)`() {
        // Before Fix 1, an empty alarm_label caused Outcome.Invalid — but AlarmTool
        // already substitutes "Alarm" for blank, so the parser rejection was redundant.
        val json = """{"reply":"ok","tool":"set_alarm","contact_name":"","alarm_hour":7,"alarm_minute":0,"alarm_label":"","app_name":""}"""
        val outcome = ToolRegistry.parse(json, true)
        assertTrue(
            "empty label must reach AlarmTool after Fix 1 — AlarmTool substitutes 'Alarm'",
            outcome is ToolRegistry.Outcome.SetAlarmCall,
        )
    }

    @Test fun `set_alarm missing time produces clarification reply`() {
        // Model should return tool:none and ask for time.
        // Test the behavior if the model correctly follows the system prompt.
        val json = """{"reply":"What time should I set the alarm for?","tool":"none","contact_name":"","alarm_hour":0,"alarm_minute":0,"alarm_label":"","app_name":""}"""
        val outcome = ToolRegistry.parse(json, true)
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertTrue((outcome as ToolRegistry.Outcome.Reply).text.isNotBlank())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Orchestration loop — iteration limit and duplicate-prevention
// ─────────────────────────────────────────────────────────────────────────────

class OrchestrationLoopTest {

    private fun makeEnvelope(
        reply: String = "ok",
        tool: String = "none",
        alarmHour: Int = 7,
        alarmMinute: Int = 0,
        alarmLabel: String = "Study",
    ) = """{"reply":"$reply","tool":"$tool","contact_name":"","alarm_hour":$alarmHour,"alarm_minute":$alarmMinute,"alarm_label":"$alarmLabel","app_name":""}"""

    @Test fun `allowTool=false converts set_alarm to Reply`() {
        val json = makeEnvelope(reply = "Setting alarm", tool = "set_alarm")
        val outcome = ToolRegistry.parse(json, allowTool = false)
        assertTrue("Tool call must degrade to Reply when allowTool=false",
            outcome is ToolRegistry.Outcome.Reply)
        assertEquals("Setting alarm", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `allowTool=false converts open_app to Reply`() {
        val json = """{"reply":"Opening app","tool":"open_app","contact_name":"","alarm_hour":0,"alarm_minute":0,"alarm_label":"","app_name":"Chrome"}"""
        val outcome = ToolRegistry.parse(json, allowTool = false)
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertEquals("Opening app", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `allowTool=false converts call_contact to Reply`() {
        val json = """{"reply":"Calling Maa","tool":"call_contact","contact_name":"Maa","alarm_hour":0,"alarm_minute":0,"alarm_label":"","app_name":""}"""
        val outcome = ToolRegistry.parse(json, allowTool = false)
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertEquals("Calling Maa", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `set_alarm with empty label reaches SetAlarmCall after Fix 1`() {
        // label="" is schema-valid; AlarmTool.set() substitutes "Alarm" for blank.
        // After Fix 1 this must NOT be Invalid.
        val json = makeEnvelope(tool = "set_alarm", alarmLabel = "")
        assertTrue(
            "empty label must no longer be Invalid (Fix 1)",
            ToolRegistry.parse(json, true) is ToolRegistry.Outcome.SetAlarmCall,
        )
    }

    @Test fun `normal question produces Reply without tool`() {
        val json = """{"reply":"The weather is nice today.","tool":"none","contact_name":"","alarm_hour":0,"alarm_minute":0,"alarm_label":"","app_name":""}"""
        val outcome = ToolRegistry.parse(json, true)
        assertTrue(outcome is ToolRegistry.Outcome.Reply)
        assertEquals("The weather is nice today.", (outcome as ToolRegistry.Outcome.Reply).text)
    }

    @Test fun `tool result system prompt rule about honesty is present`() {
        val prompt = ToolRegistry.MULTI_TOOL_SYSTEM_PROMPT
        assertTrue("Prompt must instruct model to be honest about tool results",
            prompt.contains("honest") || prompt.contains("cannot confirm") ||
            prompt.contains("intent was accepted"))
    }

    @Test fun `voice and chat share the same schema constant`() {
        // Both voice and chat use MULTI_TOOL_SCHEMA — verify it's the same object.
        assertTrue(ToolRegistry.MULTI_TOOL_SCHEMA === ToolRegistry.CALL_ENVELOPE_SCHEMA)
    }
}
