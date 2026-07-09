package com.roco.capture.notify.data

import com.roco.capture.notify.model.AppSettings
import com.roco.capture.notify.model.HelperUser
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HelperApi {
    @Throws(IOException::class)
    fun fetchUsers(settings: AppSettings): List<HelperUser> {
        if (!settings.hasEndpoint) {
            throw IOException("请先配置洛克助手 IP 和端口")
        }

        val connection = URL("${settings.baseUrl()}/api/users").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/json")

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("读取用户失败：HTTP $code")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            return buildList {
                for (i in 0 until root.length()) {
                    val userObject = root.getJSONObject(i)
                    val uid = userObject.optString("uid")
                    val name = userObject.optString("name")
                    if (uid.isNotBlank() && name.isNotBlank()) {
                        add(
                            HelperUser(
                                uid = uid,
                                name = name,
                                avatar = userObject.optLong("avatar").takeIf { it > 0L },
                            ),
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
