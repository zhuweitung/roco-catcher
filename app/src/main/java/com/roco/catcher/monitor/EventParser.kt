package com.roco.catcher.monitor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class PetCaughtPayload(
    val baseConfId: String,
    val gid: Long,
    val occurredAtMillis: Long?,
)

data class ThrowBallPayload(
    val ballId: Long?,
    val occurredAtMillis: Long?,
)

object EventParser {
    fun readEventName(rawJson: String): String? {
        val root = parseRoot(rawJson) ?: return null
        return root.stringValue("event") ?: root.stringValue("type")
    }

    fun parsePetCatch(rawJson: String): PetCaughtPayload? {
        val root = parseRoot(rawJson) ?: return null
        val data = resolvePetData(root)
        val baseConfId = data.stringValue("base_conf_id")?.takeIf { it.isNotBlank() } ?: return null
        val gid = data.longValue("gid")?.takeIf { it > 0L } ?: return null
        val occurredAtMillis =
            (data.longValue("add_time") ?: data.objectValue("together_catch_info")
                ?.longValue("catch_time"))?.toEpochMillis()
        return PetCaughtPayload(
            baseConfId = baseConfId,
            gid = gid,
            occurredAtMillis = occurredAtMillis,
        )
    }

    fun parseThrowBall(rawJson: String): ThrowBallPayload? {
        val root = parseRoot(rawJson) ?: return null
        val data = root.objectValue("data") ?: root
        val ballId = data.longValue("ball_id")
        val occurredAtMillis = data.longValue("time")?.toEpochMillis()
        if (ballId == null && occurredAtMillis == null) return null
        return ThrowBallPayload(
            ballId = ballId,
            occurredAtMillis = occurredAtMillis,
        )
    }

    private fun parseRoot(rawJson: String): JsonObject? {
        return runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
    }

    /**
     * Supports both legacy `{ data: pet }` and new `{ data: { data: pet, id } }` envelopes,
     * as well as a bare pet payload object.
     */
    private fun resolvePetData(root: JsonObject): JsonObject {
        var current = root
        repeat(MAX_DATA_UNWRAP_DEPTH) {
            if (isPetPayload(current)) {
                return current
            }
            val nested = current["data"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return current
            current = nested
        }
        return current
    }

    private fun isPetPayload(obj: JsonObject): Boolean {
        return obj.containsKey("base_conf_id") && obj.containsKey("gid")
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }

    private fun JsonObject.longValue(key: String): Long? {
        return stringValue(key)?.toLongOrNull()
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
    }

    private fun Long.toEpochMillis(): Long? {
        if (this <= 0L) return null
        return if (this < MILLIS_TIMESTAMP_THRESHOLD) this * 1000L else this
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private const val MILLIS_TIMESTAMP_THRESHOLD = 100_000_000_000L
    private const val MAX_DATA_UNWRAP_DEPTH = 3
}