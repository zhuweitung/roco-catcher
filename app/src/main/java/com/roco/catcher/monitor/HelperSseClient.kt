package com.roco.catcher.monitor

import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.HelperUser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class HelperSseClient(
    private val settings: AppSettings,
    private val user: HelperUser,
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient,
) {
    interface Listener {
        fun onOpen()
        fun onEvent(rawJson: String, eventName: String?)
        fun onClosed()
        fun onFailure(message: String?)
    }

    @Volatile
    private var eventSource: EventSource? = null

    @Throws(IOException::class)
    fun connect() {
        if (!settings.hasEndpoint) {
            throw IOException("请先配置洛克助手 IP 和端口")
        }

        val request = Request.Builder()
            .url("${settings.baseUrl()}/api/events?uid=${user.uid}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        eventSource = EventSources.createFactory(client)
            .newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        listener.onOpen()
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        listener.onEvent(data, type)
                    }

                    override fun onClosed(eventSource: EventSource) {
                        listener.onClosed()
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        val status = response?.let { "HTTP ${it.code}" }
                        listener.onFailure(t?.message ?: status ?: "SSE 连接异常")
                    }
                },
            )
    }

    fun cancel() {
        eventSource?.cancel()
        eventSource = null
    }

    private companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

