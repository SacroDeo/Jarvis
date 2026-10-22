package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.contacts.ToolRegistry
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.tools.AlarmTool
import dk.foss.jarvis.tools.AppLaunchTool
import dk.foss.jarvis.voice.ContactAwarePrivacyFilter
import dk.foss.jarvis.worker.ChatMessage
import dk.foss.jarvis.worker.JsonSchemaResponseFormat
import dk.foss.jarvis.worker.JsonSchemaSpec
import dk.foss.jarvis.worker.WorkerClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the text-chat interface.
 *
 * Uses the same multi-tool schema and system prompt as [ConversationViewModel]
 * so the model has a consistent view of available tools in both voice and text.
 *
 * Tool execution:
 *  - [ToolRegistry.TOOL_SET_ALARM]   → [AlarmTool.set] executed locally.
 *  - [ToolRegistry.TOOL_OPEN_APP]    → [AppLaunchTool.open] executed locally.
 *  - [ToolRegistry.TOOL_CALL_CONTACT] → not executed from text chat (voice-only
 *    for safety); model is asked to redirect the user to voice mode.
 *
 * After a tool executes the result is fed back in a bounded loop
 * ([MAX_ORCHESTRATION_STEPS]) so the model can compose a natural reply.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val privacyFilter by lazy { ContactAwarePrivacyFilter(app) }

    private fun onMain(block: () -> Unit) { main.post(block) }

    /** Sanitize a message for outgoing model requests. */
    private fun sanitize(text: String): String = privacyFilter.filter(text).sanitizedText

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    private var cancelRunnable: Runnable? = null
    private var orchestrationStep = 0
    private val turnToolResults = mutableListOf<ChatMessage>()
    /** True once at least one tool has been dispatched in the current chat turn. */
    private var toolExecutedThisTurn = false

    companion object {
        private const val MAX_ORCHESTRATION_STEPS = 3
    }

    fun newConversation() {
        cancel()
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
        }
    }

    fun cancel() {
        cancelRunnable?.run()
        cancelRunnable = null
        isStreaming.value = false
        orchestrationStep = 0
        turnToolResults.clear()
        toolExecutedThisTurn = false
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            orchestrationStep = 0
            turnToolResults.clear()
            toolExecutedThisTurn = false

            val filterResult = privacyFilter.filter(text)
            val sanitizedText = filterResult.sanitizedText
            repo.addMessage("user", sanitizedText)

            val assistantIndex = repo.addMessage("assistant", "")
            isStreaming.value = true

            requestModelTurn(s.workerUrl, s.workerToken, s.model, assistantIndex, allowTool = true)
        }
    }

    /**
     * Send a model request and handle the response.
     *
     * When the model requests a tool, execute it locally, add the result context,
     * and call [requestModelTurn] again with [allowTool] = false so the final
     * response is always a plain reply.
     *
     * The chat message at [assistantIndex] is filled only with the final plain reply;
     * intermediate tool envelopes are never written to the message list.
     */
    private fun requestModelTurn(
        workerUrl: String,
        workerToken: String,
        model: String,
        assistantIndex: Int,
        allowTool: Boolean,
    ) {
        val requestHistory = listOf(ChatMessage("system", ToolRegistry.MULTI_TOOL_SYSTEM_PROMPT)) +
            repo.historyForRequest(::sanitize).let { history ->
                // Drop the empty assistant placeholder — it's a UI slot only.
                // historyForRequest() already includes it; trim the trailing blank entry.
                if (history.isNotEmpty() && history.last().role == "assistant" &&
                    history.last().content.isBlank()
                ) history.dropLast(1) else history
            } +
            turnToolResults

        val client = WorkerClient(workerUrl, workerToken)
        val schema = JsonSchemaResponseFormat(
            type = "json_schema",
            json_schema = JsonSchemaSpec(
                name = "assistant_envelope",
                strict = true,
                schema = ToolRegistry.MULTI_TOOL_SCHEMA,
            ),
        )

        // Buffer the full response before deciding how to display it.
        val responseBuffer = StringBuilder()

        cancelRunnable = client.streamChat(
            requestHistory,
            model,
            repo.sessionId,
            object : WorkerClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    responseBuffer.append(textDelta)
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    val rawContent = responseBuffer.toString()
                    when (val outcome = ToolRegistry.parse(rawContent, allowTool)) {

                        is ToolRegistry.Outcome.Reply -> {
                            repo.replaceMessage(assistantIndex, outcome.text)
                            finishStream()
                        }

                        is ToolRegistry.Outcome.SetAlarmCall -> {
                            if (orchestrationStep >= MAX_ORCHESTRATION_STEPS) {
                                repo.replaceMessage(assistantIndex, outcome.ack.ifBlank { "Done." })
                                finishStream()
                                return@onMain
                            }
                            orchestrationStep++
                            toolExecutedThisTurn = true
                            val context = getApplication<Application>()
                            val result = AlarmTool.set(context, outcome.hour, outcome.minute, outcome.label)
                            val resultMsg = when (result) {
                                is AlarmTool.Result.Success     -> "Tool set_alarm: ${result.description}"
                                is AlarmTool.Result.InvalidTime -> "Tool set_alarm failed: invalid time ${outcome.hour}:${"%02d".format(outcome.minute)}"
                                is AlarmTool.Result.Failure     -> "Tool set_alarm failed: ${result.reason}"
                            }
                            turnToolResults.add(ChatMessage("system", resultMsg))
                            responseBuffer.clear()
                            requestModelTurn(workerUrl, workerToken, model, assistantIndex, allowTool = true)
                        }

                        is ToolRegistry.Outcome.OpenAppCall -> {
                            if (orchestrationStep >= MAX_ORCHESTRATION_STEPS) {
                                repo.replaceMessage(assistantIndex, outcome.ack.ifBlank { "Done." })
                                finishStream()
                                return@onMain
                            }
                            orchestrationStep++
                            toolExecutedThisTurn = true
                            val context = getApplication<Application>()
                            val result = AppLaunchTool.open(context, outcome.appName)
                            val resultMsg = when (result) {
                                is AppLaunchTool.Result.Success         -> "Tool open_app executed: opened ${result.appLabel}"
                                is AppLaunchTool.Result.NotFound        -> "Tool open_app failed: no app found for \"${result.query}\""
                                is AppLaunchTool.Result.MultipleMatches -> "Tool open_app ambiguous: matches ${result.labels.take(3).joinToString(", ")}"
                                is AppLaunchTool.Result.Failure         -> "Tool open_app failed: ${result.reason}"
                            }
                            turnToolResults.add(ChatMessage("system", resultMsg))
                            responseBuffer.clear()
                            requestModelTurn(workerUrl, workerToken, model, assistantIndex, allowTool = true)
                        }

                        is ToolRegistry.Outcome.ToolCall -> {
                            // call_contact is voice-only — redirect politely.
                            val redirectReply = "To place a call, please switch to voice mode by tapping the microphone."
                            repo.replaceMessage(assistantIndex, redirectReply)
                            finishStream()
                        }

                        is ToolRegistry.Outcome.Invalid -> {
                            // Malformed response — show a safe error.
                            val msg = "⚠️ I had trouble understanding my own response. Please try again."
                            repo.replaceMessage(assistantIndex, msg, isError = true)
                            finishStream()
                        }
                    }
                }

                override fun onError(message: String) = onMain {
                    val cur = messages.getOrNull(assistantIndex)
                    // If a tool already ran and the follow-up model request failed,
                    // the action (alarm/app launch) may have been dispatched.  Show
                    // an honest message rather than a generic "something went wrong".
                    val errMsg = if (toolExecutedThisTurn) {
                        "The action was requested, but I couldn't confirm it. Please check your device."
                    } else {
                        "⚠️ $message"
                    }
                    if (cur != null && cur.text.isBlank()) {
                        repo.replaceMessage(assistantIndex, errMsg, isError = !toolExecutedThisTurn)
                    } else {
                        repo.addMessage("assistant", errMsg, isError = !toolExecutedThisTurn)
                    }
                    finishStream()
                }
            },
            responseFormat = schema,
        )
    }

    private fun finishStream() {
        isStreaming.value = false
        cancelRunnable = null
        orchestrationStep = 0
        turnToolResults.clear()
        toolExecutedThisTurn = false
        viewModelScope.launch { repo.persist() }
    }

    override fun onCleared() {
        cancel()
        repo.persistAsync()
        super.onCleared()
    }
}
