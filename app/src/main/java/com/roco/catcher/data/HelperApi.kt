package com.roco.catcher.data

import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.HelperUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HelperApi(
    private val client: OkHttpClient = defaultClient,
) {
    @Throws(IOException::class)
    fun fetchUsers(settings: AppSettings): List<HelperUser> {
        if (!settings.hasEndpoint) {
            throw IOException("请先配置洛克助手 IP 和端口")
        }

        val request = Request.Builder()
            .url("${settings.baseUrl()}/api/users")
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("读取小洛克失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return json.decodeFromString<List<ApiUserDto>>(body)
                .filter { it.uid.isNotBlank() && it.name.isNotBlank() }
                .map { HelperUser(uid = it.uid, name = it.name, avatar = it.avatar) }
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
        }

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class ApiUserDto(
    val avatar: Long? = null,
    val name: String = "",
    val uid: String = "",
)

