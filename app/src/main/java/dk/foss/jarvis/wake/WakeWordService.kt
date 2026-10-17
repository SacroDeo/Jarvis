package dk.foss.jarvis.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import android.provider.Settings
import dk.foss.jarvis.BuildConfig
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import dk.foss.jarvis.assist.JarvisInteractionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Callback interface for VoiceInteractionService to receive wake detections. */
interface WakeListener {
    fun onWakeDetected()
}

/**
 * Always-on "Hey Jarvis" listener. Runs wake-word detection in a microphone
 * foreground service; on detection, notifies the VoiceInteractionService (or
 * launches MainActivity in legacy mode).
 *
 * Engine selection (tried in order):
 * 1. [NnapiWakeEngine] — loads the three openWakeWord ONNX models directly
 *    via ONNX Runtime 1.18.0 with addNnapi() on each session.  Falls back
 *    to CPU per-model if NNAPI rejects a model. Falls back to engine 2 if
 *    initialisation fails entirely.
 * 2. Library [WakeWordEngine] — the original always-CPU implementation via
 *    xyz.rementia:openwakeword:0.1.5. Used when NNAPI is unavailable.
 *
 * Both engines feed raw probability scores into the same [onScore] method, so
 * all smoothing, burst-logging, thresholds, and refractory-period logic are
 * identical regardless of which engine is active.
 *
 * Two start modes:
 * - [start]: legacy — wakes MainActivity directly via startActivity / notification
 * - [startWithListener]: VIS mode — calls [WakeListener.onWakeDetected] → showSession()
 */
class WakeWordService : Service() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Library (CPU) engine — original implementation, used as fallback ─────
    private var engine: WakeWordEngine? = null

    // ── NNAPI engine — attempted first; null when unavailable or on failure ──
    private var nnapiEngine: NnapiWakeEngine? = null

    @Volatile private var cooling = false
    @Volatile private var paused = false

    // Debug: enable verbose per-frame score logging (disabled by default, enable for debugging)
    private val debugScores = BuildConfig.DEBUG

    // Detection is driven off raw per-frame scores (not the library's single-frame `detections`),
    // so we can smooth confidence and trigger on a more forgiving bar.
    // All state below is touched only from the main thread.
    private val recentScores = ArrayDeque<Float>()
    private var lastTriggerAt = 0L
    private var burstPeak = 0f
    private var inBurst = false
    private var frameCount = 0

    // Consecutive frames at or above STRONG_THRESHOLD; reset when score drops below
    // the threshold, when the engine pauses, or when a trigger fires.
    // Prevents a single brief spike from background audio from satisfying the
    // strong-frame path — requires MIN_CONSECUTIVE_STRONG frames in a row.
    private var consecutiveStrong = 0

    // Callback for VoiceInteractionService — set when started via startWithListener()
    private var wakeListener: WakeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wakeListener = pendingListener
        pendingListener = null
        createChannels()
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ONGOING,
                ongoingNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.onFailure {
            Log.e(TAG, "startForeground failed (likely background start restriction on Android 14+)", it)
            stopSelf()
            return@onCreate
        }
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle restart after process death: intent is null, flags=0.
        // On Android 14+, START_STICKY restart in background fails because
        // startForeground with microphone type requires RECORD_AUDIO which is
        // while-in-use and denied in background. Return NOT_STICKY to avoid crash loop.
        // The VoiceInteractionService (which gets background mic exemption) will
        // explicitly restart us via startWithListener() when it receives onReady().
        return START_NOT_STICKY
    }

    /**
     * Start the wake detection engine.
     *
     * Tries [NnapiWakeEngine] first. If it initialises successfully (isReady=true),
     * that engine runs and [WakeWordEngine] is never created.
     * If [NnapiWakeEngine] is not ready (NNAPI unavailable, missing permission,
     * or OrtSession failure), falls back to [WakeWordEngine] which runs entirely
     * on the library's existing CPU path — identical to the pre-NNAPI behaviour.
     */
    private fun startEngine() {
        if (engine != null || nnapiEngine != null) return

        // ── Attempt 1: NNAPI engine ───────────────────────────────────────────
        val ne = NnapiWakeEngine(
            context = this,
            // Score callback: NnapiWakeEngine runs on Dispatchers.Default;
            // post to main thread so onScore() can safely access recentScores et al.
            onScore = { score -> uiScope.launch { onScore(score) } },
            engineScope = engineScope,
        )

        if (ne.isReady) {
            nnapiEngine = ne
            ne.start()
            Log.i(TAG, "NNAPI wake engine active — " +
                "nnApiEnabled=${ne.nnApiEnabled} " +
                "(thresholds: strong=$STRONG_THRESHOLD, sustained=$SUSTAINED_THRESHOLD " +
                "over $SMOOTHING_FRAMES frames, refractory=${REFRACTORY_MS}ms)")
            return
        }

        // NNAPI engine failed to initialise — discard and use library engine
        Log.w(TAG, "NNAPI engine not ready — using library CPU engine as fallback")
        ne.release()

        // ── Attempt 2: library WakeWordEngine (CPU, original implementation) ──
        startLibraryEngine()
    }

    private fun startLibraryEngine() {
        runCatching {
            // Threshold is set high so the library's own detection (and its logging) stays
            // quiet — we make the decision ourselves from the raw `scores` flow below.
            val models = listOf(
                WakeWordModel("jarvis", "jarvis_v1.onnx", MODEL_THRESHOLD),
            )
            val e = WakeWordEngine(this, models, DetectionMode.SINGLE_BEST, COOLDOWN_MS, engineScope)
            engine = e
            uiScope.launch { e.scores.collect { onScore(it.score) } }
            e.start()
            Log.i(TAG, "Library CPU wake engine active (thresholds: strong=%.2f, sustained=%.2f over %d frames, refractory=%dms)"
                .format(STRONG_THRESHOLD, SUSTAINED_THRESHOLD, SMOOTHING_FRAMES, REFRACTORY_MS))
            if (debugScores) Log.d(TAG, "Debug score logging ENABLED")
        }.onFailure {
            Log.e(TAG, "wake engine failed to start", it)
            stopSelf()
        }
    }

    /**
     * Raw confidence for "jarvis" arrives every ~80 ms from whichever engine is active.
     * Fires on EITHER one strong frame OR a sustained moderate average.
     * A refractory period prevents re-fires.
     * Called on the main thread for both engines.
     */
    private fun onScore(score: Float) {
        frameCount++
        if (debugScores && frameCount % 10 == 0) { // log every 10th frame (~800ms) to avoid spam
            Log.d(TAG, "score=%.3f (frame=%d, paused=%b, cooling=%b)".format(score, frameCount, paused, cooling))
        }

        // Track + log the peak of each voice burst (one line per utterance, for tuning).
        if (score >= BURST_FLOOR) {
            inBurst = true
            if (score > burstPeak) burstPeak = score
        } else if (inBurst) {
            inBurst = false
            Log.i(TAG, "voice burst peak=%.2f".format(burstPeak))
            burstPeak = 0f
        }

        // Skip detection logic if paused or cooling
        if (paused || cooling) return

        // Track consecutive frames at or above STRONG_THRESHOLD.
        // Reset when score falls below the threshold so only unbroken runs count.
        if (score >= STRONG_THRESHOLD) consecutiveStrong++ else consecutiveStrong = 0

        recentScores.addLast(score)
        while (recentScores.size > SMOOTHING_FRAMES) recentScores.removeFirst()
        val sustained = recentScores.size == SMOOTHING_FRAMES &&
            recentScores.sum() / SMOOTHING_FRAMES >= SUSTAINED_THRESHOLD
        // Strong path now requires MIN_CONSECUTIVE_STRONG frames in a row at or above
        // STRONG_THRESHOLD; a single spike no longer fires by itself.
        // Sustained path is unchanged: mean ≥ SUSTAINED_THRESHOLD over SMOOTHING_FRAMES.
        if (consecutiveStrong < MIN_CONSECUTIVE_STRONG && !sustained) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < REFRACTORY_MS) return
        lastTriggerAt = now

        // Diagnostic log — fires once per accepted trigger; reads window before clearing.
        val triggerPath = when {
            consecutiveStrong >= MIN_CONSECUTIVE_STRONG && sustained ->
                "strong($consecutiveStrong)+sustained"
            consecutiveStrong >= MIN_CONSECUTIVE_STRONG ->
                "strong($consecutiveStrong)"
            else ->
                "sustained"  // consecutiveStrong < MIN_CONSECUTIVE_STRONG; sustained was true
        }
        val winMean = if (recentScores.isNotEmpty()) recentScores.sum() / recentScores.size else 0f
        Log.i(TAG, "WAKE trigger: path=$triggerPath  score=%.3f  winMean=%.3f  winSize=${recentScores.size}".format(score, winMean))

        consecutiveStrong = 0
        recentScores.clear()
        onWake(score)
    }

    private fun onWake(score: Float) {
        Log.i(TAG, "WAKE detected (score=%.2f) cooling=%s".format(score, cooling))
        if (cooling) return
        cooling = true

        // Free the mic so conversation mode's recognizer can record
        pauseEngine()

        // Three wake paths, tried in priority order:
        //
        // 1. wakeListener (preferred) — wired by JarvisInteractionService.startWithListener().
        //    Fires showSession() via the WakeListener callback.  Available when the service
        //    was started by the VIS's onReady() path.
        //
        // 2. JarvisInteractionService.triggerWakeSession() (secondary VIS path) — used when
        //    wakeListener is null but the VIS instance is still alive.  Happens when the
        //    service was started by BootReceiver in legacy mode before onReady() called
        //    startWithListener().  showSession() → startAssistantActivity() is exempt from
        //    Android background-activity-launch (BAL) restrictions.
        //
        // 3. legacyLaunch() — Jarvis is not the active VoiceInteractionService.  Falls back
        //    to a CATEGORY_CALL full-screen-intent notification which works on all screen
        //    states without needing BAL exemptions.
        when {
            wakeListener != null -> {
                Log.i(TAG, "Wake path 1: VIS wakeListener → showSession()")
                wakeListener!!.onWakeDetected()
            }
            JarvisInteractionService.triggerWakeSession() -> {
                // triggerWakeSession() already logged "showSession() dispatched" on success.
                Log.i(TAG, "Wake path 2: VIS static trigger → showSession() dispatched")
            }
            else -> {
                Log.i(TAG, "Wake path 3: legacy (VIS not active) → notifyWake")
                legacyLaunch()
            }
        }

        uiScope.launch {
            delay(4000)
            cooling = false
        }
    }

    private fun legacyLaunch() {
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = km?.isKeyguardLocked == true
        // SYSTEM_ALERT_WINDOW grants a BAL exemption on Android 10+ when the permission
        // is actually approved by the user (Settings → Apps → Jarvis → "Display over other apps").
        val canDrawOverlays = Settings.canDrawOverlays(this)

        when {
            canDrawOverlays -> {
                // Permission granted → startActivity() is BAL-exempt; direct launch works.
                Log.i(TAG, "legacyLaunch: canDrawOverlays=true → startActivity (BAL exempt)")
                runCatching { startActivity(launch) }.onFailure { e ->
                    // Unexpected: permission is granted but launch still failed (e.g. ActivityNotFoundException).
                    // Fall through to the notification so the user still sees feedback.
                    Log.w(TAG, "legacyLaunch: startActivity failed despite canDrawOverlays (${e.javaClass.simpleName}: ${e.message}) — notifyWake fallback")
                    notifyWake(launch)
                }
            }
            locked -> {
                // Screen locked → CATEGORY_CALL full-screen intent turns the screen on
                // and launches the activity via the PendingIntent.
                Log.i(TAG, "legacyLaunch: screen locked, canDrawOverlays=false → notifyWake (full-screen intent)")
                notifyWake(launch)
            }
            else -> {
                // Unlocked home screen, no SYSTEM_ALERT_WINDOW.
                // startActivity() from a background foreground-service is silently blocked
                // by Android 10+ BAL restrictions when SYSTEM_ALERT_WINDOW is not granted.
                // Send a CATEGORY_CALL notification the user can tap to open Jarvis.
                // For fully seamless (no-tap) launch:
                //   • Set Jarvis as the default assistant (recommended — uses startAssistantActivity()), or
                //   • Grant "Display over other apps" in Settings → Apps → Jarvis.
                Log.w(TAG, "legacyLaunch: unlocked home screen, canDrawOverlays=false — " +
                    "startActivity() blocked by Android BAL; sending tap-to-open notification. " +
                    "For seamless launch: set Jarvis as default assistant or grant 'Display over other apps'.")
                notifyWake(launch)
            }
        }
    }

    private fun notifyWake(launch: Intent) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 1, launch, flags)
        val notif = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jarvis")
            .setContentText("Listening…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WAKE, notif)
        uiScope.launch { delay(2000); runCatching { nm.cancel(NOTIF_WAKE) } }
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jarvis")
            .setContentText("Listening for \u201cJarvis\u201d")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Wake word", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Wake alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** Release the mic (e.g. while a conversation is using the recognizer). */
    private fun pauseEngine() {
        if (paused) return
        paused = true
        runCatching { engine?.stop() }       // library engine pause
        runCatching { nnapiEngine?.stop() }  // NNAPI engine pause
        recentScores.clear()
        consecutiveStrong = 0
        inBurst = false
        burstPeak = 0f
        Log.i(TAG, "wake paused (mic released)")
    }

    /** Resume always-on listening after a conversation finishes. */
    private fun resumeEngine() {
        if (!paused) return
        paused = false
        runCatching { engine?.start() }       // library engine resume
        runCatching { nnapiEngine?.start() }  // NNAPI engine resume
        Log.i(TAG, "wake resumed")
    }

    override fun onDestroy() {
        instance = null
        // Library engine cleanup (original)
        runCatching { engine?.stop(); engine?.release() }
        engine = null
        // NNAPI engine cleanup
        runCatching { nnapiEngine?.release() }
        nnapiEngine = null
        runCatching { engineScope.cancel() }
        runCatching { uiScope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_ONGOING = "jarvis_wake_ongoing"
        private const val CHANNEL_WAKE = "jarvis_wake_alert"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_WAKE = 1002

        // --- detection sensitivity (driven from the raw scores flow) ---
        // Model threshold kept high so the library's built-in detection/logging stays
        // dormant; we decide from `scores` below.
        private const val MODEL_THRESHOLD = 0.95f
        // Fire on a single strong frame (lowered from 0.5 — community jarvis_v1 model
        // scores differently than hey_jarvis_v0.1, peaks are more moderate)....
        private const val STRONG_THRESHOLD = 0.3f
        // ...or on a sustained moderate average over the smoothing window (lowered
        // to catch more near-misses with the new model).
        private const val SUSTAINED_THRESHOLD = 0.2f
        private const val SMOOTHING_FRAMES = 5 // ~0.4 s at 80 ms/frame (was 3)
        private const val REFRACTORY_MS = 2500L // ignore re-triggers right after a fire
        private const val BURST_FLOOR = 0.1f // score above which a "voice burst" is in progress
        private const val COOLDOWN_MS = 1500L // engine `detections` cooldown (unused now, harmless)
        // Minimum consecutive frames at or above STRONG_THRESHOLD required for the
        // strong-frame path to fire.  2 frames = 160 ms of sustained elevated score.
        // The sustained-average path (mean ≥ SUSTAINED_THRESHOLD over SMOOTHING_FRAMES)
        // is unchanged and unaffected by this counter.
        private const val MIN_CONSECUTIVE_STRONG = 2

        @Volatile private var instance: WakeWordService? = null

        /**
         * Stash for the WakeListener set by VoiceInteractionService.startWithListener() before
         * the service process has been created. startForegroundService() is asynchronous — the
         * service's onCreate() has NOT run yet at the point the caller returns from
         * startWithListener(). We therefore cannot set instance?.wakeListener directly (instance
         * is null). Instead we park the listener here, and onCreate() claims it on the main thread.
         */
        @Volatile private var pendingListener: WakeListener? = null

        /** Pause/resume the always-on listener so the mic is free during a conversation. */
        fun pauseListening() {
            instance?.pauseEngine()
        }

        fun resumeListening() {
            instance?.resumeEngine()
        }

        /** Check if the wake word engine is running and healthy. */
        fun isHealthy(): Boolean {
            val inst = instance ?: return false
            // Healthy if either engine is running and not paused
            val libOk = inst.engine != null
            val nnapiOk = inst.nnapiEngine?.isReady == true
            return (libOk || nnapiOk) && !inst.paused
        }

        /** Legacy start — launches MainActivity directly on wake (for non-assistant mode). */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        /**
         * Start with a listener — used by VoiceInteractionService. Does not launch activity
         * directly. The listener MUST be stored in pendingListener before startForegroundService
         * is called because the service component is created asynchronously and instance is null
         * until onCreate() runs. If the service is already running, wire the listener directly.
         */
        fun startWithListener(context: Context, listener: WakeListener) {
            // Store the listener BEFORE starting the service so onCreate() can claim it.
            // This is the fix for the race condition: instance is null until the async
            // startForegroundService() completes and onCreate() runs on the main thread.
            pendingListener = listener
            val current = instance
            if (current != null) {
                // Service already alive — wire the listener immediately and clear the stash.
                current.wakeListener = listener
                pendingListener = null
                return
            }
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            pendingListener = null
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
