package dk.foss.jarvis.data

import dk.foss.jarvis.voice.TtsVoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the TTS voice preference migration introduced by Fix 3.
 *
 * Background:
 *  Phase 8 renamed the DataStore key from "tts_voice_id" (TTS_VOICE_ID) to
 *  "tts_voice_user" (TTS_VOICE_USER) without a read-path fallback.  Any user
 *  whose voice was persisted under the old key lost their selection silently
 *  after installing Phase 8, because the new code only read TTS_VOICE_USER.
 *
 * Fix 3 adds the fallback:
 *   ttsVoiceUserId = p[Keys.TTS_VOICE_USER] ?: p[Keys.TTS_VOICE_ID]
 *
 * This test file covers:
 *  1. The preference-selection logic (new-key precedence / legacy-key fallback / both absent).
 *  2. TtsVoice JSON serialization round-trip (what actually lives in the preference).
 *  3. The null-safety guards that wrap every preference read.
 *
 * DataStore itself is an Android component and cannot be exercised in a JVM
 * unit test.  The logic under test is:
 *  - the Kotlin ?: null-coalescing expression that picks TTS_VOICE_USER vs TTS_VOICE_ID
 *  - the Json codec used by SettingsStore / ConversationViewModel / SettingsScreen
 *
 * Device-side smoke tests required:
 *  DV-1  Install the new APK on top of an installation that had a voice saved
 *        in the OLD key ("tts_voice_id").  Jarvis must speak with that voice
 *        without requiring the user to re-select.
 *  DV-2  Open Settings → Voice, pick a different voice, press Save.
 *        On next launch the newly saved voice must be used.
 *  DV-3  Open Settings → Voice, clear the selection (no voice), press Save.
 *        Jarvis must fall back to the Android system TTS default voice.
 */
class TtsVoicePreferenceFallbackTest {

    /**
     * Mimics the DataStore read expression introduced by Fix 3:
     *   p[Keys.TTS_VOICE_USER] ?: p[Keys.TTS_VOICE_ID]
     *
     * null represents "key not present in DataStore".
     */
    private fun readVoicePref(newKey: String?, legacyKey: String?): String? = newKey ?: legacyKey

    // ── New key takes precedence ──────────────────────────────────────────────

    @Test fun `new key is returned when both keys are present`() {
        assertEquals("new-voice-json", readVoicePref("new-voice-json", "legacy-voice-json"))
    }

    @Test fun `new key is returned when only new key is present`() {
        assertEquals("new-voice-json", readVoicePref("new-voice-json", null))
    }

    // ── Legacy-key fallback ───────────────────────────────────────────────────

    @Test fun `legacy key returned when new key is absent`() {
        assertEquals("legacy-voice-json", readVoicePref(null, "legacy-voice-json"))
    }

    // ── Both absent ───────────────────────────────────────────────────────────

    @Test fun `null returned when both keys absent (system voice used)`() {
        assertNull(readVoicePref(null, null))
    }

    // ── takeIf blank guard ────────────────────────────────────────────────────

    @Test fun `blank value from DataStore is treated as no selection`() {
        // ConversationViewModel and SettingsScreen wrap the read with:
        //   ttsVoiceUserId?.takeIf { it.isNotBlank() }?.let { decode it }
        // Verify the guard works as expected for blank strings.
        val raw: String? = ""
        val guarded = raw?.takeIf { it.isNotBlank() }
        assertNull("blank pref must be treated as no selection", guarded)
    }

    @Test fun `whitespace-only value from DataStore is treated as no selection`() {
        val raw: String? = "   "
        val guarded = raw?.takeIf { it.isNotBlank() }
        assertNull("whitespace pref must be treated as no selection", guarded)
    }

    @Test fun `non-blank value passes the blank guard`() {
        val raw: String? = """{"id":"en-us-x-sfg-local","name":"en-us-x-sfg-local","locale":"en-US","gender":"male","quality":400}"""
        val guarded = raw?.takeIf { it.isNotBlank() }
        assertEquals(raw, guarded)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TtsVoice JSON serialization — what actually lives in the preference
// ─────────────────────────────────────────────────────────────────────────────

class TtsVoiceSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `TtsVoice round-trips through JSON serialization`() {
        val voice = TtsVoice(
            id     = "en-us-x-sfg-local",
            name   = "en-us-x-sfg-local",
            locale = "en-US",
            gender = "male",
            quality = 400,
        )
        val serialized   = json.encodeToString(TtsVoice.serializer(), voice)
        val deserialized = json.decodeFromString(TtsVoice.serializer(), serialized)
        assertEquals(voice, deserialized)
    }

    @Test fun `TtsVoice serialized form contains expected fields`() {
        val voice = TtsVoice(id = "my-voice-id", name = "My Voice", locale = "en-IN", gender = "male")
        val serialized = json.encodeToString(TtsVoice.serializer(), voice)
        assertTrue("id field present",     serialized.contains("my-voice-id"))
        assertTrue("name field present",   serialized.contains("My Voice"))
        assertTrue("locale field present", serialized.contains("en-IN"))
        assertTrue("gender field present", serialized.contains("male"))
    }

    @Test fun `invalid JSON for TtsVoice returns null via runCatching`() {
        val result = runCatching {
            json.decodeFromString(TtsVoice.serializer(), "not valid json at all")
        }.getOrNull()
        assertNull("invalid JSON must return null, not throw", result)
    }

    @Test fun `partial JSON missing optional quality field deserializes correctly`() {
        // quality has a default (0), so omitting it from persisted JSON must not fail.
        val partial = """{"id":"some-id","name":"Some Voice","locale":"en-US","gender":"unknown"}"""
        val result = runCatching {
            json.decodeFromString(TtsVoice.serializer(), partial)
        }.getOrNull()
        assertEquals("some-id", result?.id)
        assertEquals(0, result?.quality) // default value
    }

    @Test fun `TtsVoice with unknown extra field is ignored (ignoreUnknownKeys)`() {
        val withExtra = """{"id":"v1","name":"Voice","locale":"en-US","gender":"male","quality":200,"extra_field":"ignored"}"""
        val result = runCatching {
            json.decodeFromString(TtsVoice.serializer(), withExtra)
        }.getOrNull()
        assertEquals("v1", result?.id)
    }

    @Test fun `null ttsVoiceUserId propagates as null to TtsVoice (no voice selected)`() {
        // Simulate the decode path in ConversationViewModel.kt:
        //   val voice = s.ttsVoiceUserId
        //       ?.takeIf { it.isNotBlank() }
        //       ?.let { runCatching { Json {...}.decodeFromString(...) }.getOrNull() }
        val ttsVoiceUserId: String? = null
        val voice: TtsVoice? = ttsVoiceUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(TtsVoice.serializer(), it) }.getOrNull() }
        assertNull("null pref must yield null TtsVoice", voice)
    }

    @Test fun `valid serialized TtsVoice pref produces correct TtsVoice object`() {
        val expectedVoice = TtsVoice(id = "tts-voice-42", name = "Voice 42", locale = "en-US", gender = "male", quality = 400)
        val ttsVoiceUserId: String? = json.encodeToString(TtsVoice.serializer(), expectedVoice)
        val voice: TtsVoice? = ttsVoiceUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(TtsVoice.serializer(), it) }.getOrNull() }
        assertEquals(expectedVoice, voice)
    }
}
