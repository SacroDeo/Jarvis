package dk.foss.jarvis.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.contacts.CallFlowPolicy
import dk.foss.jarvis.contacts.ContactCandidate
import dk.foss.jarvis.contacts.ContactResolver
import dk.foss.jarvis.contacts.LookupResult
import dk.foss.jarvis.contacts.PhoneNumber
import dk.foss.jarvis.contacts.ToolRegistry
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.ElevenLabsTts
import dk.foss.jarvis.voice.PrivacyFilter
import dk.foss.jarvis.voice.RegexPrivacyFilter
import dk.foss.jarvis.voice.ScribeRecognizer
import dk.foss.jarvis.voice.SimpleEnergyVerifier
import dk.foss.jarvis.voice.SpeakerVerifier
import dk.foss.jarvis.voice.SpeechInput
import dk.foss.jarvis.voice.TtsEngine
import dk.foss.jarvis.voice.TtsVoice
import dk.foss.jarvis.voice.VoiceRecognizer
import dk.foss.jarvis.wake.WakeWordService
import dk.foss.jarvis.worker.ChatMessage
import dk.foss.jarvis.worker.JsonSchemaResponseFormat
import dk.foss.jarvis.worker.JsonSchemaSpec
import dk.foss.jarvis.worker.WorkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class ConvState { Idle, Listening, Thinking, Speaking }

/**
 * Stages of the voice-only call flow. [None] means no call flow is active.
 * All transitions away from an active stage MUST go through [cancelCallFlow].
 */
private sealed class CallStage {
    object None : CallStage()

    /** READ_CONTACTS not yet granted; [token] matches [contactsPermissionRequest]. */
    data class PermissionWait(val token: Int, val query: String, val ack: String) : CallStage()

    /** Several contacts matched — awaiting spoken ordinal. */
    data class ClarifyContacts(val query: String, val candidates: List<ContactCandidate>) : CallStage()

    /** One contact, several numbers — awaiting spoken ordinal. */
    data class ClarifyNumbers(val contact: ContactCandidate) : CallStage()

    /** Local round-trip re-ask (no matches / ambiguous) — tool disabled for this turn. */
    object RoundTrip : CallStage()

    /**
     * Contact lookup is running on [Dispatchers.IO] inside [resolveAndRouteCall].
     * [onComplete] must not call [completeStructuredTurn] or advance the turn while
     * this stage is set; the coroutine will transition to the next stage (or cancel
     * via the token/turn guards) when the ContentProvider query returns.
     */
    object ToolLookupInProgress : CallStage()
}

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: VoiceRecognizer? = null
    private var speakerVerifier: SpeakerVerifier? = null
    private val privacyFilter = RegexPrivacyFilter()

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")
    val reply = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val hint = mutableStateOf<String?>(null)
    val working = mutableStateOf(false)
    val stalled = mutableStateOf(false)

    // ── call-flow state ────────────────────────────────────────────────────────
    // Nonce > 0 signals ConversationScreen to launch the READ_CONTACTS prompt once.
    val contactsPermissionRequest = mutableStateOf(0)
    private var callStage: CallStage = CallStage.None
    private var callFlowToken = 0
    private val callFlowTimeout = Runnable {
        if (callStage is CallStage.ClarifyContacts || callStage is CallStage.ClarifyNumbers) {
            cancelCallFlow()
            hint.value = "No answer — cancelled."
            goIdle()
        }
    }
    private val permissionWaitTimeout = Runnable {
        if (callStage is CallStage.PermissionWait) {
            cancelCallFlow()
            hint.value = "No answer — cancelled."
            goIdle()
        }
    }

    // --- follow-along reply display state ---
    val segments = androidx.compose.runtime.mutableStateListOf<String>()
    val speakingIndex = mutableStateOf(-1)
    val pendingText = mutableStateOf("")
    private var spokenCount = 0

    /**
     * Set to true by [handleExitCommand] when the user says an exit phrase.
     * Observed by ConversationScreen, which calls onExit() and then [consumeExitRequest].
     */
    val exitRequested = mutableStateOf(false)

    /** Called by ConversationScreen after it has acted on [exitRequested]. */
    fun consumeExitRequest() { exitRequested.value = false }

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private var androidFallback: AndroidTts? = null
    private var cancelRunnable: Runnable? = null
    private var continuous = true

    // --- streaming-TTS pipeline ---
    private val sentenceBuffer = StringBuilder()
    private val ttsQueue = ArrayDeque<String>()
    private var speaking = false
    private var streamDone = false
    private var turn = 0 // bumped each turn; stale async callbacks check this and bail
    private var retriedThisTurn = false
    // True when the current structured response was a plain reply (persist it);
    // false for tool calls (their prompts are UI-only and must not enter history).
    private var lastOutcomeWasReply = true

    // While idle in a conversation we re-arm "Hey Jarvis" so the user can re-activate
    // by voice. Loop-breaker: if wake-triggered turns keep coming back empty, stop
    // re-arming and require a tap (real speech / a tap resets the counter).
    private var currentTurnFromWake = false
    private var emptyWakeTurns = 0
    private val rearmWake = Runnable { WakeWordService.resumeListening() }

    // Speak a complete sentence that's been sitting in the buffer once the stream
    // goes quiet (e.g. the agent paused to run a tool), not only when more text arrives.
    private val idleFlush = Runnable { flushPendingSentence() }

    // If content pauses while the stream is still open, the agent is likely running a tool.
    private val stallIndicator = Runnable { if (working.value) stalled.value = true }

    // ── barge-in (interrupt Jarvis while it speaks) ──────────────────────────────
    // Re-arms the interrupt recogniser after the current session ends or errors.
    // It is a no-op unless the VM is still Speaking, so it can never outlive a turn.
    private val bargeInRearm = Runnable { watchForInterrupt() }

    // Re-arms taken during the current reply.  Reset per turn; capped so that a
    // recogniser which can never start (permanent mic contention) degrades to "no
    // barge-in for this reply" instead of a tight start/error loop burning battery.
    private var bargeInRearmsThisTurn = 0

    // ── automatic exit after silence ────────────────────────────────────────────
    private val TAG = "JarvisConv"

    /**
     * Fired when the turn is over and no user speech has arrived within
     * [CONVERSATION_SILENCE_TIMEOUT_MS].
     *
     * Routes through [handleExitCommand] so automatic exit, saying "goodbye", and the
     * close button all share one teardown path:
     * stopAll() → exitRequested → ConversationScreen.onExit() → moveTaskToBack(true).
     * stopAll() releases the recognizer and calls WakeWordService.resumeListening(),
     * so the app is never force-closed and wake-word detection stays available.
     *
     * Only ever armed from [goIdle] and [handleSilenceCommand], both of which run
     * strictly after JARVIS has stopped speaking and no request is in flight — so this
     * can never fire during Listening, Thinking, or Speaking.
     */
    private val silenceExit = Runnable {
        cancelSilenceExit()
        Log.i(TAG, "auto-exit — no speech for ${CONVERSATION_SILENCE_TIMEOUT_MS}ms")
        handleExitCommand()
    }

    /**
     * Recover-only watchdog for a recognizer that never delivers either a result or an
     * error (which would otherwise leave the conversation stuck in Listening forever,
     * because [goIdle] — and therefore the silence timer — is never reached).
     *
     * Re-armed by every speech signal ([VoiceRecognizer.Listener.onSpeechStart] and
     * [VoiceRecognizer.Listener.onPartial]), so a user speaking continuously keeps
     * resetting it and is never cut off. It only fires when the recognizer is genuinely
     * silent for the full window.
     */
    private val listeningWatchdog = Runnable {
        if (state.value != ConvState.Listening) return@Runnable
        cancelListeningWatchdog()
        Log.w(TAG, "no recognizer result or error for ${MAX_LISTENING_MS}ms while Listening — forcing idle")
        goIdle()
    }

    init {
        viewModelScope.launch { ensureReady() }
    }

    private suspend fun ensureReady() {
        val s = settings ?: settingsStore.settings.first().also { settings = it }
        if (tts == null) {
            tts = if (s.useElevenLabs) {
                ElevenLabsTts(getApplication(), s.elevenKey, s.elevenVoiceId)
            } else {
                // Only an explicit in-app choice is honoured.  A missing, blank, or corrupted
                // pref degrades to the engine's system-configured voice — never throw
                // and kill ensureReady().
                val voice = s.ttsVoiceUserId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(TtsVoice.serializer(), it) }.getOrNull() }
                val androidTts = AndroidTts(getApplication(), languageTag = null, voice = voice)
                // applyVoiceIfNeeded() handles the case where TTS initialised synchronously
                // (rare). The real work is done inside the TTS init callback, which picks up
                // the voice even when AndroidTts isn't ready yet at this call site.
                androidTts.applyVoiceIfNeeded()
                androidTts
            }
        }
        if (recognizer == null) {
            // With an ElevenLabs key, use Scribe (far better accuracy); else on-device.
            recognizer = if (s.useElevenLabs) {
                ScribeRecognizer(getApplication(), s.elevenKey, languageCode = null)
            } else {
                SpeechInput(getApplication())
            }
            recognizer?.prewarm()
        }
        if (speakerVerifier == null && s.speakerVerificationEnabled) {
            speakerVerifier = SimpleEnergyVerifier(getApplication())
        }
    }

    fun startListening(fromWake: Boolean = false) {
        // A conversation auto-continues: after Jarvis speaks it listens again.
        continuous = true
        retriedThisTurn = false
        currentTurnFromWake = fromWake
        // A new turn is starting — either from a wake event or a tap — so any pending
        // auto-exit must be cancelled, otherwise saying "Hey Jarvis" during the silence
        // window could immediately exit the conversation it just opened.
        cancelSilenceExit()
        armListeningWatchdog()
        // Free the mic from the always-on wake listener so STT can record, and cancel
        // any pending re-arm so the wake engine doesn't grab the mic mid-capture.
        main.removeCallbacks(rearmWake)
        WakeWordService.pauseListening()
        viewModelScope.launch {
            ensureReady()
            if (settings?.isConfigured != true) {
                error.value = "Configure Worker in Settings first."
                state.value = ConvState.Idle
                return@launch
            }
            // If this turn was triggered by wake word and speaker verification is enabled,
            // run speaker verification on the wake utterance before starting recognition.
            if (fromWake && settings?.speakerVerificationEnabled == true && speakerVerifier != null) {
                state.value = ConvState.Listening
                hint.value = "Verifying speaker…"
                val verifier = speakerVerifier!!
                // The wake word utterance was already captured by WakeWordService.
                // For now, we'll skip to recognition since we don't have access to the wake audio.
                // TODO: Capture wake audio in WakeWordService and pass it here for verification.
                // Placeholder: assume verification passes for now.
                hint.value = null
            }
            beginTurn()
            state.value = ConvState.Listening
            startRecognition()
        }
    }

    /** Start a fresh turn: invalidate in-flight callbacks and clear pipeline state. */
    private fun beginTurn() {
        turn++
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        main.removeCallbacks(bargeInRearm)
        bargeInRearmsThisTurn = 0
        working.value = false
        stalled.value = false
        // Stop any in-flight recognition so the next start isn't blocked by
        // AudioCapture's "if (active) return" guard (which silently drops it).
        runCatching { recognizer?.stop() }
        cancelRunnable?.run()
        cancelRunnable = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        lastOutcomeWasReply = true
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
    }

    /**
     * Reset the on-screen display when the voice screen is (re)opened, so it reflects
     * the current conversation rather than a stale previous exchange (the VM is retained
     * across screen visits while the shared conversation may have been replaced).
     */
    fun resetView() {
        turn++ // invalidate any in-flight callbacks from a prior screen visit
        cancelCallFlow()
        main.removeCallbacks(rearmWake)
        main.removeCallbacks(bargeInRearm)
        cancelSilenceExit()
        cancelListeningWatchdog()
        emptyWakeTurns = 0
        currentTurnFromWake = false
        runCatching { recognizer?.stop() }
        cancelRunnable?.run()
        cancelRunnable = null
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
        working.value = false
        stalled.value = false
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        lastOutcomeWasReply = true
        state.value = ConvState.Idle
    }

    private fun startRecognition() {
        val myTurn = turn
        recognizer?.start(languageTag = null, listener = object : VoiceRecognizer.Listener {
            override fun onSpeechStart() = onMain {
                // The user has begun speaking. Refresh the stuck-recognizer watchdog so
                // long utterances are never cut off, and cancel any pending auto-exit.
                cancelSilenceExit()
                armListeningWatchdog()
            }

            override fun onPartial(text: String) = onMain {
                // Live speech is arriving (SpeechInput only) — same as onSpeechStart.
                cancelSilenceExit()
                armListeningWatchdog()
                if (turn == myTurn) transcript.value = text
            }

            override fun onEnd() = onMain {
                // Recording finished — show "Thinking" while Scribe transcribes.
                if (turn == myTurn && state.value == ConvState.Listening) state.value = ConvState.Thinking
            }

            override fun onFinal(text: String) = onMain {
                if (turn != myTurn) return@onMain
                cancelListeningWatchdog()
                transcript.value = text
                when {
                    text.isBlank() -> {
                        // Blank during a pending call stage: cancel and idle, never dial.
                        if (callStage !is CallStage.None) cancelCallFlow()
                        goIdle()
                    }
                    isSilenceCommand(text) -> {
                        cancelCallFlow()
                        handleSilenceCommand()
                    }
                    isExitCommand(text) -> {
                        cancelCallFlow()
                        handleExitCommand()
                    }
                    else -> handleCallStageAnswer(text)
                }
            }

            override fun onError(message: String, transient: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                cancelListeningWatchdog()
                // Cold-start / mic-handoff hiccup right after a wake — retry once.
                if (transient && !retriedThisTurn) {
                    retriedThisTurn = true
                    main.postDelayed({ if (turn == myTurn) startRecognition() }, 450)
                    return@onMain
                }
                // Don't show a hint on wake-triggered turns when no speech was heard.
                // The user didn't consciously start a turn, so "Didn't catch that /
                // Tap the mic to talk" is confusing noise.  goIdle() re-arms the wake
                // listener so the user can simply say the wake phrase again.
                // On tap-initiated turns, show the hint so the user knows the mic was
                // active but nothing was recognised.
                if (!currentTurnFromWake) hint.value = message
                goIdle()
            }
        })
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Local voice command handling — checked BEFORE sending to the LLM
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * True when [text] is a standalone "stop speaking" command.
     *
     * Only matches when the ENTIRE utterance is (or closely resembles) a stop command —
     * not when "stop" appears inside a normal sentence.  The check is
     * case-insensitive and trims common trailing punctuation.
     */
    private fun isSilenceCommand(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('.', '!', '?', ',', ' ')
        return t in SILENCE_COMMANDS
    }

    /**
     * True when [text] is a standalone "exit the assistant" command.
     *
     * Intentionally conservative: only exact matches after punctuation-trim to avoid
     * false positives when the user says sentences that happen to contain these words.
     */
    private fun isExitCommand(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('.', '!', '?', ',', ' ')
        return t in EXIT_COMMANDS
    }

    /**
     * Handle "shut up / stop / be quiet / stop talking".
     *
     * Immediately stops TTS and clears all queued speech.  Does NOT send anything to
     * the LLM.  Returns to Idle so the user can re-engage with a tap or another wake.
     *
     * Architecture note: the user initiates this flow by tapping the mic button while
     * Jarvis is speaking — that tap calls onMicTap() → startListening() → beginTurn()
     * which already stops the TTS engine.  The "shut up" utterance is then recognized
     * during the brief listening window that follows, and this handler prevents the VM
     * from starting a new LLM request.  The net effect is: tap → TTS stops → say
     * "shut up" → Jarvis stays silent.
     */
    private fun handleSilenceCommand() {
        // beginTurn() bumps turn (invalidating any remaining in-flight callbacks),
        // stops the TTS engine, and clears all speech queues and display state.
        beginTurn()
        state.value = ConvState.Idle
        hint.value = null
        // Re-arm the wake word so the user can say "Hey Jarvis" to re-engage.
        main.postDelayed(rearmWake, WAKE_REARM_DELAY_MS)
        // Also start the silence countdown: "shut up" leaves the conversation idle, so
        // it must auto-exit too rather than wait forever for a tap.
        armSilenceExit()
    }

    /**
     * Handle "exit / close / goodbye / stop listening / end conversation".
     *
     * Performs a full teardown (same as the user swiping the screen away) and signals
     * ConversationScreen via [exitRequested] to call its onExit() callback.
     */
    private fun handleExitCommand() {
        stopAll()
        exitRequested.value = true
    }

    /**
     * Dispatch a recognised answer while a call stage (or normal conversation) is
     * active. Clarification answers are matched locally; anything unclear cancels the
     * call flow and falls back to a normal LLM turn.
     * Exact unique matches are dialled immediately without a yes/no confirmation step.
     */
    private fun handleCallStageAnswer(text: String) {
        when (val stage = callStage) {
            is CallStage.ClarifyContacts -> {
                val idx = CallFlowPolicy.parseOrdinal(text)
                if (idx != null && idx in 1..stage.candidates.size) {
                    val contact = stage.candidates[idx - 1]
                    cancelCallFlow()
                    when {
                        contact.numbers.isEmpty() -> {
                            enqueueSpeech("${contact.displayName} has no phone number saved.")
                            streamDone = true
                            pump()
                        }
                        contact.numbers.size == 1 -> dial(contact, contact.numbers[0])
                        else -> {
                            callStage = CallStage.ClarifyNumbers(contact)
                            val prompt = CallFlowPolicy.clarifyNumbersPrompt(
                                contact.displayName,
                                contact.numbers.map { it.label },
                            )
                            reply.value = prompt
                            speakAndListenForStage(prompt)
                        }
                    }
                } else if (CallFlowPolicy.isDenial(text)) {
                    cancelCallFlow()
                    enqueueSpeech("Okay, cancelled.")
                    streamDone = true
                    pump()
                } else {
                    // Not a recognised ordinal — treat as a fresh request.
                    cancelCallFlow()
                    think(text)
                }
            }
            is CallStage.ClarifyNumbers -> {
                val idx = CallFlowPolicy.parseOrdinal(text)
                if (idx != null && idx in 1..stage.contact.numbers.size) {
                    val number = stage.contact.numbers[idx - 1]
                    cancelCallFlow()
                    dial(stage.contact, number)
                } else if (CallFlowPolicy.isDenial(text)) {
                    cancelCallFlow()
                    enqueueSpeech("Okay, cancelled.")
                    streamDone = true
                    pump()
                } else {
                    cancelCallFlow()
                    think(text)
                }
            }
            is CallStage.ToolLookupInProgress,
            is CallStage.PermissionWait,
            is CallStage.RoundTrip -> {
                // These stages own the mic separately; if speech somehow arrives here,
                // cancel and re-route as a fresh request.
                cancelCallFlow()
                think(text)
            }
            is CallStage.None -> think(text)
        }
    }

    /** Launch the system dialer via ACTION_DIAL — never places the call directly. */
    private fun dial(contact: ContactCandidate, number: PhoneNumber) {
        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            enqueueSpeech("I couldn't open the phone app.")
            streamDone = true
            pump()
            return
        }
        // Add a short, generic assistant acknowledgement so history is not left with a
        // dangling user request and no assistant response. Neither the contact name nor
        // any phone number digits are included — those stay on-device only.
        repo.addMessage("assistant", "Okay, opening the phone app.")
        viewModelScope.launch { repo.persist() }
        goIdle()
    }

    // ──────────────────────────────────────────────────────────────────────────────

    private fun think(userText: String) {
        val myTurn = turn
        emptyWakeTurns = 0 // got real speech — reset the wake loop-breaker
        // A new model request always supersedes any pending call flow.
        cancelCallFlow()
        // No longer listening: the stuck-recognizer watchdog no longer applies.
        cancelListeningWatchdog()
        hint.value = null
        // Pipeline state was already cleared by beginTurn(); only the stream-status
        // flags are new for this thinking phase.
        state.value = ConvState.Thinking
        working.value = true
        main.postDelayed(stallIndicator, STALL_MS)
        // Apply privacy filter before sending to Worker
        val filterResult = privacyFilter.filter(userText)
        val sanitizedText = filterResult.sanitizedText
        if (filterResult.detectedTypes.isNotEmpty()) {
            hint.value = "Filtered: ${filterResult.detectedTypes.joinToString(", ")}"
        }
        repo.addMessage("user", sanitizedText)
        // Tool-enforcement system prompt is prepended for the voice path only.
        val requestHistory = listOf(ChatMessage("system", ToolRegistry.SYSTEM_PROMPT)) +
            repo.historyForRequest()

        val s = settings ?: return
        val client = WorkerClient(s.workerUrl, s.workerToken)
        val structured = JsonSchemaResponseFormat(
            type = "json_schema",
            json_schema = JsonSchemaSpec(
                name = "assistant_envelope",
                strict = true,
                schema = ToolRegistry.CALL_ENVELOPE_SCHEMA,
            ),
        )
        cancelRunnable = client.streamChat(
            requestHistory,
            s.model,
            repo.sessionId,
            object : WorkerClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    if (turn == myTurn) handleStructuredContent(textDelta, myTurn, allowTool = true)
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    if (turn != myTurn) return@onMain
                    // An Invalid/error turn already went idle — don't resurrect it.
                    if (error.value != null) {
                        main.removeCallbacks(stallIndicator)
                        working.value = false
                        stalled.value = false
                        return@onMain
                    }
                    // Round-trip turn: this first onComplete belongs to the clarification
                    // re-ask, not a real reply — skip it; the follow-up request completes.
                    if (callStage is CallStage.RoundTrip) {
                        callStage = CallStage.None
                        return@onMain
                    }
                    // Contact lookup is running on Dispatchers.IO.  The coroutine in
                    // resolveAndRouteCall will drive the call-flow stages (clarification /
                    // confirmation) when it returns.  Do not call completeStructuredTurn()
                    // here — that would set streamDone=true, call pump() → finishTurn() →
                    // startListening() → beginTurn() and bump turn, causing the lookup to
                    // fail its turn guard and silently discard results.
                    if (callStage is CallStage.ToolLookupInProgress) {
                        main.removeCallbacks(stallIndicator)
                        working.value = false
                        stalled.value = false
                        return@onMain
                    }
                    completeStructuredTurn()
                }

                override fun onError(message: String) = onMain {
                    if (turn != myTurn) return@onMain
                    main.removeCallbacks(stallIndicator)
                    working.value = false
                    stalled.value = false
                    error.value = message
                    goIdle()
                }
            },
            responseFormat = structured,
        )
    }

    /**
     * Finish a structured turn: flush buffered speech, persist a plain reply
     * (tokenized), and let the TTS pump decide when to listen again.
     * Tool-call prompts are UI-only and never persisted.
     */
    private fun completeStructuredTurn() {
        if (callStage is CallStage.RoundTrip) callStage = CallStage.None
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        val rest = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (rest.isNotEmpty()) enqueueSpeech(rest)
        pendingText.value = ""
        if (lastOutcomeWasReply && reply.value.isNotBlank()) {
            // Never persist phone numbers — persist the tokenized form.
            val toPersist = privacyFilter.filter(reply.value).sanitizedText
            repo.addMessage("assistant", toPersist)
        }
        viewModelScope.launch { repo.persist() }
        streamDone = true
        pump()
    }

    /**
     * Route a full structured response (the Worker is non-streaming, so onDelta
     * delivers the whole envelope at once) through the allowlisted tool registry.
     */
    private fun handleStructuredContent(content: String, myTurn: Int, allowTool: Boolean) {
        if (turn != myTurn) return
        when (val outcome = ToolRegistry.parse(content, allowTool)) {
            is ToolRegistry.Outcome.Reply -> {
                lastOutcomeWasReply = true
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                onTextDelta(outcome.text)
            }
            is ToolRegistry.Outcome.ToolCall -> {
                lastOutcomeWasReply = false
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                // Show ack immediately; never spoken here — confirmation speaks instead.
                reply.value = outcome.ack.ifBlank { "One moment." }
                executeCallTool(outcome.contactName, myTurn)
            }
            is ToolRegistry.Outcome.Invalid -> {
                lastOutcomeWasReply = false
                // Fail closed: no tool execution, no speech from a malformed envelope.
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                error.value = "Jarvis sent an unreadable response."
                goIdle()
            }
        }
    }

    /**
     * Resolve a [call_contact] tool request locally, then enter the voice-only
     * clarification / confirmation loop. Never dials without exact confirmation.
     */
    private fun executeCallTool(contactName: String, myTurn: Int) {
        if (turn != myTurn) return
        // Lazy permission check: if READ_CONTACTS is missing, ask for it once.
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
            getApplication<Application>().checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        if (!granted) {
            val token = ++callFlowToken
            callStage = CallStage.PermissionWait(token, contactName, reply.value)
            state.value = ConvState.Idle
            hint.value = null
            contactsPermissionRequest.value = token
            enqueueSpeech("I need access to your contacts to find ${contactName}. Please allow access.")
            main.removeCallbacks(permissionWaitTimeout)
            main.postDelayed(permissionWaitTimeout, PERMISSION_WAIT_MS)
            return
        }
        resolveAndRouteCall(contactName, myTurn)
    }

    /**
     * Contacts lookup + stage selection, using [ContactResolver.lookup] to separate
     * exact from approximate matches.
     *
     * - [LookupResult.ExactUnique]    — one name matches exactly; dial immediately if
     *   only one number is saved, or ask which number first if there are several.
     * - [LookupResult.ExactAmbiguous] — multiple contacts share the same exact name;
     *   ask which person the user means.
     * - [LookupResult.FuzzyMatches]   — no exact match but plausible alternatives found;
     *   ask which one using the "did you mean" prompt.
     * - [LookupResult.NotFound]       — nothing found; round-trip LLM reask.
     *
     * The ContentProvider query runs on [Dispatchers.IO]; all UI mutations resume on Main.
     */
    private fun resolveAndRouteCall(contactName: String, myTurn: Int) {
        if (turn != myTurn) return
        val context = getApplication<Application>()
        val myToken = callFlowToken
        callStage = CallStage.ToolLookupInProgress
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ContactResolver.lookup(context, contactName)
            }
            if (turn != myTurn || callFlowToken != myToken) return@launch
            when (result) {
                is LookupResult.ExactUnique -> {
                    val contact = result.contact
                    when {
                        contact.numbers.isEmpty() -> {
                            cancelCallFlow()
                            enqueueSpeech("I found ${contact.displayName}, but they have no phone number saved.")
                            streamDone = true
                            pump()
                        }
                        contact.numbers.size == 1 -> {
                            // Single exact match, single number — dial without extra confirmation.
                            cancelCallFlow()
                            dial(contact, contact.numbers[0])
                        }
                        else -> {
                            // Single exact match, multiple numbers — ask which one.
                            callStage = CallStage.ClarifyNumbers(contact)
                            val prompt = CallFlowPolicy.clarifyNumbersPrompt(
                                contact.displayName,
                                contact.numbers.map { it.label },
                            )
                            reply.value = prompt
                            speakAndListenForStage(prompt)
                        }
                    }
                }
                is LookupResult.ExactAmbiguous -> {
                    callStage = CallStage.ClarifyContacts(contactName, result.contacts)
                    val prompt = CallFlowPolicy.clarifyContactsPrompt(result.contacts.map { it.displayName })
                    reply.value = prompt
                    speakAndListenForStage(prompt)
                }
                is LookupResult.FuzzyMatches -> {
                    callStage = CallStage.ClarifyContacts(contactName, result.contacts)
                    val prompt = CallFlowPolicy.fuzzyMatchPrompt(result.contacts.map { it.displayName })
                    reply.value = prompt
                    speakAndListenForStage(prompt)
                }
                is LookupResult.NotFound -> {
                    roundTripReask("No contact named \"$contactName\" was found. Ask the user for a more specific name.")
                }
            }
        }
    }

    /** Speak a clarification / confirmation prompt, then listen for the answer. */
    private fun speakAndListenForStage(prompt: String) {
        enqueueSpeech(prompt)
        streamDone = true
        pump() // finishTurn() will startListening() once speech completes
    }

    /**
     * Ask the model (tool disabled) to rephrase after a failed local resolution.
     * The follow-up onComplete is swallowed via the RoundTrip stage.
     */
    private fun roundTripReask(note: String) {
        val s = settings ?: return
        callStage = CallStage.RoundTrip
        // System note is request-only — never persisted or added to the UI history.
        val requestHistory = listOf(ChatMessage("system", ToolRegistry.SYSTEM_PROMPT)) +
            repo.historyForRequest() +
            ChatMessage("system", "$note Answer with tool=\"none\" and ask a short question.")
        val client = WorkerClient(s.workerUrl, s.workerToken)
        val structured = JsonSchemaResponseFormat(
            type = "json_schema",
            json_schema = JsonSchemaSpec(
                name = "assistant_envelope",
                strict = true,
                schema = ToolRegistry.CALL_ENVELOPE_SCHEMA,
            ),
        )
        val myTurn = turn
        cancelRunnable = client.streamChat(
            requestHistory,
            s.model,
            repo.sessionId,
            object : WorkerClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    if (turn == myTurn) handleStructuredContent(textDelta, myTurn, allowTool = false)
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    if (turn != myTurn) return@onMain
                    if (error.value != null) {
                        main.removeCallbacks(stallIndicator)
                        working.value = false
                        stalled.value = false
                        return@onMain
                    }
                    completeStructuredTurn()
                }

                override fun onError(message: String) = onMain {
                    if (turn != myTurn) return@onMain
                    main.removeCallbacks(stallIndicator)
                    working.value = false
                    stalled.value = false
                    error.value = message
                    goIdle()
                }
            },
            responseFormat = structured,
        )
    }

    /** Invalidate the call flow: bump token, clear stage, cancel timers. */
    private fun cancelCallFlow() {
        callFlowToken++
        callStage = CallStage.None
        main.removeCallbacks(callFlowTimeout)
        main.removeCallbacks(permissionWaitTimeout)
    }

    /** Arm the confirmation / clarification listening window. */
    private fun armCallFlowTimeout() {
        main.removeCallbacks(callFlowTimeout)
        main.postDelayed(callFlowTimeout, CALL_CONFIRM_TIMEOUT_MS)
    }

    /**
     * Result of the READ_CONTACTS permission prompt. [nonce] must match the token
     * issued when the prompt was raised; a stale nonce is ignored.
     */
    fun onContactsPermissionResult(granted: Boolean, nonce: Int) {
        val stage = callStage
        if (nonce != callFlowToken || stage !is CallStage.PermissionWait) return
        main.removeCallbacks(permissionWaitTimeout)
        if (!granted) {
            cancelCallFlow()
            enqueueSpeech("I can't access your contacts, so I can't place that call.")
            streamDone = true
            pump()
            return
        }
        callStage = CallStage.None
        resolveAndRouteCall(stage.query, turn)
    }

    /** A token arrived: show it, and speak as soon as a full sentence is available. */
    private fun onTextDelta(delta: String) {
        reply.value += delta
        sentenceBuffer.append(delta)
        extractSentences()
        pendingText.value = sentenceBuffer.toString().trim()
        // Content is flowing → not stalled. Re-arm both timers.
        stalled.value = false
        main.removeCallbacks(idleFlush)
        main.postDelayed(idleFlush, IDLE_FLUSH_MS)
        main.removeCallbacks(stallIndicator)
        main.postDelayed(stallIndicator, STALL_MS)
    }

    private fun flushPendingSentence() {
        val s = sentenceBuffer.toString().trim()
        if (s.isNotEmpty() && (s.endsWith('.') || s.endsWith('!') || s.endsWith('?'))) {
            sentenceBuffer.setLength(0)
            pendingText.value = ""
            enqueueSpeech(s)
        }
    }

    private fun extractSentences() {
        while (true) {
            val s = sentenceBuffer
            var cut = -1
            for (i in s.indices) {
                val c = s[i]
                if (c == '\n') { cut = i; break }
                // sentence end only when we already see the following char is whitespace
                // (so "3.5" or a trailing "." mid-stream isn't split prematurely)
                if ((c == '.' || c == '!' || c == '?') && i + 1 < s.length && s[i + 1].isWhitespace()) {
                    cut = i; break
                }
            }
            if (cut < 0 && s.length > 180) { // soft cap so one long clause still starts early
                val sp = s.lastIndexOf(' ')
                if (sp > 40) cut = sp
            }
            if (cut < 0) break
            val sentence = s.substring(0, cut + 1).trim()
            s.delete(0, cut + 1)
            if (sentence.isNotEmpty()) enqueueSpeech(sentence)
        }
    }

    private fun enqueueSpeech(text: String) {
        ttsQueue.addLast(text)
        segments.add(text)
        pump()
    }

    /** Speak queued sentences one after another (the next synthesizes after the prior plays). */
    private fun pump() {
        if (speaking) return
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            if (streamDone) finishTurn()
            return
        }
        speaking = true
        speakingIndex.value = spokenCount
        spokenCount++
        if (state.value != ConvState.Speaking) {
            state.value = ConvState.Speaking
            // Jarvis is talking, so the listening watchdog and any pending auto-exit
            // are both irrelevant until this turn finishes.
            cancelListeningWatchdog()
            cancelSilenceExit()
            // Start a concurrent interrupt listener so the user can say a silence or exit
            // phrase — or new input — without tapping first.  See watchForInterrupt().
            watchForInterrupt()
        }
        val engine = tts ?: ensureFallback()
        if (engine == null) { speaking = false; return }
        val myTurn = turn
        engine.speak(
            text = next,
            onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
            onError = {
                // premium engine failed on this sentence — say it with on-device TTS, then continue
                val fb = ensureFallback()
                if (fb != null && fb !== engine) {
                    fb.speak(
                        text = next,
                        onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                        onError = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
                    )
                } else {
                    main.post { if (turn == myTurn) { speaking = false; pump() } }
                }
            },
        )
    }

    /**
     * Opens (and keeps open) a recognition session while TTS is speaking, so the user
     * can interrupt Jarvis mid-sentence by voice — "shut up", or any other command —
     * without tapping the mic first.
     *
     * **Only [SpeechInput].** The ElevenLabs path uses [AudioCapture], which records raw
     * PCM with no acoustic echo cancellation, so JARVIS's own voice would be transcribed
     * and treated as a user command.  The on-device [SpeechInput] path is used instead;
     * echo suppression there is whatever the platform engine provides, which is why the
     * acceptance gate below is "the recogniser reported speech onset", not "text arrived".
     *
     * **The window must be self-healing.** A [android.speech.SpeechRecognizer] session is
     * short-lived: with nobody talking it ends in NO_MATCH / SPEECH_TIMEOUT, and while
     * Google TTS holds the audio output it can fail outright with RECOGNIZER_BUSY /
     * AUDIO / CLIENT.  Either way the previous implementation simply swallowed the error
     * (Listener.onError defaults to a no-op), so one failed start left the whole reply
     * uninterruptible.  Every terminal outcome now re-arms the window after a short
     * backoff, for as long as the VM is still speaking.
     *
     * **Turn safety:** [myTurn] is captured at call time and every callback re-checks it.
     * [beginTurn], [goIdle], [stopAll], and [resetView] all advance/clear before touching
     * the recogniser, so a stale barge-in callback can never act.
     */
    private fun watchForInterrupt() {
        // Only meaningful while Jarvis is talking, and never re-entered while a session
        // from this same call is already live (guarded by the state check below).
        if (state.value != ConvState.Speaking) return
        if (recognizer !is SpeechInput) return  // no-AEC capture path — see kdoc
        val myTurn = turn
        var sawSpeech = false // set by onSpeechStart / onPartial, read by onFinal

        recognizer?.start(languageTag = null, listener = object : VoiceRecognizer.Listener {
            override fun onSpeechStart() = onMain {
                // Someone started talking while Jarvis speaks.  Refresh the watchdog so a
                // long interruption is never cut off, and cancel any pending auto-exit.
                if (turn != myTurn) return@onMain
                sawSpeech = true
                cancelSilenceExit()
                armListeningWatchdog()
            }

            override fun onPartial(text: String) = onMain {
                if (turn != myTurn) return@onMain
                if (text.isNotEmpty()) sawSpeech = true
                cancelSilenceExit()
                armListeningWatchdog()
            }

            override fun onFinal(text: String) = onMain {
                if (turn != myTurn) return@onMain
                cancelListeningWatchdog()
                if (!sawSpeech) {
                    // Text without a reported speech onset is almost always the phone
                    // speaker echoing back, or a decoder artefact — never act on it.
                    // Just keep listening for the rest of this reply.
                    scheduleBargeInRearm(myTurn)
                    return@onMain
                }
                when {
                    text.isBlank()         -> scheduleBargeInRearm(myTurn) // nothing usable
                    isSilenceCommand(text) -> { cancelCallFlow(); handleSilenceCommand() }
                    isExitCommand(text)    -> handleExitCommand()
                    else -> {
                        // User spoke new input while Jarvis was talking — barge in.
                        beginTurn()            // stops TTS immediately and clears queued sentences
                        transcript.value = text
                        think(text)
                    }
                }
            }

            // Recognition errors during TTS are expected (the user may simply not be
            // speaking, or TTS may be holding the audio focus).  They must never reach
            // the UI — the reply is still fine.  Re-arm so the window stays open.
            override fun onError(message: String, transient: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                Log.i(TAG, "barge-in session ended ($message) — re-arming while speaking")
                scheduleBargeInRearm(myTurn)
            }
        })
    }

    /**
     * Re-open the barge-in window shortly, but only if the same turn is still speaking.
     * Uses a fixed short backoff: long enough for a cancelling recogniser to have finished
     * releasing the mic (avoiding ERROR_RECOGNIZER_BUSY re-entry), short enough that the
     * gap between interruptions stays imperceptible.  [beginTurn] / [goIdle] / [stopAll]
     * remove this callback, so a queued re-arm can never outlive the reply.
     */
    private fun scheduleBargeInRearm(myTurn: Int) {
        if (turn != myTurn || state.value != ConvState.Speaking) return
        if (bargeInRearmsThisTurn >= MAX_BARGE_IN_REARMS) {
            // Engine cannot give us a usable session for this reply (e.g. the mic stays
            // contended for its whole duration).  Stop trying so we do not spin; the
            // mic-tap path remains available and the next turn retries from scratch.
            Log.w(TAG, "barge-in unavailable after $MAX_BARGE_IN_REARMS attempts — tap still works")
            return
        }
        bargeInRearmsThisTurn++
        main.removeCallbacks(bargeInRearm)
        main.postDelayed(bargeInRearm, BARGE_IN_REARM_MS)
    }

    private fun finishTurn() {
        // Permission wait: do not start listening — the system dialog owns input.
        if (callStage is CallStage.PermissionWait) {
            // Release the mic left over from the barge-in window opened while the
            // rationale was spoken, so the permission dialog is not fighting for it.
            runCatching { recognizer?.stop() }
            cancelListeningWatchdog()
            cancelSilenceExit()
            state.value = ConvState.Idle
            return
        }
        // Clarify / Confirm stages: arm the answer timeout, then listen.
        if (callStage is CallStage.ClarifyContacts || callStage is CallStage.ClarifyNumbers) {
            armCallFlowTimeout()
        }
        if (continuous) startListening() else goIdle()
    }

    /** Arm (or re-arm) the automatic-exit timer. */
    private fun armSilenceExit() {
        main.removeCallbacks(silenceExit)
        main.postDelayed(silenceExit, CONVERSATION_SILENCE_TIMEOUT_MS)
    }

    private fun cancelSilenceExit() {
        main.removeCallbacks(silenceExit)
    }

    /** Arm (or re-arm) the stuck-recognizer watchdog. Reset by every speech signal. */
    private fun armListeningWatchdog() {
        main.removeCallbacks(listeningWatchdog)
        main.postDelayed(listeningWatchdog, MAX_LISTENING_MS)
    }

    private fun cancelListeningWatchdog() {
        main.removeCallbacks(listeningWatchdog)
    }

    /**
     * Go idle WITHIN the conversation. Re-arms the wake word after a short settle
     * delay (WAKE_REARM_DELAY_MS) so the NNAPI/VoiceInteractionService engine can
     * detect the next "Jarvis" without requiring a tap.
     *
     * The delay prevents TTS tail-echo from self-triggering and the WakeWordService
     * REFRACTORY_MS (2500 ms) provides the hard gate against back-to-back fires —
     * so no loop-breaker gate is needed here.
     *
     * Note: emptyWakeTurns is still counted for diagnostic purposes but no longer
     * gates re-arming. The previous gate (MAX_EMPTY_WAKE) permanently suppressed
     * the wake engine after 2 consecutive no-speech wake turns, which is the
     * root cause of the "must press mic button" regression.
     */
    private fun goIdle() {
        // Stop any recognition still in flight before re-arming the wake word.
        // Two reasons, both specific to the barge-in session watchForInterrupt() opens
        // during Speaking:
        //  1. A late onFinal from that session would otherwise pass its `turn == myTurn`
        //     guard — goIdle() does not bump turn — and open an unintended new turn.
        //  2. It must release the mic BEFORE rearmWake fires WAKE_REARM_DELAY_MS later,
        //     otherwise the wake engine and SpeechRecognizer contend for RECORD_AUDIO.
        //     A failed AudioRecord init leaves paused=false, so isHealthy() would report
        //     healthy while neither engine is actually capturing.
        // Safe at every goIdle() call site: the session has already delivered a result
        // or an error, so this is a cancel on a finished or absent recognizer.  It also
        // fires on the Scribe path, whose stop() is likewise an idempotent cancel.
        runCatching { recognizer?.stop() }
        cancelListeningWatchdog()
        main.removeCallbacks(bargeInRearm)
        cancelCallFlow()
        state.value = ConvState.Idle
        main.removeCallbacks(rearmWake)
        // Track consecutive wake-triggered empty turns (diagnostic only).
        if (currentTurnFromWake) emptyWakeTurns++ else emptyWakeTurns = 0
        // Always re-arm after the settle delay — never suppress permanently.
        main.postDelayed(rearmWake, WAKE_REARM_DELAY_MS)
        // The turn is over and Jarvis is no longer speaking, so it is now safe to
        // start counting silence. Cancelled again by any speech onset or wake event.
        // Skipped while an error is showing: Retry/Dismiss must stay available and the
        // banner must never auto-dismiss itself.
        if (error.value == null) armSilenceExit() else cancelSilenceExit()
    }

    private fun ensureFallback(): TtsEngine? {
        (tts as? AndroidTts)?.let { return it }
        if (androidFallback == null) androidFallback = AndroidTts(getApplication(), languageTag = null)
        return androidFallback
    }

    fun onMicTap() {
        when (state.value) {
            ConvState.Listening -> { turn++; recognizer?.stop(); goIdle() }
            else -> startListening()
        }
    }

    fun stopAll() {
        continuous = false
        turn++
        emptyWakeTurns = 0
        cancelCallFlow()
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        main.removeCallbacks(rearmWake)
        main.removeCallbacks(bargeInRearm)
        cancelSilenceExit()
        cancelListeningWatchdog()
        working.value = false
        stalled.value = false
        recognizer?.release()
        recognizer = null
        runCatching { tts?.stop(); androidFallback?.stop() }
        cancelRunnable?.run()
        cancelRunnable = null
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        lastOutcomeWasReply = true
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        state.value = ConvState.Idle
        hint.value = null
        // Save the conversation (covers turns that ended in an error/cancel, not just
        // successful replies) before handing the mic back to the wake listener.
        repo.persistAsync()
        WakeWordService.resumeListening()
    }

    override fun onCleared() {
        cancelCallFlow()
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        main.removeCallbacks(rearmWake)
        main.removeCallbacks(bargeInRearm)
        cancelSilenceExit()
        cancelListeningWatchdog()
        recognizer?.release()
        cancelRunnable?.run()
        cancelRunnable = null
        tts?.shutdown()
        androidFallback?.shutdown()
        repo.persistAsync()
        WakeWordService.resumeListening()
        super.onCleared()
    }

    private companion object {
        const val IDLE_FLUSH_MS = 350L
        const val STALL_MS = 800L
        const val WAKE_REARM_DELAY_MS = 1200L

        /**
         * How long a spoken call confirmation / clarification waits for the user's
         * answer before cancelling (never dialling).
         */
        const val CALL_CONFIRM_TIMEOUT_MS = 12_000L

        /** How long the READ_CONTACTS system prompt waits before giving up. */
        const val PERMISSION_WAIT_MS = 30_000L

        /**
         * Backoff before re-opening the barge-in recognition window after a session ends
         * or errors while Jarvis is speaking.  Long enough for a cancelling
         * SpeechRecognizer to release the mic (so the restart does not immediately hit
         * ERROR_RECOGNIZER_BUSY), short enough that repeated interruptions feel instant.
         */
        const val BARGE_IN_REARM_MS = 300L

        /**
         * Ceiling on barge-in re-arms within one reply.  A healthy recogniser needs one
         * re-arm per ~10 s of silence between sentences, so this leaves ample headroom for
         * a long answer while still bounding worst-case work: with the 300 ms backoff the
         * engine is never restarted more than ~3×/s, and after the cap it is left alone.
         */
        const val MAX_BARGE_IN_REARMS = 20

        // ── automatic exit after silence ───────────────────────────────────────
        // THE single place this timeout is defined.
        /**
         * How long the conversation stays open after Jarvis has finished speaking (or
         * finished listening) without any further user speech.  On expiry the VM runs
         * the same teardown as saying "goodbye": stopAll() + exitRequested, which
         * releases the recognizer, hands the mic back to WakeWordService and moves the
         * activity to the background.  The app is not killed and wake-word detection
         * stays enabled, so "Hey Jarvis" works immediately afterwards.
         */
        const val CONVERSATION_SILENCE_TIMEOUT_MS = 20_000L

        /**
         * Recover-only ceiling on a single listening window.  If the recognizer
         * produces neither a result nor an error for this long while the VM is in
         * [ConvState.Listening], the watchdog forces idle so the conversation cannot get
         * stuck forever (and so [CONVERSATION_SILENCE_TIMEOUT_MS] can finally arm).
         *
         * Deliberately conservative at 45 s: it is well above the longest legitimate
         * path through either backend — AudioCapture caps one utterance at MAX_MS = 30 s
         * plus END_SILENCE_MS = 1.8 s plus transcription/upload time, and SpeechInput
         * relies on the platform speech timeout.  Crucially the watchdog is re-armed by
         * onSpeechStart and onPartial, so a user who is still talking resets it and is
         * never cut off; it can only fire while the recognizer is truly mute.  It also
         * cannot fire outside [ConvState.Listening], so it can never interrupt Jarvis
         * mid-sentence or abandon an in-flight request.
         */
        const val MAX_LISTENING_MS = 45_000L

        /**
         * Utterances that mean "stop speaking right now" — handled locally, never sent to LLM.
         * Only matched when the ENTIRE recognized text equals one of these (case-insensitive,
         * after stripping trailing punctuation), so they cannot fire inside a normal sentence.
         */
        val SILENCE_COMMANDS = setOf(
            "shut up",
            "stop",
            "be quiet",
            "stop talking",
            "quiet",
            "silence",
            "shh",
        )

        /**
         * Utterances that mean "exit the assistant entirely" — handled locally, never sent to LLM.
         * Conservative exact-match list to avoid false positives.
         */
        val EXIT_COMMANDS = setOf(
            "exit",
            "close",
            "stop listening",
            "end conversation",
            "goodbye",
            "bye",
            "close jarvis",
            "exit jarvis",
            "goodbye jarvis",
            "bye jarvis",
            "stop jarvis",
        )
    }
}
