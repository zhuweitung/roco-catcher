package com.roco.capture.notify.monitor

import com.roco.capture.notify.model.AppSettings
import com.roco.capture.notify.model.HelperUser
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HelperSseClient(
    private val settings: AppSettings,
    private val user: HelperUser,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onEvent(rawJson: String, eventName: String?)
        fun onClosed()
    }

    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    @Throws(IOException::class)
    fun connectAndRead() {
        if (!settings.hasEndpoint) {
            throw IOException("请先配置洛克助手 IP 和端口")
        }

        val url = URL("${settings.baseUrl()}/api/events?uid=${user.uid}")
        val conn = url.openConnection() as HttpURLConnection
        connection = conn
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 0
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("SSE 连接失败：HTTP $code")
            }

            listener.onOpen()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                readSseLines(reader)
            }
            listener.onClosed()
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    private fun readSseLines(reader: BufferedReader) {
        var eventName: String? = null
        val data = StringBuilder()

        while (!cancelled) {
            val line = reader.readLine() ?: break
            when {
                line.isBlank() -> {
                    dispatch(data, eventName)
                    eventName = null
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
                line.trimStart().startsWith("{") -> listener.onEvent(line.trim(), eventName)
            }
        }

        dispatch(data, eventName)
    }

    private fun dispatch(data: StringBuilder, eventName: String?) {
        if (data.isNotBlank()) {
            listener.onEvent(data.toString(), eventName)
            data.clear()
        }
    }
}
