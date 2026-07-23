package com.roco.catcher.data

import com.roco.catcher.model.AppSettings
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 洛克助手 HTTP/HTTPS 客户端：
 * - 自动兼容 http / https（无需设置开关）
 * - HTTPS 跳过证书校验（兼容自签证书）
 */
object HelperHttp {
    private val preferredSchemeByEndpoint = ConcurrentHashMap<String, String>()

    private val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }.socketFactory
    }

    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    fun apiClient(): OkHttpClient = baseBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun sseClient(): OkHttpClient = baseBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun baseUrls(settings: AppSettings): List<String> {
        val host = settings.helperIp.trim()
        val port = settings.helperPort ?: error("helperPort is not configured")
        val endpointKey = endpointKey(host, port)
        val https = "https://$host:$port"
        val http = "http://$host:$port"
        return when (preferredSchemeByEndpoint[endpointKey]) {
            "https" -> listOf(https, http)
            "http" -> listOf(http, https)
            else -> listOf(https, http)
        }
    }

    fun rememberSuccessfulBaseUrl(settings: AppSettings, baseUrl: String) {
        val host = settings.helperIp.trim()
        val port = settings.helperPort ?: return
        val scheme = when {
            baseUrl.startsWith("https://", ignoreCase = true) -> "https"
            baseUrl.startsWith("http://", ignoreCase = true) -> "http"
            else -> return
        }
        preferredSchemeByEndpoint[endpointKey(host, port)] = scheme
    }

    fun <T> withBaseUrls(
        settings: AppSettings,
        block: (baseUrl: String) -> T,
    ): T {
        val urls = baseUrls(settings)
        var lastError: Exception? = null
        for (baseUrl in urls) {
            try {
                val result = block(baseUrl)
                rememberSuccessfulBaseUrl(settings, baseUrl)
                return result
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("无法连接洛克助手")
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .sslSocketFactory(trustAllSocketFactory, trustAllManager)
        .hostnameVerifier(trustAllHostnameVerifier)

    private fun endpointKey(host: String, port: Int): String = "$host:$port"
}
