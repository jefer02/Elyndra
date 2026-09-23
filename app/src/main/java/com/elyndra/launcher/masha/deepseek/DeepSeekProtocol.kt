package com.elyndra.launcher.masha.deepseek

import com.elyndra.launcher.masha.MashaError
import com.elyndra.launcher.masha.TokenUsage
import com.elyndra.launcher.masha.ToolCall
import com.elyndra.launcher.masha.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * El formato de la API de chat de DeepSeek (compatible con la de OpenAI):
 * cómo se arma la petición, cómo se leen los eventos del streaming y cómo se
 * traducen los errores. Sin red ni Android: se prueba entero en la JVM.
 *
 *   POST {baseUrl}/chat/completions
 *   Authorization: Bearer <clave>
 *
 * En streaming llegan líneas SSE `data: {json}` con trozos de texto
 * (`choices[0].delta.content`) o de llamadas a herramientas
 * (`choices[0].delta.tool_calls[i]`, con los argumentos a pedazos), líneas
 * `: keep-alive` mientras el servidor piensa, y `data: [DONE]` al final.
 */
object DeepSeekProtocol {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Un mensaje del hilo, tal como lo espera la API. */
    sealed interface Message {
        data class System(val content: String) : Message
        data class User(val content: String) : Message
        data class Assistant(val content: String, val toolCalls: List<ToolCall> = emptyList()) : Message
        data class Tool(val callId: String, val content: String) : Message
    }

    fun requestBody(
        model: String,
        messages: List<Message>,
        tools: List<ToolSpec>?,
        stream: Boolean,
        temperature: Double,
        maxTokens: Int,
    ): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") { messages.forEach { add(encode(it)) } }
        put("stream", stream)
        // Con esto el último trozo trae el recuento de tokens (y los que salieron de la caché de DeepSeek).
        if (stream) putJsonObject("stream_options") { put("include_usage", true) }
        put("temperature", temperature)
        put("max_tokens", maxTokens)
        if (!tools.isNullOrEmpty()) {
            putJsonArray("tools") {
                tools.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", spec.parameters)
                        }
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }.toString()

    private fun encode(m: Message): JsonObject = when (m) {
        is Message.System -> buildJsonObject {
            put("role", "system")
            put("content", m.content)
        }
        is Message.User -> buildJsonObject {
            put("role", "user")
            put("content", m.content)
        }
        is Message.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", m.content)
            if (m.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    m.toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            }
                        }
                    }
                }
            }
        }
        is Message.Tool -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.callId)
            put("content", m.content)
        }
    }

    /* ── streaming ────────────────────────────────────────────── */

    sealed interface SseLine {
        data class Data(val payload: String) : SseLine
        data object Done : SseLine
        /** Línea vacía, comentario (`: keep-alive`) u otro campo SSE. */
        data object Skip : SseLine
    }

    fun parseLine(line: String): SseLine {
        val trimmed = line.trimEnd('\r')
        if (!trimmed.startsWith("data:")) return SseLine.Skip
        val payload = trimmed.removePrefix("data:").trim()
        return when {
            payload.isEmpty() -> SseLine.Skip
            payload == "[DONE]" -> SseLine.Done
            else -> SseLine.Data(payload)
        }
    }

    /**
     * Junta los trozos de una respuesta en streaming: el texto, las llamadas a
     * herramientas (que llegan por índice y con los argumentos partidos), el
     * motivo de parada y el recuento de tokens.
     */
    class StreamAccumulator {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, PartialCall>()

        var finishReason: String? = null
            private set
        var usage: TokenUsage? = null
            private set

        val content: String get() = text.toString()
        val hasToolCalls: Boolean get() = calls.isNotEmpty()

        private class PartialCall(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

        /** Procesa un evento `data:`; devuelve el texto nuevo que trae (vacío si ninguno). */
        fun accept(payload: String): String {
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrElse { throw MashaError.Protocol("chunk ilegible") }
            root["usage"]?.takeIf { it is JsonObject }?.let { usage = parseUsage(it.jsonObject) }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
            choice.string("finish_reason")?.let { finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return ""
            (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                val c = element as? JsonObject ?: return@forEach
                val index = (c["index"] as? JsonPrimitive)?.intOrNull ?: calls.size
                val partial = calls.getOrPut(index) { PartialCall() }
                c.string("id")?.let { partial.id = it }
                (c["function"] as? JsonObject)?.let { f ->
                    f.string("name")?.let { if (it.isNotEmpty()) partial.name = it }
                    f.string("arguments")?.let { partial.arguments.append(it) }
                }
            }
            val piece = delta.string("content").orEmpty()
            text.append(piece)
            return piece
        }

        /** Las llamadas completas. Unos argumentos ilegibles se entregan como `{}`: la herramienta dirá qué falta. */
        fun toolCalls(): List<ToolCall> = calls.entries.mapNotNull { (index, c) ->
            if (c.name.isBlank()) return@mapNotNull null
            val args = c.arguments.toString().trim().ifEmpty { "{}" }
            val parsed = runCatching { json.parseToJsonElement(args).jsonObject }.getOrElse { JsonObject(emptyMap()) }
            ToolCall(id = c.id ?: "call_$index", name = c.name, arguments = parsed)
        }
    }

    fun parseUsage(u: JsonObject): TokenUsage = TokenUsage(
        promptTokens = u.int("prompt_tokens"),
        completionTokens = u.int("completion_tokens"),
        cachedPromptTokens = u.int("prompt_cache_hit_tokens"),
    )

    /** Texto de una respuesta sin streaming. */
    fun parseCompletion(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject.string("content")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Error de la API → error de Masha. El cuerpo trae `{"error": {"message": …}}`. */
    fun errorFor(code: Int, body: String): MashaError {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.string("message")
        }.getOrNull() ?: body.take(200)
        return when (code) {
            401, 403 -> MashaError.Unauthorized
            402 -> MashaError.InsufficientBalance
            429 -> MashaError.RateLimited
            400, 404, 422 -> MashaError.BadRequest(code, detail)
            in 500..599 -> MashaError.Unavailable(code)
            else -> MashaError.BadRequest(code, detail)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
