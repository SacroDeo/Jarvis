package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dk.foss.jarvis.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale

/** Speech output. Implementations: [AndroidTts] (free, on-device) and [ElevenLabsTts]. */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit)
    fun stop()
    fun shutdown()
}

/** Platform-agnostic TTS voice representation. */
@Serializable
data class TtsVoice(
    val id: String,
    val name: String,
    val locale: String,
    val gender: String, // "male", "female", or "unknown"
    val quality: Int = 0, // TextToSpeech.QUALITY_HIGH = 400, QUALITY_NORMAL = 200
)

/** Android's built-in TextToSpeech. Free, offline, available everywhere. */
class AndroidTts(
    context: Context,
    private val languageTag: String? = null,
    private val voice: TtsVoice? = null,
) : TtsEngine {

    private var ready = false
    private var initFailed = false
    private var pending: Triple<String, () -> Unit, (String) -> Unit>? = null
    private var selectedVoice: Voice? = null

    // Holds the requested voice until TTS finishes initializing (init is async).
    // The init callback checks this field and applies it. External callers can
    // also call applyVoiceIfNeeded() once they believe TTS is ready.
    private var voiceToApply: TtsVoice? = voice

    private val tts: TextToSpeech = createTts(context)

    private fun createTts(context: Context): TextToSpeech {
        return TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            initFailed = !ready
            if (ready) {
                // ── Apply the saved voice if one was passed at construction time.
                // This MUST happen inside the init callback because TTS is async:
                // when the caller calls applyVoiceIfNeeded() right after construction,
                // ready==false so nothing happens there. The init callback is the only
                // reliable place to apply the voice.
                val toApply = voiceToApply
                if (toApply != null) {
                    selectedVoice = findSystemVoice(toApply)
                    selectedVoice?.let { runCatching { tts.setVoice(it) } }
                    voiceToApply = null
                } else if (selectedVoice == null && !languageTag.isNullOrEmpty()) {
                    // No specific voice — fall back to language tag.
                    runCatching { tts.language = Locale.forLanguageTag(languageTag) }
                }
            }
            val p = pending; pending = null
            if (p != null) {
                if (ready) speak(p.first, p.second, p.third)
                else p.third("TTS unavailable")
            }
        }
    }

    /**
     * Apply the selected voice if TTS is already ready (e.g. after a short warm-up
     * delay in test code). Has no effect when ready==false because the init callback
     * will pick up [voiceToApply] when initialization completes.
     */
    fun applyVoiceIfNeeded() {
        if (ready && voiceToApply != null) {
            val toApply = voiceToApply!!
            selectedVoice = findSystemVoice(toApply)
            selectedVoice?.let { runCatching { tts.setVoice(it) } }
            voiceToApply = null
        }
    }

    private fun findSystemVoice(v: TtsVoice): Voice? {
        return try {
            // Match by id (= original system voice name) first; fall back to display name.
            tts.voices?.firstOrNull { it.name == v.id || it.name == v.name }
        } catch (e: Exception) {
            null
        }
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (initFailed) { onError("TTS unavailable"); return }
        if (!ready) { pending = Triple(text, onDone, onError); return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("deprecated") override fun onError(utteranceId: String?) = onError("TTS error")
            override fun onError(utteranceId: String?, errorCode: Int) = onError("TTS error $errorCode")
        })
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        if (res == TextToSpeech.ERROR) onError("TTS failed to start")
    }

    override fun stop() { runCatching { tts.stop() } }
    override fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }

    companion object {
        /**
         * Enumerate all available system TTS voices, normalized to [TtsVoice].
         *
         * Gender detection uses two strategies:
         * 1. Voice name substring match ("male"/"female") — works for some engines.
         * 2. Voice features set — Google TTS and others expose "male"/"female" as feature strings.
         * Voices that don't match either strategy get gender = "unknown".
         */
        fun getAvailableVoices(context: Context): List<TtsVoice> {
            val tempTts = TextToSpeech(context.applicationContext) { }
            return try {
                Thread.sleep(100)
                tempTts.voices.map { v ->
                    val nameLower = v.name.lowercase()
                    val features = try { v.features ?: emptySet() } catch (_: Exception) { emptySet() }
                    val featuresLower = features.map { it.lowercase() }.toSet()
                    val gender = when {
                        // Explicit "female" wins — check name and features
                        nameLower.contains("female") || featuresLower.any { it.contains("female") } -> "female"
                        // Explicit "male" (but NOT "female") — name and features
                        nameLower.contains("male") || featuresLower.any { it.contains("male") } -> "male"
                        else -> "unknown"
                    }
                    TtsVoice(
                        id = v.name,
                        name = v.name,
                        locale = v.locale.toLanguageTag(),
                        gender = gender,
                        quality = v.quality,
                    )
                }.toList()
            } catch (e: Exception) {
                emptyList()
            } finally {
                tempTts.shutdown()
            }
        }

        /**
         * Select the best male English voice from [voices], with a locale preference of
         * en-IN > en-US > en-GB > any English, and quality as a tiebreaker within each locale.
         *
         * Falls back to non-female (unknown-gender) English voices when no explicitly-male voice
         * is available, so the device default male (if any) is preferred over an explicit female.
         * Only uses an explicit female voice as a last resort if it's the only English option.
         */
        fun selectBestMaleEnglishVoice(voices: List<TtsVoice>): TtsVoice? {
            val englishVoices = voices.filter { it.locale.startsWith("en") }
            val maleVoices = englishVoices.filter { it.gender == "male" }
            val nonFemaleVoices = englishVoices.filter { it.gender != "female" }

            fun bestInPool(pool: List<TtsVoice>): TtsVoice? {
                if (pool.isEmpty()) return null
                // Local (on-device) voices preferred over network voices for reliability
                val local = pool.filter { it.id.contains("local", ignoreCase = true) }
                val candidates = local.ifEmpty { pool }
                return when {
                    candidates.any { it.locale.startsWith("en-IN") } ->
                        candidates.filter { it.locale.startsWith("en-IN") }.maxByOrNull { it.quality }
                    candidates.any { it.locale.startsWith("en-US") } ->
                        candidates.filter { it.locale.startsWith("en-US") }.maxByOrNull { it.quality }
                    candidates.any { it.locale.startsWith("en-GB") } ->
                        candidates.filter { it.locale.startsWith("en-GB") }.maxByOrNull { it.quality }
                    else -> candidates.maxByOrNull { it.quality }
                }
            }

            // Priority:
            // 1. Explicitly-male English voices
            // 2. Non-female (unknown-gender) English voices — avoids explicit female
            // 3. Any English voice (absolute fallback)
            return bestInPool(maleVoices)
                ?: bestInPool(nonFemaleVoices)
                ?: bestInPool(englishVoices)
        }
    }
}

/**
 * ElevenLabs streaming TTS. Downloads the synthesized mp3 then plays it via
 * MediaPlayer. Used only when the user has set an ElevenLabs key.
 */
class ElevenLabsTts(
    context: Context,
    private val apiKey: String,
    private val voiceId: String,
    // Low-latency model for real-time assistant replies (multilingual, high quality).
    private val modelId: String = "eleven_turbo_v2_5",
) : TtsEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class TtsRequest(val text: String, val model_id: String)

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var cancelled = false

    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        cancelled = false
        Thread {
            try {
                val body = json.encodeToString(TtsRequest.serializer(), TtsRequest(text, modelId))
                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=mp3_44100_128&optimize_streaming_latency=3")
                    .addHeader("xi-api-key", apiKey)
                    .addHeader("Accept", "audio/mpeg")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                Http.base.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onError("ElevenLabs HTTP ${resp.code}") }
                        return@Thread
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (cancelled) return@Thread
                    val file = File(appContext.cacheDir, "tts_${nextId()}.mp3")
                    file.writeBytes(bytes)
                    main.post { playFile(file, onDone, onError) }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "ElevenLabs error") }
            }
        }.start()
    }

    private fun playFile(file: File, onDone: () -> Unit, onError: (String) -> Unit) {
        if (cancelled) { runCatching { file.delete() }; onDone(); return }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { runCatching { file.delete() }; onDone() }
            mp.setOnErrorListener { _, what, _ -> onError("Playback error $what"); true }
            mp.setOnPreparedListener { if (!cancelled) it.start() else onDone() }
            mp.prepareAsync() // non-blocking; avoids blocking the main thread per sentence
        } catch (e: Exception) {
            runCatching { mp.release() } // don't leak the native player on setup failure
            player = null
            runCatching { file.delete() }
            onError(e.message ?: "Playback failed")
        }
    }

    override fun stop() {
        cancelled = true
        runCatching { player?.stop() }
        runCatching { player?.release() } // separate so a throwing stop() can't skip release
        player = null
    }

    override fun shutdown() = stop()

    private fun nextId(): Long = counter.incrementAndGet()

    private companion object {
        val counter = java.util.concurrent.atomic.AtomicLong(0)
    }
}
