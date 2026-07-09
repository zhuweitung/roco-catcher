package com.roco.capture.notify.monitor

import org.json.JSONObject

data class PetChangedPayload(
    val baseConfId: String,
    val gid: Long,
)

object CaptureEventParser {
    fun parse(rawJson: String, eventName: String? = null): PetChangedPayload? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val type = root.optString("type", eventName.orEmpty())
        val data = root.optJSONObject("data") ?: root
        val looksLikePetPayload = data.has("base_conf_id") && data.has("gid")

        if (type.isNotBlank() && type != "pet_info.changed") {
            return null
        }
        if (type.isBlank() && eventName != "pet_info.changed" && !looksLikePetPayload) {
            return null
        }

        val baseConfId = data.opt("base_conf_id")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val gid = data.optLong("gid", -1L).takeIf { it > 0L } ?: return null
        return PetChangedPayload(baseConfId = baseConfId, gid = gid)
    }
}
