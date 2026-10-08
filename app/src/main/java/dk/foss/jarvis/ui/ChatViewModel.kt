package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.voice.PrivacyFilter
import dk.foss.jarvis.voice.RegexPrivacyFilter
import dk.foss.jarvis.worker.WorkerClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val privacyFilter = RegexPrivacyFilter()

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    private var cancelRunnable: Runnable? = null

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
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            // Apply privacy filter
            val filterResult = privacyFilter.filter(text)
            val sanitizedText = filterResult.sanitizedText
            repo.addMessage("user", sanitizedText)
            val history = repo.historyForRequest()
            val assistantIndex = repo.addMessage("assistant", "")
            isStreaming.value = true

            val client = WorkerClient(s.workerUrl, s.workerToken)
            cancelRunnable = client.streamChat(history, s.model, repo.sessionId, object : WorkerClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    repo.appendToMessage(assistantIndex, textDelta)
                }

                override fun onSessionId(id: String) { repo.setSessionId(id) }

                override fun onComplete() = onMain {
                    isStreaming.value = false
                    cancelRunnable = null
                    viewModelScope.launch { repo.persist() }
                }

                override fun onError(message: String) = onMain {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $message", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $message", isError = true)
                    }
                    isStreaming.value = false
                    cancelRunnable = null
                    viewModelScope.launch { repo.persist() }
                }
            })
        }
    }

    override fun onCleared() {
        cancel()
        repo.persistAsync() // viewModelScope is already cancelled here
        super.onCleared()
    }
}