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
