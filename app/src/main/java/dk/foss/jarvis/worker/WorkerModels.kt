package dk.foss.jarvis.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WorkerChatRequest(
    val messages: List<ChatMessage>,
    val response_format: JsonSchemaResponseFormat? = null,
)

@Serializable
data class JsonSchemaResponseFormat(
    val type: String = "json_schema",
    val json_schema: JsonSchemaSpec,
)

@Serializable
data class JsonSchemaSpec(
    val name: String,
    val strict: Boolean = true,
    val schema: JsonObject,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class WorkerChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<WorkerChoice>,
    val usage: WorkerUsage,
)

@Serializable
data class WorkerChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String?,
)

@Serializable
data class WorkerUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
)

@Serializable
data class WorkerModelsResponse(
    val `object`: String,
    val data: List<WorkerModelEntry>,
)

@Serializable
data class WorkerModelEntry(
    val id: String,
    val `object`: String,
    val created: Long,
    val owned_by: String,
)