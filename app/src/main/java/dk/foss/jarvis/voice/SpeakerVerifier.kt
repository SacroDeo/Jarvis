package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaker verification for "Hey Jarvis" wake word.
 * Separates wake-word detection (openWakeWord) from speaker authentication.
 *
 * IMPLEMENTATION NOTES:
 * - This is a framework for on-device speaker verification.
 * - For production use, a speaker embedding model (e.g., ECAPA-TDNN, x-vector)
 *   should be integrated via TensorFlow Lite or ONNX Runtime.
 * - The current implementation uses a simple energy/spectral centroid check as a
 *   PLACEHOLDER — NOT SECURE for real speaker authentication.
 *
 * To add real speaker verification:
 * 1. Obtain a TFLite/ONNX speaker embedding model (e.g., from SpeechBrain, NVIDIA NeMo)
 * 2. Add model to assets/ and load via TFLite Interpreter or ONNX Runtime
 * 3. Replace [SimpleEnergyVerifier] with a model-based implementation
 * 4. Enrollment: record 3-5 phrases, extract embeddings, average them
 * 5. Verification: extract embedding from wake utterance, cosine similarity vs enrolled
 */
interface SpeakerVerifier {
    /** Check if a speaker profile exists for the current user. */
    fun isEnrolled(): Boolean

    /** Start enrollment: record multiple phrases to build a speaker profile. */
    suspend fun enroll(listener: EnrollmentListener)

    /** Verify if the given audio matches the enrolled speaker. */
    suspend fun verify(audioFile: File): VerificationResult

    /** Delete the enrolled speaker profile. */
    suspend fun deleteProfile()

    interface EnrollmentListener {
        fun onPhraseRequested(phraseIndex: Int, totalPhrases: Int, phrase: String)
        fun onRecordingComplete(phraseIndex: Int)
        fun onEnrollmentComplete(success: Boolean, message: String)
    }

    data class VerificationResult(
        val matched: Boolean,
        val confidence: Float, // 0.0 to 1.0
        val message: String,
    )
}

/**
 * Placeholder implementation using simple audio energy check.
 * NOT SECURE — for development/testing only.
 * Replace with a proper speaker embedding model for production.
 */
class SimpleEnergyVerifier(private val context: Context) : SpeakerVerifier {

    private val TAG = "SpeakerVerifier"
    private val profileDir = File(context.filesDir, "speaker_profile")
    private val enrollmentPhrases = listOf(
        "Hey Jarvis, what's the weather today",
        "Hey Jarvis, set a timer for five minutes",
        "Hey Jarvis, play some music",
        "Hey Jarvis, turn on the lights",
        "Hey Jarvis, read my messages",
    )

    override fun isEnrolled(): Boolean {
        return profileDir.exists() && profileDir.listFiles()?.isNotEmpty() == true
    }

    override suspend fun enroll(listener: SpeakerVerifier.EnrollmentListener) = withContext(Dispatchers.IO) {
        profileDir.mkdirs()
        var successCount = 0

        for (i in enrollmentPhrases.indices) {
            listener.onPhraseRequested(i, enrollmentPhrases.size, enrollmentPhrases[i])
            val audioFile = File(profileDir, "enroll_$i.wav")
            val recorded = recordAudio(audioFile, durationMs = 3000)
            if (recorded) {
                successCount++
                listener.onRecordingComplete(i)
            } else {
                listener.onEnrollmentComplete(false, "Failed to record phrase ${i + 1}")
                return@withContext
            }
        }

        // Create a simple "profile" - just store that enrollment happened
        File(profileDir, "profile.dat").writeText("enrolled_${System.currentTimeMillis()}")
        listener.onEnrollmentComplete(true, "Enrolled $successCount phrases (placeholder)")
    }

    override suspend fun verify(audioFile: File): SpeakerVerifier.VerificationResult = withContext(Dispatchers.IO) {
        if (!isEnrolled()) {
            return@withContext SpeakerVerifier.VerificationResult(false, 0f, "No speaker profile enrolled")
        }

        // PLACEHOLDER: Simple energy check - NOT real speaker verification
        // Real implementation would:
        // 1. Load audio file
        // 2. Extract speaker embedding using a model (ECAPA-TDNN, etc.)
        // 3. Compare cosine similarity with enrolled embeddings
        // 4. Return matched=true if similarity > threshold (e.g., 0.75)

        val energy = computeEnergy(audioFile)
        val matched = energy > 500 // arbitrary threshold for placeholder
        val confidence = (energy / 5000f).coerceIn(0f, 1f)

        SpeakerVerifier.VerificationResult(
            matched = matched,
            confidence = confidence,
            message = if (matched) "Voice matched (placeholder)" else "Voice not recognized (placeholder)"
        )
    }

    override suspend fun deleteProfile(): Unit = withContext(Dispatchers.IO) {
        profileDir.listFiles()?.forEach { it.delete() }
        profileDir.delete()
    }

    private fun recordAudio(outputFile: File, durationMs: Long): Boolean {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size")
            return false
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )

        try {
            recorder.startRecording()
            val buffer = ByteArray(bufferSize)
            val fos = FileOutputStream(outputFile)
            // Write WAV header (placeholder, will be fixed after recording)
            fos.write(ByteArray(44))

            val startTime = System.currentTimeMillis()
            var totalBytes = 0
            while (System.currentTimeMillis() - startTime < durationMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    totalBytes += read
                }
            }

            // Write proper WAV header
            fos.channel.position(0)
            writeWavHeader(fos, totalBytes, sampleRate)
            fos.close()
            recorder.stop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            recorder.stop()
            return false
        } finally {
            recorder.release()
        }
    }

    private fun computeEnergy(audioFile: File): Float {
        try {
            val fis = FileInputStream(audioFile)
            fis.channel.position(44) // Skip WAV header
            val buffer = ByteArray(1024)
            var sum = 0L
            var count = 0
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                for (i in 0 until read step 2) {
                    if (i + 1 < read) {
                        val sample = buffer[i].toInt() or (buffer[i + 1].toInt() shl 8)
                        sum += (sample * sample.toLong())
                        count++
                    }
                }
            }
            fis.close()
            return if (count > 0) kotlin.math.sqrt(sum.toDouble() / count).toFloat() else 0f
        } catch (e: Exception) {
            Log.e(TAG, "Energy computation failed", e)
            return 0f
        }
    }

    private fun writeWavHeader(fos: FileOutputStream, dataSize: Int, sampleRate: Int) {
        val byteRate = sampleRate * 2 // 16-bit mono
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(dataSize + 36)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // PCM format chunk size
        buffer.putShort(1) // PCM
        buffer.putShort(1) // Mono
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2) // Block align
        buffer.putShort(16) // Bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        fos.write(buffer.array())
    }
}