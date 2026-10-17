package dk.foss.jarvis.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.FloatBuffer

/**
 * NNAPI-accelerated wake word engine.
 *
 * Loads the three openWakeWord ONNX model files directly via ONNX Runtime 1.18.0
 * and creates OrtSession instances with [OrtSession.SessionOptions.addNnapi].
 * Each model gracefully falls back to CPU if NNAPI is unavailable for that model;
 * if all three sessions fail entirely, [isReady] is false and [WakeWordService]
 * falls back to the existing library-based [WakeWordEngine].
 *
 * ┌─ Pipeline per 80 ms chunk (1280 samples @ 16 kHz) ───────────────────────┐
 * │                                                                           │
 * │  AudioRecord MIC (PCM-16 mono) → normalize to float [-1, 1]              │
 * │      → prepend 480-sample history → 1760-sample mel input                 │
 * │      → melspectrogram.onnx  [1, 1760]     → [1, 1, 8, 32]                │
 * │                                              8 mel frames × 32 bins       │
 * │      → sliding window accumulates 76 frames                               │
 * │      → embedding_model.onnx [1, 76, 32, 1] → [1, 1, 1, 96]              │
 * │                                              96-dim embedding             │
 * │      → sliding window accumulates 16 embeddings                          │
 * │      → jarvis_v1.onnx       [1, 16, 96]   → [1, 1]                       │
 * │                                              wake probability score       │
 * │      → onScore callback → WakeWordService.onScore()                       │
 * │                                                                           │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * The 480-sample history matches the original library pipeline exactly.
 * AudioProcessor.streamingMelSpectrogram() feeds rawDataBuffer.takeLast(n + 480)
 * = 1280 + 480 = 1760 samples, which produces 8 mel frames per chunk.
 * Without this overlap each chunk's mel windows lack context for the FFT windows
 * straddling the chunk boundary, producing corrupted features.
 *
 * AudioSource.MIC (= 1) matches the library's AudioRecorder.  VOICE_RECOGNITION
 * (= 7) was the previous value; on the Realme 8 that source returns silence
 * (all-zero shorts) causing a permanently flat score of ~0.001.
 *
 * Confirmed model shapes (inspected via onnxruntime Python API on the actual files):
 *   melspectrogram.onnx  input "input"    [batch, samples]     output "output"     [batch, 1, 8, 32]
 *   embedding_model.onnx input "input_1"  [batch, 76, 32, 1]   output "conv2d_19"  [batch, 1, 1, 96]
 *   jarvis_v1.onnx       input "inputs_0" [1, 16, 96]          output "Identity"   [1, 1]
 */
class NnapiWakeEngine(
    private val context: Context,
    /**
     * Called with each raw probability score (0.0..1.0) from the wake classifier.
     * Runs on [Dispatchers.Default]. Caller is responsible for thread-hopping if needed.
     */
    private val onScore: (Float) -> Unit,
    private val engineScope: CoroutineScope,
) {
    // ── Model I/O names and pipeline constants confirmed by bytecode + Python inspection ──
    private companion object {
        const val TAG = "JarvisNNAPI"

        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280      // 80 ms @ 16 kHz — matches library AudioRecorder.BUFFER_SIZE_IN_SHORTS

        // Rolling history appended before each chunk to give the mel spectrogram
        // model its required FFT context at chunk boundaries.
        // Confirmed from library bytecode: AudioProcessor.streamingMelSpectrogram()
        // calls rawDataBuffer.takeLast(accumulatedSamples + 480) where
        // accumulatedSamples = 1280, giving 1760 total samples per mel inference.
        const val MEL_HISTORY = 480

        // Total samples fed to melspectrogram.onnx per chunk: 480 history + 1280 new.
        const val MEL_INPUT_SAMPLES = MEL_HISTORY + CHUNK_SAMPLES    // = 1760

        // melspectrogram.onnx: [1, 1760] → [1, 1, 8, 32]  (8 mel frames per chunk)
        // Confirmed: 1760-sample input → 8 output frames (Python onnxruntime inspection).
        const val MEL_FRAMES_PER_CHUNK = 8  // steady-state value for 1760-sample input
        const val MEL_BINS = 32             // mel bins (confirmed from model output)
        const val MEL_IN = "input"
        const val MEL_OUT = "output"

        // embedding_model.onnx: [1, 76, 32, 1] → [1, 1, 1, 96]
        const val MEL_WINDOW = 76           // context frames for embedding
        const val EMB_DIM = 96             // embedding dimension
        const val EMB_IN = "input_1"
        const val EMB_OUT = "conv2d_19"

        // jarvis_v1.onnx: [1, 16, 96] → [1, 1]
        const val EMB_WINDOW = 16          // context embeddings for wake classifier
        const val WAKE_IN = "inputs_0"
        const val WAKE_OUT = "Identity"

        // RMS threshold (float PCM) above which a chunk is considered speech.
        // 0.02 ≈ RMS 655 shorts — comfortably within the confirmed speech range (500-1275).
        // Used only by the one-shot NNAPI vs CPU mel diagnostic.
        const val RMS_DIAG_THRESHOLD = 0.02f

        // How many full-pipeline chunks between per-model timing log lines.
        // 150 chunks × 80 ms = ~12 s.  Set to 63 (≈ 5 s) for faster initial feedback.
        const val TIMING_LOG_INTERVAL = 150
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** All three sessions initialised successfully (with or without NNAPI). */
    var isReady: Boolean = false
        private set

    /** True when addNnapi() was accepted for at least one model's session. */
    var nnApiEnabled: Boolean = false
        private set

    /**
     * True after the first successful inference pass confirms NNAPI is executing.
     * Logged at INFO for benchmark verification.
     */
    var nnApiConfirmed: Boolean = false
        private set

    private var melSess: OrtSession? = null
    private var embSess: OrtSession? = null
    private var wakeSess: OrtSession? = null

    // Sliding inference windows — accessed only from the Dispatchers.Default coroutine
    private val melWindow = ArrayDeque<FloatArray>()   // each FloatArray is float[MEL_BINS], max MEL_WINDOW entries
    private val embWindow = ArrayDeque<FloatArray>()   // each FloatArray is float[EMB_DIM],  max EMB_WINDOW entries

    /**
     * Rolling 480-sample tail of the previous chunk.
     *
     * Prepended to the current 1280-sample chunk before mel inference so that
     * FFT windows straddling the chunk boundary have proper audio context.
     * This replicates the library's rawDataBuffer.takeLast(1280 + 480) behaviour.
     *
     * Initialised to zeros (silence padding) so the very first chunk still
     * produces valid (though slightly degraded) mel features.
     * Accessed only from the Dispatchers.Default recording coroutine.
     */
    private var melHistory = FloatArray(MEL_HISTORY)   // zeros on first chunk = silence padding

    // ── Pre-allocated per-chunk reusable buffers (PHASE 3 GC optimisation) ──────
    //
    // These six FloatArrays are promoted from local variables / per-iteration
    // allocations to engine instance fields.  They are accessed exclusively from
    // the single Dispatchers.Default recording coroutine (recordingJob); no
    // concurrent access is possible because:
    //   • start() is guarded by `if (!isReady || capturing) return`
    //   • processChunk() is called synchronously from within the recording loop
    //   • stop() does not read or write any of these fields
    //   • ONNX inference is synchronous — ORT finishes with a FloatBuffer.wrap()
    //     view before the field is written again on the next chunk
    //
    // Sizes are compile-time constants; no resizing ever occurs.
    // These fields carry no native resources and are not reset in stop()/release();
    // their contents are overwritten at the start of every new chunk.
    //
    // Eliminated per-chunk allocations (≈ 28 KB / chunk, ≈ 350 KB / s):
    //   chunkBuf    replaces  FloatArray(1280)              5 120 B / chunk
    //   melInputBuf replaces  FloatArray(1760)              7 040 B / chunk
    //   melOutBuf   replaces  FloatArray(nFrames * 32)      1 024 B / chunk
    //   embFlatBuf  replaces  FloatArray(76 × 32)           9 728 B / chunk
    //   wakeFlatBuf replaces  FloatArray(16 × 96)           6 144 B / chunk
    //
    // NOTE — embVecBuf: the pre-allocated 96-float read buffer is included for
    // completeness, but each value added to embWindow must be an independent copy
    // (embWindow.addLast(embVecBuf.copyOf())), because the window holds references
    // and embVecBuf is overwritten on the next chunk.  The copyOf() replaces the
    // former `val embVec = FloatArray(EMB_DIM)` allocation one-for-one, so
    // embVecBuf provides zero net steady-state allocation savings.  The field is
    // harmless and consistent with the rest of the pool.
    private val chunkBuf    = FloatArray(CHUNK_SAMPLES)                    // 1 280 floats — normalised PCM [-1, 1]
    private val melInputBuf = FloatArray(MEL_INPUT_SAMPLES)                // 1 760 floats — [melHistory | chunk]
    private val melOutBuf   = FloatArray(MEL_FRAMES_PER_CHUNK * MEL_BINS)  //   256 floats — mel output (max 8 × 32)
    private val embFlatBuf  = FloatArray(MEL_WINDOW * MEL_BINS)            // 2 432 floats — embedding model input
    private val embVecBuf   = FloatArray(EMB_DIM)                          //    96 floats — embedding model output
    private val wakeFlatBuf = FloatArray(EMB_WINDOW * EMB_DIM)             // 1 536 floats — wake classifier input

    private var audioRecord: AudioRecord? = null
    private var recordingJob: kotlinx.coroutines.Job? = null
    @Volatile private var capturing = false

    // ── One-shot NNAPI vs CPU mel diagnostic ─────────────────────────────────
    // Created in initSessions() if melSess uses NNAPI; closed in runMelDiagnostic()
    // after the first speech chunk.  Never touches wake detection logic.
    private var melCpuSessForDiag: OrtSession? = null
    @Volatile private var melDiagDone = false

    // ── Per-model inference timing accumulators (diagnostic only) ────────────
    // Accumulate wall-clock nanoseconds for each model.run() call across
    // TIMING_LOG_INTERVAL full-pipeline chunks (all three models ran, score
    // extracted), then log averages and maxima before resetting.
    // Accessed exclusively from the single Dispatchers.Default recording
    // coroutine — same thread-safety contract as the pre-allocated buffers.
    // No synchronisation is required.
    private var timingChunksFull = 0
    private var melAccNs  = 0L;  private var melMaxNs  = 0L
    private var embAccNs  = 0L;  private var embMaxNs  = 0L
    private var wakeAccNs = 0L;  private var wakeMaxNs = 0L

    init {
        initSessions()
    }

    // ── Session initialisation ────────────────────────────────────────────────

    private fun initSessions() {
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted — NNAPI engine unavailable")
            return
        }
        try {
            val assets = context.assets
            val melBytes  = assets.open("melspectrogram.onnx").readBytes()
            val embBytes  = assets.open("embedding_model.onnx").readBytes()
            val wakeBytes = assets.open("jarvis_v1.onnx").readBytes()

            val (mel,  melNnapi)  = tryNnapiSession(melBytes,  "melspectrogram")
            val (emb,  embNnapi)  = tryNnapiSession(embBytes,  "embedding_model")
            val (wake, wakeNnapi) = tryNnapiSession(wakeBytes, "jarvis_v1")

            melSess  = mel
            embSess  = emb
            wakeSess = wake
            nnApiEnabled = melNnapi || embNnapi || wakeNnapi
            isReady = true

            // If the mel model runs under NNAPI, create a CPU-only companion session
            // that will be used exactly once to compare NNAPI vs CPU mel output on
            // the first speech chunk.  Closed inside runMelDiagnostic().
            if (melNnapi) {
                try {
                    melCpuSessForDiag = env.createSession(melBytes)
                    Log.i(TAG, "Diagnostic CPU mel session created — will compare NNAPI vs CPU on first speech chunk (rms > $RMS_DIAG_THRESHOLD)")
                } catch (e: Exception) {
                    Log.w(TAG, "Diagnostic CPU mel session creation failed (non-fatal): ${e.message}")
                }
            }

            Log.i(TAG, "NNAPI wake engine ready — " +
                "melspectrogram=${if (melNnapi)  "NNAPI" else "CPU"} " +
                "embedding_model=${if (embNnapi)  "NNAPI" else "CPU"} " +
                "jarvis_v1=${if (wakeNnapi) "NNAPI" else "CPU"}")
            if (!nnApiEnabled) {
                Log.w(TAG, "NNAPI unavailable for all models on this device — all sessions use CPU. " +
                    "WakeWordService may prefer the library engine instead.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NNAPI engine init failed — WakeWordService will use library CPU engine: ${e.message}", e)
            closeSessions()
            isReady = false
        }
    }

    /**
     * Attempt to create an [OrtSession] for [modelBytes] with NNAPI.
     * Returns (session, nnApiSucceeded). If NNAPI throws, retries with CPU.
     *
     * Both the NNAPI session and the CPU fallback session are created with
     * [OrtSession.SessionOptions.setIntraOpNumThreads] and
     * [OrtSession.SessionOptions.setInterOpNumThreads] set to 1.
     *
     * Without these limits, ORT defaults to spawning [Runtime.availableProcessors]
     * (= 8 on the MT6785) threads for intra-operator parallelism on every inference
     * call.  At 12.5 inference calls per second across three models the multi-thread
     * overhead produces the 200 %+ CPU usage observed during screen-off idle.
     * Limiting each session to one thread keeps all inference work on a single core
     * and is functionally identical: the 80 ms chunk budget is far wider than the
     * per-chunk inference time even with serial execution.
     *
     * API compatibility: [OrtSession.SessionOptions.setIntraOpNumThreads] and
     * [OrtSession.SessionOptions.setInterOpNumThreads] have been part of the public
     * OrtSession.SessionOptions API since ONNX Runtime 1.0 and are confirmed
     * available in onnxruntime-android 1.18.0.
     */
    private fun tryNnapiSession(modelBytes: ByteArray, label: String): Pair<OrtSession, Boolean> {
        try {
            val opts = OrtSession.SessionOptions()
            // addNnapi(): confirmed available in onnxruntime-android 1.18.0
            // (inspected via javap on onnxruntime-android-1.18.0.aar/classes.jar)
            opts.addNnapi()
            // Limit ORT's internal thread pool to one thread.  See kdoc above.
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)
            val sess = env.createSession(modelBytes, opts)
            Log.i(TAG, "$label: OrtSession created with NNAPI execution provider (intraOp=1, interOp=1)")
            return sess to true
        } catch (e: OrtException) {
            Log.w(TAG, "$label: NNAPI provider rejected (${e.message}) — creating CPU session instead")
        } catch (e: Exception) {
            Log.w(TAG, "$label: NNAPI session failed (${e.javaClass.simpleName}: ${e.message}) — trying CPU")
        }

        // CPU fallback — must succeed or we rethrow.
        // Thread limits applied here too: this path runs when NNAPI is unavailable
        // or rejected, so the same multi-thread constraint applies.
        val cpuOpts = OrtSession.SessionOptions()
        cpuOpts.setIntraOpNumThreads(1)
        cpuOpts.setInterOpNumThreads(1)
        val cpuSess = env.createSession(modelBytes, cpuOpts)
        Log.i(TAG, "$label: OrtSession created with CPU (NNAPI fallback, intraOp=1, interOp=1)")
        return cpuSess to false
    }

    // ── Recording lifecycle ───────────────────────────────────────────────────

    /** Start audio capture and inference loop. No-op if already running or not ready. */
    fun start() {
        if (!isReady || capturing) return
        capturing = true

        // Reset mel history to silence so the first chunk gets valid (zero-padded)
        // mel features rather than stale audio from a previous session.
        melHistory = FloatArray(MEL_HISTORY)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Use at least 4 chunks so AudioRecord doesn't overflow between reads.
        // Library uses max(minBuf, 2560); we keep a larger buffer for safety.
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * Short.SIZE_BYTES * 4)

        val ar = AudioRecord(
            // FIX: Use MIC (= 1), matching the original library (AudioRecorder.kt line 97).
            // VOICE_RECOGNITION (= 7) was the previous value; on the Realme 8 that source
            // returns all-zero shorts in this service context, causing score ≡ 0.001.
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise — NNAPI engine cannot start")
            ar.release()
            capturing = false
            return
        }

        audioRecord = ar
        ar.startRecording()

        recordingJob = engineScope.launch(Dispatchers.Default) {
            // shortBuf is kept as a coroutine-local val following the existing pattern.
            // chunkBuf is the pre-allocated instance field filled from shortBuf each iteration.
            val shortBuf = ShortArray(CHUNK_SAMPLES)
            Log.i(TAG, "NNAPI engine audio loop started " +
                "(SAMPLE_RATE=$SAMPLE_RATE, CHUNK=$CHUNK_SAMPLES, MEL_INPUT=${MEL_INPUT_SAMPLES})")
            while (capturing && isActive) {
                val read = ar.read(shortBuf, 0, CHUNK_SAMPLES)
                if (read != CHUNK_SAMPLES) continue

                // Normalise PCM-16 shorts into the pre-allocated chunkBuf rather than
                // allocating a new FloatArray(1280) each iteration (saves 5 120 B/chunk).
                for (i in 0 until CHUNK_SAMPLES) chunkBuf[i] = shortBuf[i].toFloat() / 32768.0f
                processChunk(chunkBuf)
            }
            Log.i(TAG, "NNAPI engine audio loop ended")
        }
    }

    /** Stop audio capture and clear state. Does NOT release ONNX sessions. */
    fun stop() {
        if (!capturing) return
        capturing = false
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        melWindow.clear()
        embWindow.clear()
        // Reset mel history so the next start() begins with silence padding.
        melHistory = FloatArray(MEL_HISTORY)
        Log.i(TAG, "NNAPI engine stopped — mic released")
    }

    /** Stop capture and close all ONNX sessions. Call when the service is destroyed. */
    fun release() {
        stop()
        closeSessions()
        isReady = false
        Log.i(TAG, "NNAPI engine released")
    }

    private fun closeSessions() {
        runCatching { melSess?.close() }
        runCatching { embSess?.close() }
        runCatching { wakeSess?.close() }
        runCatching { melCpuSessForDiag?.close() }
        melSess = null; embSess = null; wakeSess = null; melCpuSessForDiag = null
    }

    // ── Three-stage inference pipeline ───────────────────────────────────────

    /**
     * Process one 80ms audio chunk through the full pipeline.
     *
     * Mel input is [history(480) | chunk(1280)] = 1760 samples → 8 mel frames.
     * After inference the last 480 samples of [chunk] become [melHistory] for the
     * next call, maintaining FFT window continuity across 1280-sample boundaries.
     *
     * Pre-allocated instance fields are used for all intermediate FloatArrays
     * (see field declarations above).  OnnxTensor wrappers remain as local
     * variables — they are not pooled because ORT owns the tensor lifetime.
     *
     * Calls [onScore] exactly once per chunk (0.0 during warm-up).
     */
    private fun processChunk(samples: FloatArray) {
        val mel  = melSess  ?: return
        val emb  = embSess  ?: return
        val wake = wakeSess ?: return

        // Timing locals: set when the respective model.run() succeeds; remain 0
        // if that stage is not reached (warmup early-returns, exceptions).
        var localMelNs  = 0L
        var localEmbNs  = 0L
        var localWakeNs = 0L

        try {
            // ── Stage 1: mel spectrogram ──────────────────────────────────────
            // Build the 1760-sample input in the pre-allocated melInputBuf:
            //   [melHistory(480) | samples(1280)]
            // This matches the library's rawDataBuffer.takeLast(accumulatedSamples + 480).
            // Input  "input"  : [1, 1760]
            // Output "output" : [1, 1, nFrames, 32]  (nFrames = 8 in steady state)
            System.arraycopy(melHistory, 0, melInputBuf, 0, MEL_HISTORY)
            System.arraycopy(samples,   0, melInputBuf, MEL_HISTORY, CHUNK_SAMPLES)

            val nFrames: Int
            val melIn = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(melInputBuf),
                longArrayOf(1L, MEL_INPUT_SAMPLES.toLong()))
            val t0Mel = System.nanoTime()
            val melRes = mel.run(mapOf(MEL_IN to melIn))
            localMelNs = System.nanoTime() - t0Mel
            try {
                val t = melRes.get(0) as OnnxTensor
                val shape = t.info.shape          // [1, 1, nFrames, 32]
                nFrames = shape[2].toInt()         // 8 in steady state; fewer on first chunk
                // Read exactly nFrames * MEL_BINS floats into the pre-allocated melOutBuf.
                // Pre-allocated size is MEL_FRAMES_PER_CHUNK * MEL_BINS (= 256); any
                // elements beyond nFrames * MEL_BINS retain stale values but are never
                // read — the frame loop and normalisation loop below both use nFrames.
                t.getFloatBuffer().get(melOutBuf, 0, nFrames * MEL_BINS)
                // Row-major layout: melOutBuf[fi * MEL_BINS + bin] = frame fi, mel bin `bin`
                // Apply openWakeWord reference mel normalisation: raw_dB / 10 + 2.
                // Matches MelSpectrogram.applyMelSpecTransform() in the library (openwakeword 0.1.5).
                // Without this the embedding model receives out-of-distribution dB values and
                // produces meaningless embeddings → classifier score ≡ ~0.001.
                for (i in 0 until nFrames * MEL_BINS) melOutBuf[i] = melOutBuf[i] / 10.0f + 2.0f
            } finally {
                melRes.close()
                melIn.close()
            }

            // Save the last MEL_HISTORY samples of the current chunk as history for the
            // next processChunk() call.  This is the rolling 480-sample overlap that gives
            // the mel model its FFT window context at each 1280-sample boundary.
            System.arraycopy(samples, CHUNK_SAMPLES - MEL_HISTORY, melHistory, 0, MEL_HISTORY)

            // ── One-shot NNAPI vs CPU mel diagnostic ──────────────────────────
            // Triggered once on the first chunk whose RMS exceeds RMS_DIAG_THRESHOLD.
            // Runs a CPU-only mel session on the identical 1760-sample input and logs
            // the difference.  No effect on wake detection.
            if (!melDiagDone && melCpuSessForDiag != null) {
                var sumSq = 0.0
                for (s in samples) sumSq += s.toDouble() * s
                val rms = Math.sqrt(sumSq / CHUNK_SAMPLES).toFloat()
                if (rms > RMS_DIAG_THRESHOLD) {
                    Log.i(TAG, "Speech chunk detected for mel diagnostic (rms=%.4f) — comparing NNAPI vs CPU".format(rms))
                    runMelDiagnostic(melInputBuf, melOutBuf)
                }
            }
            // ─────────────────────────────────────────────────────────────────

            // Add new frames to the sliding mel window.
            // Each window entry must own its data independently, so a fresh
            // FloatArray(MEL_BINS) is allocated per frame here.  These 8 × 32-float
            // allocations are retained as per the Phase 3 specification.
            for (fi in 0 until nFrames) {
                val frame = FloatArray(MEL_BINS)
                System.arraycopy(melOutBuf, fi * MEL_BINS, frame, 0, MEL_BINS)
                melWindow.addLast(frame)
            }
            while (melWindow.size > MEL_WINDOW) melWindow.removeFirst()

            if (melWindow.size < MEL_WINDOW) {
                onScore(0f)  // warming up: need MEL_WINDOW frames (≈ 0.76 s at 8 frames/chunk)
                return
            }

            // ── Stage 2: embedding ────────────────────────────────────────────
            // Input  "input_1"   : [1, 76, 32, 1]
            // Output "conv2d_19" : [1, 1, 1, 96]
            // Flat layout [1, 76, 32, 1]: embFlatBuf[fi * 32 + bin] = frame fi, mel bin `bin`
            melWindow.forEachIndexed { fi, frame ->
                System.arraycopy(frame, 0, embFlatBuf, fi * MEL_BINS, MEL_BINS)
            }
            val embIn = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(embFlatBuf),
                longArrayOf(1L, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1L))
            val t0Emb = System.nanoTime()
            val embRes = emb.run(mapOf(EMB_IN to embIn))
            localEmbNs = System.nanoTime() - t0Emb
            try {
                val t = embRes.get(0) as OnnxTensor
                t.getFloatBuffer().get(embVecBuf)  // reads 96 floats into pre-allocated buffer
            } finally {
                embRes.close()
                embIn.close()
            }

            // embVecBuf is a reused instance field; each window entry must own its data
            // independently because embVecBuf is overwritten on every chunk.
            // copyOf() allocates one FloatArray(96) for the window — same cost as the
            // former `val embVec = FloatArray(EMB_DIM)` allocation it replaces.
            embWindow.addLast(embVecBuf.copyOf())
            while (embWindow.size > EMB_WINDOW) embWindow.removeFirst()

            if (embWindow.size < EMB_WINDOW) {
                onScore(0f)  // warming up: need EMB_WINDOW embeddings (≈ 1.28 s after mel ready)
                return
            }

            // ── Stage 3: wake word classifier ────────────────────────────────
            // Input  "inputs_0" : [1, 16, 96]
            // Output "Identity" : [1, 1]
            // Flat layout [1, 16, 96]: wakeFlatBuf[ei * 96 + di] = embedding ei, dim di
            embWindow.forEachIndexed { ei, vec ->
                System.arraycopy(vec, 0, wakeFlatBuf, ei * EMB_DIM, EMB_DIM)
            }
            val score: Float
            val wakeIn = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(wakeFlatBuf),
                longArrayOf(1L, EMB_WINDOW.toLong(), EMB_DIM.toLong()))
            val t0Wake = System.nanoTime()
            val wakeRes = wake.run(mapOf(WAKE_IN to wakeIn))
            localWakeNs = System.nanoTime() - t0Wake
            try {
                val t = wakeRes.get(0) as OnnxTensor
                score = t.getFloatBuffer().get()  // single float at position 0
            } finally {
                wakeRes.close()
                wakeIn.close()
            }

            // Log once when first real score arrives (confirms inference is running)
            if (!nnApiConfirmed) {
                nnApiConfirmed = true
                Log.i(TAG, "NNAPI engine: first wake score=$score (nnApiEnabled=$nnApiEnabled) — inference confirmed")
            }

            // ── Inference timing accumulation ─────────────────────────────────────
            // Placed before onScore() so the sample is recorded regardless of
            // callback behaviour.  Only reached when all three model.run() calls
            // succeeded and the score was extracted; warmup early-returns and
            // inference exceptions never reach this point.
            melAccNs  += localMelNs;  if (localMelNs  > melMaxNs)  melMaxNs  = localMelNs
            embAccNs  += localEmbNs;  if (localEmbNs  > embMaxNs)  embMaxNs  = localEmbNs
            wakeAccNs += localWakeNs; if (localWakeNs > wakeMaxNs) wakeMaxNs = localWakeNs
            timingChunksFull++
            if (timingChunksFull >= TIMING_LOG_INTERVAL) {
                val n = timingChunksFull.toLong()
                // sumModel avg = mel + emb + wake model.run() durations only.
                // Excludes tensor creation, array copies, window updates, and
                // normalisation; not the same as total per-chunk processing time.
                Log.i(TAG, "inference timing  n=$timingChunksFull  " +
                    "mel avg=%.2fms max=%.2fms  "
                        .format(melAccNs  / n / 1_000_000.0, melMaxNs  / 1_000_000.0) +
                    "emb avg=%.2fms max=%.2fms  "
                        .format(embAccNs  / n / 1_000_000.0, embMaxNs  / 1_000_000.0) +
                    "wake avg=%.2fms max=%.2fms  "
                        .format(wakeAccNs / n / 1_000_000.0, wakeMaxNs / 1_000_000.0) +
                    "sumModel avg=%.2fms"
                        .format((melAccNs + embAccNs + wakeAccNs) / n / 1_000_000.0))
                timingChunksFull = 0
                melAccNs = 0L; embAccNs = 0L; wakeAccNs = 0L
                melMaxNs = 0L; embMaxNs = 0L; wakeMaxNs = 0L
            }

            onScore(score)

        } catch (e: OrtException) {
            Log.e(TAG, "ONNX inference error (OrtException): ${e.message}")
            onScore(0f)
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            onScore(0f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * One-shot diagnostic: run the mel spectrogram model on CPU with the same
     * 1760-sample input that was just processed by the NNAPI session, then log
     * the per-output statistics and maximum absolute difference.
     *
     * Called at most once (guarded by [melDiagDone]).  Closes and nulls
     * [melCpuSessForDiag] after use so it is not held in memory indefinitely.
     *
     * Does NOT affect wake detection, thresholds, or any pipeline state.
     */
    private fun runMelDiagnostic(melInput: FloatArray, nnapiBuf: FloatArray) {
        // Mark done immediately so a second high-RMS chunk in the same 80ms window
        // cannot race into a second call before this one finishes.
        melDiagDone = true
        val cpuSess = melCpuSessForDiag ?: return
        try {
            val diagIn = OnnxTensor.createTensor(env,
                FloatBuffer.wrap(melInput),
                longArrayOf(1L, MEL_INPUT_SAMPLES.toLong()))
            val cpuRes = cpuSess.run(mapOf(MEL_IN to diagIn))
            try {
                val t = cpuRes.get(0) as OnnxTensor
                val nFramesCpu = t.info.shape[2].toInt()
                val cpuBuf = FloatArray(nFramesCpu * MEL_BINS)
                t.getFloatBuffer().get(cpuBuf)
                // Apply the same normalisation that processChunk() applies to the NNAPI output
                // before passing melOutBuf to this function (processChunk():440  value / 10.0f + 2.0f).
                // Without this the NNAPI side is in normalised space and the CPU side is raw dB,
                // producing a spurious ~33–47 divergence and a false "NNAPI mel is likely wrong" warning.
                for (i in cpuBuf.indices) cpuBuf[i] = cpuBuf[i] / 10.0f + 2.0f

                // Per-output statistics
                var nnapiMin = Float.MAX_VALUE; var nnapiMax = -Float.MAX_VALUE; var nnapiSum = 0.0
                for (v in nnapiBuf) { if (v < nnapiMin) nnapiMin = v; if (v > nnapiMax) nnapiMax = v; nnapiSum += v }
                val nnapiMean = (nnapiSum / nnapiBuf.size).toFloat()

                var cpuMin = Float.MAX_VALUE; var cpuMax = -Float.MAX_VALUE; var cpuSum = 0.0
                for (v in cpuBuf) { if (v < cpuMin) cpuMin = v; if (v > cpuMax) cpuMax = v; cpuSum += v }
                val cpuMean = (cpuSum / cpuBuf.size).toFloat()

                // Element-wise difference (compare only the overlapping region)
                val compareLen = minOf(nnapiBuf.size, cpuBuf.size)
                var maxAbsDiff = 0f; var sumAbsDiff = 0.0
                for (i in 0 until compareLen) {
                    val d = Math.abs(nnapiBuf[i] - cpuBuf[i])
                    if (d > maxAbsDiff) maxAbsDiff = d
                    sumAbsDiff += d
                }
                val meanAbsDiff = (sumAbsDiff / compareLen).toFloat()

                Log.i(TAG, "=== MEL DIAGNOSTIC: NNAPI vs CPU ===")
                Log.i(TAG, "  NNAPI mel  frames=${nnapiBuf.size / MEL_BINS}  mean=${"%.3f".format(nnapiMean)}  min=${"%.3f".format(nnapiMin)}  max=${"%.3f".format(nnapiMax)}")
                Log.i(TAG, "  CPU   mel  frames=${cpuBuf.size  / MEL_BINS}  mean=${"%.3f".format(cpuMean)}  min=${"%.3f".format(cpuMin)}  max=${"%.3f".format(cpuMax)}")
                Log.i(TAG, "  Diff       maxAbsDiff=${"%.4f".format(maxAbsDiff)}  meanAbsDiff=${"%.6f".format(meanAbsDiff)}")
                Log.i(TAG, "  Reference  normalised speech mel mean expected ~[-1.5, 0.0]  silence ≈ -8.0")

                // Both sides are in normalised mel space (raw_dB / 10 + 2), so the
                // expected means lie in ≈[-8.0, 0.0] — a total span of ≈8.0.  The
                // thresholds below are therefore expressed in normalised units, not
                // decibels: 0.5 normalised == 5 raw dB, 2.0 normalised == 20 raw dB.
                // (The previous 20f / 5f bounds were raw-dB values left over from
                // before the CPU output was normalised at line 637; 20f was
                // unreachable in a space whose full span is only ≈8.0.)
                when {
                    Math.abs(nnapiMean - cpuMean) > 2f ->
                        Log.w(TAG, "  *** LARGE NNAPI/CPU DIVERGENCE (${(nnapiMean - cpuMean).format1()} normalised units ≈ ${(nnapiMean - cpuMean).format1dB()} dB) — NNAPI mel is likely wrong ***")
                    Math.abs(nnapiMean - cpuMean) > 0.5f ->
                        Log.w(TAG, "  *** MODERATE NNAPI/CPU DIVERGENCE (${(nnapiMean - cpuMean).format1()} normalised units ≈ ${(nnapiMean - cpuMean).format1dB()} dB) — investigate further ***")
                    else ->
                        Log.i(TAG, "  NNAPI and CPU mel outputs are consistent (mean diff ${(nnapiMean - cpuMean).format1()} normalised units ≈ ${(nnapiMean - cpuMean).format1dB()} dB, threshold 0.5)")
                }
            } finally {
                cpuRes.close()
                diagIn.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mel diagnostic failed: ${e.message}", e)
        } finally {
            // Release the diagnostic session — it is never needed again.
            runCatching { melCpuSessForDiag?.close() }
            melCpuSessForDiag = null
            Log.i(TAG, "=== MEL DIAGNOSTIC complete — CPU session released ===")
        }
    }

    private fun Float.format1() = "%.1f".format(this)

    /**
     * Convert a divergence expressed in normalised mel units back to raw dB for
     * human-readable log output.  Inverse of the `value / 10.0f + 2.0f` transform:
     * the constant offset cancels in a difference, so `dB = normalised * 10`.
     */
    private fun Float.format1dB() = "%.1f".format(this * 10.0f)

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
