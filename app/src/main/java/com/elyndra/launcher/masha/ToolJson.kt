package com.elyndra.launcher.masha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/* Lectura tolerante de los argumentos que manda el modelo: un número puede
   llegar como "30", una lista como un texto suelto. Mejor entenderlo que
   devolver un error por una comilla. */

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) {
        "yes", "si", "sí", "1" -> true
        "no", "0" -> false
        else -> null
    }
}

fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

/** Resultado correcto para el modelo, con [detail] y lo que añada [extra]. */
fun toolOk(
    detail: String? = null,
    attachment: MashaAttachment? = null,
    mutating: Boolean = false,
    extra: JsonObjectBuilder.() -> Unit = {},
): ToolResult = ToolResult(
    ok = true,
    payload = buildJsonObject {
        put("ok", true)
        detail?.let { put("detail", it) }
        extra()
    },
    attachment = attachment,
    mutating = mutating,
)

/** Resultado fallido: el modelo lo cuenta con estas palabras. */
fun toolFail(detail: String): ToolResult = ToolResult(
    ok = false,
    payload = buildJsonObject {
        put("ok", false)
        put("detail", detail)
    },
)
