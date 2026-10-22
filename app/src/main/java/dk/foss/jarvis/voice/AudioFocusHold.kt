package dk.foss.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Holds transient audio focus for the duration of one voice conversation.
 *
 * Audio focus is acquired once when the first microphone turn starts and held
 * until the conversation ends ([release]).  It is deliberately NOT released
 * between turns, while JARVIS is thinking, or while it is speaking.  Holding
 * focus across the whole conversation causes music players and other audio apps
 * to pause (AUDIOFOCUS_GAIN_TRANSIENT), which
 *  - reduces acoustic interference during speech recognition, and
 *  - prevents the phone speaker from replaying TTS output into the microphone
 *    when the next recognition turn opens.
 *
 * Design principles
 * -----------------
 * - Uses [AudioFocusRequest.Builder] (API 26+, safe given minSdk = 29).
 * - [take] is idempotent: re-calling while focus is already held is a no-op.
 * - [release] is idempotent: calling when focus is not held is a no-op.
 * - A refused focus request (e.g. during an active phone call) is logged and
 *   silently ignored: the conversation proceeds without focus rather than
 *   blocking or crashing.
 * - Permanent focus loss ([AudioManager.AUDIOFOCUS_LOSS]): logged only.
 *   The [OnAudioFocusChangeListener] signature carries no grant identifier,
 *   so there is no safe way to determine from the callback which
 *   [requestAudioFocus] call's grant is being revoked.  Mutating [isHeld]
 *   or calling [AudioManager.abandonAudioFocusRequest] from the callback
 *   risks corrupting the state of a newer grant that may have succeeded
 *   between focus loss and callback delivery.  Trade-off: after permanent
 *   focus loss mid-conversation, [isHeld] remains true and same-conversation
 *   [take] calls continue to be no-ops; focus is not reacquired until the
 *   next conversation calls [take] after a preceding [release].
 * - Transient loss ([AudioManager.AUDIOFOCUS_LOSS_TRANSIENT]) and duck events
 *   are only logged; they do not affect [isHeld] because JARVIS continues the
 *   conversation regardless and focus will be regained shortly.
 *
 * Limitations
 * -----------
 * Audio focus alone does not guarantee echo cancellation or fix every
 * microphone glitch (those require AEC on the AudioRecord session and
 * synchronised wake-engine shutdown — addressed in P0.2 and P0.3).
 * This class is one layer of a broader audio-reliability improvement.
 *
 * Thread safety
 * -------------
 * [take] and [release] must be called on the main thread (matching
 * [ConversationViewModel]'s threading model).  The [AudioManager] delivers
 * focus-change events on an OS-internal thread; the focus listener only
 * calls [Log] from that thread and mutates no shared state, so no Handler,
 * posting, or additional synchronisation is required.  [isHeld]'s
 * [@Volatile] is belt-and-suspenders for any accidental cross-thread call;
 * all normal paths are main-thread only.
 *
 * Testing
 * -------
 * This class depends on [AudioManager], an Android system service.  Pure-JVM
 * unit tests are not feasible without Robolectric (not in this project).
 * Verification is via device-side smoke tests:
 *   DS-AF-1  Open Jarvis, play music first — music must pause when Jarvis
 *            starts listening and resume when the conversation ends.
 *   DS-AF-2  Repeat wake word three times consecutively — focus must be
 *            taken once (idempotent) and released once at the end.
 *   DS-AF-3  Trigger Jarvis during an active phone call — no crash;
 *            conversation proceeds without audio focus.
 */
class AudioFocusHold(context: Context) {

    private companion object {
        const val TAG = "JarvisAudioFocus"
    }

    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * True while audio focus is currently held by this instance.
     * [@Volatile] for safety; all normal call sites are on the main thread.
     */
    @Volatile var isHeld: Boolean = false
        private set

    /**
     * The single [AudioFocusRequest] object reused for the lifetime of this
     * instance.  [AudioFocusRequest] objects are safe to reuse across
     * request/abandon cycles — they are not invalidated by abandon.
     */
    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Delayed grants add async complexity; reject them so a refused request is
            // simply skipped rather than arriving unexpectedly later in the session.
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                // The OS delivers this callback on an AudioManager-internal thread.
                // All focusChange cases are log-only; isHeld is owned exclusively by
                // take() and release() on the main thread.
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS ->
                        // Permanent loss (e.g. phone call answered): logged only.
                        // The OnAudioFocusChangeListener API provides no grant identifier,
                        // so it is impossible to safely distinguish which requestAudioFocus
                        // call's grant is being revoked.  Mutating isHeld or calling
                        // abandonAudioFocusRequest here risks corrupting the state of a
                        // newer grant that may have succeeded between focus loss and
                        // callback delivery.  release() in stopAll()/onCleared() abandons
                        // correctly when the conversation ends.
                        Log.i(TAG, "Focus lost permanently (incoming call or media resumed)")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        // Transient loss: JARVIS keeps the conversation alive; focus will
                        // be regained when the competing audio ends.
                        Log.i(TAG, "Focus lost transiently — conversation continues")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        Log.d(TAG, "Focus: ducked by another app")
                    AudioManager.AUDIOFOCUS_GAIN ->
                        Log.d(TAG, "Focus regained")
                    else ->
                        Log.d(TAG, "Focus change: $focusChange")
                }
            }
            .build()

    /**
     * Acquire transient audio focus.  Idempotent: no-op if already held.
     *
     * Call once when the first listening turn begins ([ConversationViewModel.startListening]).
     * Do NOT call in [goIdle] or between turns — the conversation is still active.
     *
     * A refused request is logged and silently skipped so the conversation can
     * proceed even when an exclusive-focus app (e.g. a phone call) is active.
     */
    fun take() {
        if (isHeld) {
            Log.d(TAG, "take() called while already held — no-op")
            return
        }
        val result = audio.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            isHeld = true
            Log.d(TAG, "Audio focus acquired (GAIN_TRANSIENT)")
        } else {
            // AUDIOFOCUS_REQUEST_FAILED: the request was denied (e.g. an active phone call
            // holds exclusive focus).  AUDIOFOCUS_REQUEST_DELAYED cannot occur here because
            // setAcceptsDelayedFocusGain(false) was set when the request was built.
            // Proceed without focus — conversation continues, just without music pausing.
            Log.i(TAG, "Audio focus request not granted (result=$result) — continuing without focus")
        }
    }

    /**
     * Release audio focus.  Idempotent: no-op if not currently held.
     *
     * Call when the conversation ends ([stopAll] / [onCleared]).
     * Music and other audio apps resume as soon as this returns.
     */
    fun release() {
        if (!isHeld) {
            Log.d(TAG, "release() called while not held — no-op")
            return
        }
        audio.abandonAudioFocusRequest(request)
        isHeld = false
        Log.d(TAG, "Audio focus released")
    }
}
