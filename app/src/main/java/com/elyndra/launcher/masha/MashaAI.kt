package com.elyndra.launcher.masha

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/* ─────────────────────────────────────────────────────────────
   Masha: la compañera que orquesta Elyndra.

   Este archivo es el contrato con "el cerebro": qué se le pide y
   qué devuelve, sin atarse a ningún proveedor. Hoy la implementa
   DeepSeekMashaAI; cambiar de modelo o de proveedor es escribir
   otra implementación de [MashaAI], no tocar la app.
   ───────────────────────────────────────────────────────────── */

/** Un turno de la conversación ya terminado. */
data class MashaTurn(val role: Role, val text: String) {
    enum class Role { User, Masha }
}

/** Una herramienta: nombre, para qué sirve y sus parámetros en JSON Schema. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** Lo que el modelo pide ejecutar. [arguments] ya viene parseado. */
data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

/**
 * Resultado de una herramienta.
 *
 * [payload] es lo que lee el modelo para contar lo que pasó; [attachment] es
 * lo que enseña la interfaz bajo el mensaje (una lista de juegos, un plan de
 * sesión…). [mutating] marca las que cambian algo: una respuesta que las usó
 * nunca se sirve de caché.
 */
data class ToolResult(
    val ok: Boolean,
    val payload: JsonObject,
    val attachment: MashaAttachment? = null,
    val mutating: Boolean = false,
)

/** Lo que Masha puede *hacer*. Lo implementa la app (ver ui/MashaActions). */
interface MashaToolbox {
    val specs: List<ToolSpec>
    suspend fun execute(call: ToolCall): ToolResult
}

/** Lo que va pasando mientras Masha contesta, en orden. */
sealed interface MashaEvent {
    /** Un trozo de texto nuevo (streaming). */
    data class Delta(val text: String) : MashaEvent

    data class ToolStarted(val call: ToolCall) : MashaEvent

    data class ToolFinished(val call: ToolCall, val result: ToolResult) : MashaEvent

    /** Respuesta completa. */
    data class Completed(val text: String, val usage: TokenUsage?, val fromCache: Boolean = false) : MashaEvent

    /** No se pudo terminar. [partial] es lo que llegó a escribirse. */
    data class Failed(val error: MashaError, val partial: String) : MashaEvent
}

data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val cachedPromptTokens: Int)

/** Por qué no hubo respuesta de la IA. Cada caso tiene su texto en la interfaz. */
sealed class MashaError(message: String) : Exception(message) {
    /** Sin clave configurada. */
    data object NotConfigured : MashaError("not configured")

    /** Sin red. */
    data object Offline : MashaError("offline")

    /** Clave rechazada (401). */
    data object Unauthorized : MashaError("unauthorized")

    /** Sin saldo en la cuenta del proveedor (DeepSeek responde 402). */
    data object InsufficientBalance : MashaError("insufficient balance")

    /** Demasiadas peticiones (429). */
    data object RateLimited : MashaError("rate limited")

    /** El servicio está saturado o caído (5xx). */
    data class Unavailable(val code: Int) : MashaError("unavailable $code")

    /** La petición no gustó (400/422): suele ser un error nuestro. */
    data class BadRequest(val code: Int, val detail: String) : MashaError("bad request $code: $detail")

    data class Network(val detail: String) : MashaError("network: $detail")

    /** La respuesta no se pudo entender. */
    data class Protocol(val detail: String) : MashaError("protocol: $detail")

    /** El modelo encadenó demasiadas herramientas sin llegar a contestar. */
    data object TooManyRounds : MashaError("too many tool rounds")
}

data class MashaChatRequest(
    /** Turnos anteriores, en orden (sin el mensaje nuevo). */
    val history: List<MashaTurn>,
    val message: String,
    /** El contexto del momento (biblioteca, sesiones, dispositivo…) en JSON. */
    val context: String,
    /** Huella estable del contexto: lo que cambia la respuesta, sin batería ni hora. */
    val contextFingerprint: String,
    /** Idioma de la interfaz ("es", "en"…). */
    val language: String,
    /** Se puede responder de caché si nada relevante ha cambiado (preguntas de consulta). */
    val cacheable: Boolean = false,
)

/** El cerebro de Masha: un proveedor de IA detrás de una interfaz. */
interface MashaAI {
    val providerName: String
    val model: String

    /** Hay clave y la IA en línea está permitida. */
    val isAvailable: Boolean

    /**
     * Conversación con herramientas y respuesta en streaming. El flujo termina
     * siempre con [MashaEvent.Completed] o [MashaEvent.Failed]; cancelarlo corta
     * la petición en curso.
     */
    fun chat(request: MashaChatRequest, toolbox: MashaToolbox?): Flow<MashaEvent>

    /** Una respuesta de una vez y sin herramientas. */
    suspend fun complete(system: String, prompt: String, maxTokens: Int = 400, temperature: Double = 1.0): Result<String>

    /**
     * Comprueba la clave con la llamada más barata posible. Si el proveedor lo
     * da, devuelve un detalle legible para Ajustes (DeepSeek: el saldo).
     */
    suspend fun ping(): Result<String?>
}

/**
 * Lo que un mensaje de Masha lleva debajo del texto. Se guarda en JSON con el
 * mensaje (tabla `masha_messages`) para que el hilo se vea igual al volver.
 */
@Serializable
sealed interface MashaAttachment {

    /** Juegos de la biblioteca, en tarjetas (resultado de una búsqueda o una lista). */
    @Serializable
    @SerialName("games")
    data class Games(val title: String? = null, val keys: List<String>, val total: Int = keys.size) : MashaAttachment

    /** Un plan de sesión: juegos y minutos, con botón para empezar. */
    @Serializable
    @SerialName("plan")
    data class Plan(
        val minMinutes: Int,
        val maxMinutes: Int,
        val mood: String,
        val blocks: List<PlanItem>,
    ) : MashaAttachment

    @Serializable
    data class PlanItem(val key: String, val minutes: Int, val reason: String)

    /** Un arco y dónde va. */
    @Serializable
    @SerialName("arc")
    data class ArcCard(val arcId: String, val title: String, val keys: List<String>, val done: Int, val total: Int) : MashaAttachment

    /** Algo que Masha hizo (lanzar, cambiar el tema…): una ficha pequeña de "hecho". */
    @Serializable
    @SerialName("done")
    data class Done(val label: String, val ok: Boolean = true) : MashaAttachment
}
