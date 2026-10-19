package dk.foss.jarvis.worker

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Talks to the Jarvis Cloudflare Worker.
 * POST /chat with Bearer auth.
 * Non-streaming (returns complete response), but exposes a streaming-compatible callback
 * so existing ViewModels can use it without changes.
 */
class WorkerClient(
    private val baseUrl: String,
    private val bearerToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    interface StreamCallbacks {
        fun onDelta(textDelta: String)
        fun onSessionId(id: String) {}
        fun onComplete() {}
        fun onError(message: String) {}
    }

    /**
     * Sends chat messages to the Worker and delivers the response via callbacks.
     * Since the Worker returns a non-streaming response, we simulate streaming
     * by calling onDelta once with the full content, then onComplete.
     */
    fun streamChat(
        messages: List<ChatMessage>,
        model: String, // ignored; Worker uses its configured model
        sessionId: String?, // ignored; Worker is stateless
        cb: StreamCallbacks,
        responseFormat: JsonSchemaResponseFormat? = null,
    ): Runnable {
        val requestMessages = messages.map { ChatMessage(it.role, it.content) }
        val body = json.encodeToString(
            WorkerChatRequest.serializer(),
            WorkerChatRequest(requestMessages, responseFormat),
        )

        val request = Request.Builder()
            .url("$baseUrl/chat")
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMedia))
            .build()

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val call = Http.base.newCall(request)
        val runnable = Runnable {
            try {
                call.execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val msg = when (response.code) {
                            401 -> "Unauthorized: Invalid or missing bearer token"
                            400 -> "Bad request: $responseBody"
                            429 -> "Rate limit exceeded"
                            500, 502, 503, 504 -> "Worker error: HTTP ${response.code}"
                            else -> "HTTP ${response.code}: ${responseBody.take(300)}"
                        }
                        if (finished.compareAndSet(false, true)) cb.onError(msg)
                        return@use
                    }

                    try {
                        val workerResponse = json.decodeFromString(WorkerChatResponse.serializer(), responseBody)
                        val content = workerResponse.choices.firstOrNull()?.message?.content ?: ""

                        var doneCalled = false
                        if (content.isNotEmpty()) {
                            cb.onDelta(content)
                        }
                        if (finished.compareAndSet(false, true)) {
                            doneCalled = true
                            cb.onComplete()
                        }
                        // onDelta doesn't mark the turn as finished; onComplete does.
                        // If content was empty, onDelta wasn't called but we still need onComplete.
                    } catch (e: Exception) {
                        if (finished.compareAndSet(false, true)) {
                            cb.onError("Failed to parse response: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (finished.compareAndSet(false, true)) {
                    cb.onError("Network error: ${e.message ?: "Unknown error"}")
                }
            }
        }

        // Execute on IO thread pool
        Http.base.dispatcher.executorService.execute(runnable)
        return Runnable { call.cancel() }
    }

    /** GET /models — returns model ids on success, or a failure with the reason. */
    suspend fun testConnection(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $bearerToken")
                .get()
                .build()
            Http.base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${text.take(200).ifBlank { response.message }}")
                }
                json.decodeFromString(WorkerModelsResponse.serializer(), text).data.map { it.id }
            }
        }
    }
}