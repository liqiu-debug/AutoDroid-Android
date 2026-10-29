package org.autojs.autodroid.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Gson 安全取值扩展。
 *
 * Gson 的 `getAsJsonObject(String)` / `asString` 等在值为 [com.google.gson.JsonNull]
 * 或类型不符时会抛 ClassCastException / UnsupportedOperationException——平台下发的
 * JSON 里 `{"android": null}`、`"upload_response": null` 这类显式 null 很常见
 * （用例预览崩溃事故即由此而来），UI/解析层一律用这些取不到就返回 null 的访问器。
 */

fun JsonElement?.asObjectOrNull(): JsonObject? =
    this?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonElement?.asArrayOrNull(): JsonArray? =
    this?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonElement?.asStringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive }?.asString

fun JsonElement?.asIntOrNull(): Int? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

fun JsonElement?.asDoubleOrNull(): Double? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

fun JsonElement?.asBooleanOrNull(): Boolean? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
