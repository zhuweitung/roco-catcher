package com.roco.catcher.monitor

import com.roco.catcher.data.HelperHttp
import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.HelperUser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

class HelperSseClient(
    private val settings: AppSettings,
    private val user: HelperUser,
    private val listener: Listener,
    private val client: OkHttpClient = HelperHttp.sseClient(),
) {
    interface Listener {
        fun onOpen()
        fun onEvent(rawJson: String, eventName: String?, eventId: String?)
        fun onClosed()
        fun onFailure(message: String?)
    }

    @Volatile
    private var eventSource: EventSource? = null

    @Volatile
    private var opened: Boolean = false

    @Volatile
    private var cancelled: Boolean = false

    @Throws(IOException::class)
    fun connect() {
        if (!settings.hasEndpoint) {
            throw IOException("请先配置洛克助手 IP 和端口")
        }

        cancelled = false
        opened = false
        connectTo(HelperHttp.baseUrls(settings).first())
    }

    private fun connectTo(baseUrl: String) {
        if (cancelled) return

        val request = Request.Builder()
            .url("$baseUrl/api/events?uid=${user.uid}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        eventSource = EventSources.createFactory(client)
            .newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        opened = true
                        HelperHttp.rememberSuccessfulBaseUrl(settings, baseUrl)
                        listener.onOpen()
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        listener.onEvent(data, type, id)
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (!cancelled) {
                            listener.onClosed()
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (cancelled) return

                        // 仅在尚未成功建立连接时尝试另一协议（http/https）
                        val fallback = nextBaseUrl(baseUrl)
                        if (!opened && fallback != null) {
                            connectTo(fallback)
                            return
                        }

                        val status = response?.let { "HTTP ${it.code}" }
                        listener.onFailure(t?.message ?: status ?: "SSE 连接异常")
                    }
                },
            )
    }

    private fun nextBaseUrl(current: String): String? {
        val urls = HelperHttp.baseUrls(settings)
        val index = urls.indexOf(current)
        return if (index >= 0 && index < urls.lastIndex) urls[index + 1] else null
    }

    fun cancel() {
        cancelled = true
        eventSource?.cancel()
        eventSource = null
    }
}

