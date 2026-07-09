package com.roco.catcher.monitor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class PetChangedPayload(
    val baseConfId: String,
    val gid: Long,
)

object CaptureEventParser {
    fun parse(rawJson: String, eventName: String? = null): PetChangedPayload? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val type = root.stringValue("type") ?: eventName.orEmpty()
        val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: root
        val looksLikePetPayload = data.containsKey("base_conf_id") && data.containsKey("gid")

        if (type.isNotBlank() && type != "pet_info.changed") {
            return null
        }
        if (type.isBlank() && eventName != "pet_info.changed" && !looksLikePetPayload) {
            return null
        }

        val baseConfId = data.stringValue("base_conf_id")?.takeIf { it.isNotBlank() } ?: return null
        val gid = data.longValue("gid")?.takeIf { it > 0L } ?: return null
        return PetChangedPayload(baseConfId = baseConfId, gid = gid)
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    private fun JsonObject.longValue(key: String): Long? {
        return stringValue(key)?.toLongOrNull()
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }
}

