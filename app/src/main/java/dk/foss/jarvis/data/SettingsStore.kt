package dk.foss.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.foss.jarvis.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import dk.foss.jarvis.voice.TtsVoice

private val Context.dataStore by preferencesDataStore(name = "jarvis_settings")

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** User configuration: how to reach the Worker, and optional premium voice. */
data class JarvisSettings(
    val workerUrl: String,
    val workerToken: String,
    val model: String,
    val elevenKey: String,
    val elevenVoiceId: String,
    val wakeEnabled: Boolean,
    // JSON TtsVoice chosen explicitly by the user inside JARVIS.  Null means "no
    // in-app choice" — AndroidTts then leaves the engine alone so the phone's
    // system Text-to-speech voice is used.
    val ttsVoiceUserId: String? = null,
    val speakerVerificationEnabled: Boolean = false,
) {
    val isConfigured: Boolean get() = workerUrl.isNotEmpty() && workerToken.isNotEmpty()
    val useElevenLabs: Boolean get() = elevenKey.isNotEmpty() && elevenVoiceId.isNotEmpty()
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val WORKER_URL = stringPreferencesKey("worker_url")
        val WORKER_TOKEN = stringPreferencesKey("worker_token")
        val MODEL = stringPreferencesKey("model")
        val ELEVEN_KEY = stringPreferencesKey("eleven_key")
        val ELEVEN_VOICE = stringPreferencesKey("eleven_voice")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
        // Legacy key: it was also written by an automatic "best male English" pick, so a
        // value there cannot be told apart from a deliberate user choice.  Only ever
        // removed now (see migrateLegacyTtsVoice), never read.
        val TTS_VOICE_USER = stringPreferencesKey("tts_voice_user")
        val SPEAKER_VERIFICATION_ENABLED = booleanPreferencesKey("speaker_verification_enabled")
    }

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        JarvisSettings(
            workerUrl = p[Keys.WORKER_URL] ?: "",
            workerToken = p[Keys.WORKER_TOKEN] ?: "",
            model = (p[Keys.MODEL] ?: "").let { if (it.isEmpty() || it == LEGACY_MODEL) DEFAULT_MODEL else it },
            elevenKey = (p[Keys.ELEVEN_KEY] ?: "").ifEmpty { BuildConfig.DEFAULT_ELEVEN_KEY },
            elevenVoiceId = (p[Keys.ELEVEN_VOICE] ?: "")
                .ifEmpty { BuildConfig.DEFAULT_ELEVEN_VOICE.ifEmpty { DEFAULT_ELEVEN_VOICE } },
            wakeEnabled = p[Keys.WAKE_ENABLED] ?: false,
            ttsVoiceUserId = p[Keys.TTS_VOICE_USER],
            speakerVerificationEnabled = p[Keys.SPEAKER_VERIFICATION_ENABLED] ?: false,
        )
    }

    suspend fun updateWake(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAKE_ENABLED] = enabled }
    }

    suspend fun updateWorkerConnection(workerUrl: String, workerToken: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.WORKER_URL] = workerUrl.trim().trimEnd('/')
            p[Keys.WORKER_TOKEN] = workerToken.trim()
            p[Keys.MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
        }
    }

    suspend fun updateVoice(elevenKey: String, elevenVoiceId: String) {
        context.dataStore.edit { p ->
            p[Keys.ELEVEN_KEY] = elevenKey.trim()
            p[Keys.ELEVEN_VOICE] = elevenVoiceId.trim().ifEmpty { DEFAULT_ELEVEN_VOICE }
        }
    }

    /**
     * Record (or clear) the voice the user explicitly picked in the JARVIS voice list.
     *
     * Only called from an explicit user action.  Writing also drops the legacy
     * `tts_voice_id` key, whose value may have come from the old automatic
     * "best male English" selection and would otherwise keep overriding the
     * phone's system Text-to-speech voice.  No other setting is touched.
     */
    suspend fun updateTtsVoice(voice: TtsVoice?) {
        context.dataStore.edit { p ->
            val value = voice?.let { json.encodeToString(TtsVoice.serializer(), it) }
            if (value != null) {
                p[Keys.TTS_VOICE_USER] = value
            } else {
                p.remove(Keys.TTS_VOICE_USER)
            }
            p.remove(Keys.TTS_VOICE_ID)
        }
    }

    /**
     * One-time cleanup of the legacy auto-populated voice preference, so it stops
     * overriding the phone's system Text-to-speech voice.  Safe to call on every
     * settings save: it is a no-op once the legacy key is gone, it never writes a
     * value, and it touches no other key.  Deliberate in-app choices live in the
     * separate `tts_voice_user` key and are left alone.
     */
    suspend fun migrateLegacyTtsVoice() {
        context.dataStore.edit { p ->
            if (p.contains(Keys.TTS_VOICE_ID)) p.remove(Keys.TTS_VOICE_ID)
        }
    }

    suspend fun updateSpeakerVerification(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.SPEAKER_VERIFICATION_ENABLED] = enabled }
    }

    companion object {
        const val DEFAULT_MODEL = "mimo-v2.5-pro-ultraspeed"
        const val LEGACY_MODEL = "kimi-for-coding"
        const val DEFAULT_ELEVEN_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    }
}