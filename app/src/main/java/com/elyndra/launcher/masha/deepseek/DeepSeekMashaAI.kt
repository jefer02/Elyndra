package com.elyndra.launcher.masha.deepseek

import com.elyndra.launcher.di.MashaHttp
import com.elyndra.launcher.masha.MashaAI
import com.elyndra.launcher.masha.MashaCache
import com.elyndra.launcher.masha.MashaChatRequest
import com.elyndra.launcher.masha.MashaEndpoint
import com.elyndra.launcher.masha.MashaError
import com.elyndra.launcher.masha.MashaEvent
import com.elyndra.launcher.masha.MashaPrompt
import com.elyndra.launcher.masha.MashaToolbox
import com.elyndra.launcher.masha.MashaTools
import com.elyndra.launcher.masha.MashaTurn
import com.elyndra.launcher.masha.TokenUsage
import com.elyndra.launcher.masha.ToolResult
import com.elyndra.launcher.masha.deepseek.DeepSeekProtocol.Message
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Masha sobre DeepSeek (`deepseek-chat`, compatible con la API de OpenAI).
 *
 * Una pregunta puede llevar varias vueltas: el modelo contesta en streaming
 * y, si pide herramientas, se ejecutan, se le devuelven los resultados y
 * sigue, hasta que responde con texto (como mucho [MAX_ROUNDS] vueltas). El
 * texto de todas las vueltas se va emitiendo según llega.
 */
@Singleton
class DeepSeekMashaAI @Inject constructor(
    private val config: MashaEndpoint,
    /** Perezoso: crear el cliente (TLS, almacén de certificados) no tiene por qué pasar al arrancar la app. */
    @MashaHttp private val http: Lazy<OkHttpClient>,
    private val cache: MashaCache,
) : MashaAI {

    override val providerName: String = "DeepSeek"
    override val model: String get() = config.model
    override val isAvailable: Boolean get() = config.hasKey && config.onlineEnabled

    override fun chat(request: MashaChatRequest, toolbox: MashaToolbox?): Flow<MashaEvent> = flow {
        if (!config.hasKey) {
            emit(MashaEvent.Failed(MashaError.NotConfigured, ""))
            return@flow
        }

        val cacheKey = if (request.cacheable) {
            cache.key(
                model, MashaPrompt.VERSION.toString(), request.language, normalize(request.message),
                request.contextFingerprint, request.history.takeLast(4).joinToString("|") { it.role.name + ":" + it.text.take(200) },
            )
        } else {
            null
        }
        cacheKey?.let { cache.get(it) }?.let { cached ->
            emit(MashaEvent.Delta(cached))
            emit(MashaEvent.Completed(cached, usage = null, fromCache = true))
            return@flow
        }

        val messages = ArrayList<Message>()
        messages += Message.System(MashaPrompt.SYSTEM)
        request.history.takeLast(MAX_HISTORY).forEach { turn ->
            val text = turn.text.take(MAX_TURN_CHARS)
            messages += if (turn.role == MashaTurn.Role.User) Message.User(text) else Message.Assistant(text)
        }
        // El contexto va en el último mensaje, no en el de sistema: así el
        // prefijo (sistema + herramientas + hilo) se repite igual entre
        // preguntas y DeepSeek lo sirve de su caché, más rápido y más barato.
        messages += Message.User(
            "CONTEXT (JSON):\n${request.context}\nUI_LANGUAGE: ${request.language}\n\nUSER MESSAGE:\n${request.message}",
        )

        val full = StringBuilder()
        var usage: TokenUsage? = null
        var mutated = false
        var needsBreak = false

        repeat(MAX_ROUNDS) {
            val acc = DeepSeekProtocol.StreamAccumulator()
            try {
                stream(messages, toolbox) { payload ->
                    val piece = acc.accept(payload)
                    if (piece.isNotEmpty()) {
                        // Entre el texto de una vuelta y el de la siguiente, un salto de párrafo.
                        if (needsBreak) {
                            needsBreak = false
                            full.append("\n\n")
                            emit(MashaEvent.Delta("\n\n"))
                        }
                        full.append(piece)
                        emit(MashaEvent.Delta(piece))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MashaError) {
                emit(MashaEvent.Failed(e, full.toString()))
                return@flow
            } catch (e: Exception) {
                emit(MashaEvent.Failed(MashaError.Network(e.message ?: e.javaClass.simpleName), full.toString()))
                return@flow
            }
            acc.usage?.let { u -> usage = usage?.let { it.plus(u) } ?: u }

            val calls = acc.toolCalls()
            if (calls.isEmpty() || toolbox == null) {
                val text = full.toString().trim()
                if (text.isEmpty()) {
                    emit(MashaEvent.Failed(MashaError.Protocol("respuesta vacía (${acc.finishReason})"), ""))
                    return@flow
                }
                if (cacheKey != null && !mutated) cache.put(cacheKey, text, CACHE_TTL_MS)
                emit(MashaEvent.Completed(text, usage))
                return@flow
            }

            messages += Message.Assistant(acc.content, calls)
            for (call in calls) {
                emit(MashaEvent.ToolStarted(call))
                val result = try {
                    toolbox.execute(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult(false, buildJsonObject {
                        put("ok", false)
                        put("error", e.message ?: e.javaClass.simpleName)
                    })
                }
                if (result.mutating || call.name in MashaTools.MUTATING) mutated = true
                emit(MashaEvent.ToolFinished(call, result))
                messages += Message.Tool(call.id, result.payload.toString())
            }
            if (full.isNotEmpty()) needsBreak = true
        }
        emit(MashaEvent.Failed(MashaError.TooManyRounds, full.toString()))
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(system: String, prompt: String, maxTokens: Int, temperature: Double): Result<String> =
        withContext(Dispatchers.IO) {
            if (!config.hasKey) return@withContext Result.failure(MashaError.NotConfigured)
            try {
                val body = DeepSeekProtocol.requestBody(
                    model = model,
                    messages = listOf(Message.System(system), Message.User(prompt)),
                    tools = null,
                    stream = false,
                    temperature = temperature,
                    maxTokens = maxTokens,
                )
                val text = execute(post("/chat/completions", body)) { response ->
                    DeepSeekProtocol.parseCompletion(response.body?.string().orEmpty())
                }
                if (text == null) Result.failure(MashaError.Protocol("respuesta vacía")) else Result.success(text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: MashaError) {
                Result.failure(e)
            }
        }

    /**
     * Comprueba la clave consultando el saldo (no gasta tokens). Devuelve el
     * saldo legible ("4.20 USD") si la API lo da.
     */
    override suspend fun ping(): Result<String?> = withContext(Dispatchers.IO) {
        if (!config.hasKey) return@withContext Result.failure(MashaError.NotConfigured)
        try {
            val request = Request.Builder()
                .url("${config.baseUrl}/user/balance")
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "application/json")
                .get()
                .build()
            val detail = execute(request) { response ->
                val root = runCatching { DeepSeekProtocol.json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
                val available = root?.get("is_available")?.toString()?.toBooleanStrictOrNull()
                if (available == false) throw MashaError.InsufficientBalance
                val info = runCatching { (root?.get("balance_infos") as? JsonArray)?.firstOrNull()?.jsonObject }.getOrNull()
                val total = info?.get("total_balance")?.toString()?.trim('"')
                val currency = info?.get("currency")?.toString()?.trim('"')
                if (total != null && currency != null) "$total $currency" else null
            }
            Result.success(detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MashaError) {
            Result.failure(e)
        }
    }

    /* ── red ──────────────────────────────────────────────────── */

    private suspend fun stream(messages: List<Message>, toolbox: MashaToolbox?, onData: suspend (String) -> Unit) {
        val body = DeepSeekProtocol.requestBody(
            model = model,
            messages = messages,
            tools = toolbox?.specs,
            stream = true,
            temperature = CHAT_TEMPERATURE,
            maxTokens = CHAT_MAX_TOKENS,
        )
        execute(post("/chat/completions", body)) { response ->
            val source = response.body?.source() ?: throw MashaError.Protocol("sin cuerpo")
            while (true) {
                val line = source.readUtf8Line() ?: break
                when (val parsed = DeepSeekProtocol.parseLine(line)) {
                    DeepSeekProtocol.SseLine.Done -> break
                    is DeepSeekProtocol.SseLine.Data -> onData(parsed.payload)
                    DeepSeekProtocol.SseLine.Skip -> Unit
                }
            }
        }
    }

    private fun post(path: String, body: String): Request = Request.Builder()
        .url(config.baseUrl + path)
        .header("Authorization", "Bearer ${config.apiKey}")
        .header("Accept", "application/json, text/event-stream")
        .post(body.toRequestBody(JSON))
        .build()

    /**
     * Ejecuta la llamada y traduce los errores. La lectura de OkHttp es
     * bloqueante y no se entera de que la corrutina se canceló, así que un
     * vigilante cancela la llamada en cuanto eso pase: cerrar el chat corta
     * la respuesta al instante en vez de esperar a que termine.
     */
    private suspend fun <T> execute(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call: Call = http.get().newCall(request)
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw DeepSeekProtocol.errorFor(response.code, response.body?.string().orEmpty())
                block(response)
            }
        } catch (e: UnknownHostException) {
            throw MashaError.Offline
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelada")
            throw MashaError.Network(e.message ?: e.javaClass.simpleName)
        } finally {
            watcher.cancel()
        }
    }

    private fun normalize(message: String) = message.trim().lowercase().replace(Regex("""\s+"""), " ")

    private fun TokenUsage.plus(o: TokenUsage) = TokenUsage(
        promptTokens + o.promptTokens,
        completionTokens + o.completionTokens,
        cachedPromptTokens + o.cachedPromptTokens,
    )

    private companion object {
        val JSON = "application/json".toMediaType()

        const val MAX_ROUNDS = 5
        const val MAX_HISTORY = 12
        const val MAX_TURN_CHARS = 1_500

        /** DeepSeek recomienda 1.0–1.3 para conversación; 1.0 mantiene los datos a raya. */
        const val CHAT_TEMPERATURE = 1.0
        const val CHAT_MAX_TOKENS = 900

        /** Una respuesta de consulta vale media hora si nada ha cambiado. */
        const val CACHE_TTL_MS = 30L * 60 * 1000
    }
}
