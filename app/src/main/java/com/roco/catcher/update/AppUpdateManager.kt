package com.roco.catcher.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.runCatching

class AppUpdateManager(
    private val context: Context,
    private val releaseApi: GithubReleaseApi = GithubReleaseApi(),
    private val downloadClient: OkHttpClient = defaultDownloadClient,
) {
    fun currentVersionName(): String {
        return runCatching {
            packageInfo().versionName?.takeIf { it.isNotBlank() } ?: "unknown"
        }.getOrDefault("unknown")
    }

    fun currentVersionCode(): Int {
        return runCatching {
            val info = packageInfo()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        }.getOrDefault(0)
    }

    fun checkForUpdate(): UpdateCheckResult {
        return try {
            val release = releaseApi.fetchLatestRelease()
            val asset = selectApkAsset(release.assets)
                ?: return UpdateCheckResult.Failed("最新 Release 中未找到 APK 文件")

            val remoteVersionName = resolveVersionName(release, asset)
            if (remoteVersionName.isBlank()) {
                return UpdateCheckResult.Failed("无法解析远程版本号")
            }

            val remoteVersionCode = resolveVersionCode(release.body)
            val localVersionName = currentVersionName()
            val localVersionCode = currentVersionCode()

            val hasUpdate = when {
                remoteVersionCode != null && remoteVersionCode > 0 && localVersionCode > 0 ->
                    remoteVersionCode > localVersionCode
                else -> compareVersionName(remoteVersionName, localVersionName) > 0
            }

            if (!hasUpdate) {
                UpdateCheckResult.UpToDate(localVersionName)
            } else {
                UpdateCheckResult.UpdateAvailable(
                    AppUpdateInfo(
                        versionName = remoteVersionName,
                        versionCode = remoteVersionCode,
                        releaseName = release.name.ifBlank { release.tagName },
                        releaseNotes = release.body.trim(),
                        htmlUrl = release.htmlUrl,
                        apkName = asset.name,
                        apkDownloadUrl = asset.browserDownloadUrl,
                        apkSizeBytes = asset.size,
                    ),
                )
            }
        } catch (error: Exception) {
            UpdateCheckResult.Failed(error.message ?: "检查更新失败")
        }
    }

    fun listDownloadSources(officialUrl: String): List<DownloadSource> {
        return buildDownloadUrls(officialUrl).mapIndexed { index, url ->
            DownloadSource(
                index = index,
                label = if (index == 0) "官方" else sourceHostLabel(url),
                url = url,
            )
        }
    }

    fun download(
        update: AppUpdateInfo,
        startSourceIndex: Int = 0,
    ): Flow<DownloadState> = flow {
        val dir = updateDir().apply { mkdirs() }
        val target = File(dir, update.apkName.ifBlank { "roco-catcher-update.apk" })
        val partial = File(dir, "${target.name}.part")

        dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".part")) }
            ?.forEach { runCatching { it.delete() } }

        val sources = listDownloadSources(update.apkDownloadUrl)
        if (sources.isEmpty()) {
            emit(DownloadState.Failed("下载地址为空"))
            return@flow
        }

        val fromIndex = startSourceIndex.coerceIn(0, sources.lastIndex)
        var lastErrorMessage: String? = null
        var lastSourceLabel = sources[fromIndex].label
        var lastSourceIndex = fromIndex

        for (index in fromIndex until sources.size) {
            val source = sources[index]
            lastSourceLabel = source.label
            lastSourceIndex = source.index
            try {
                downloadToFile(source.url, partial) { read, total ->
                    emit(
                        DownloadState.Progress(
                            bytesRead = read,
                            totalBytes = total,
                            sourceLabel = source.label,
                            sourceIndex = source.index,
                        ),
                    )
                }
                if (!partial.exists() || partial.length() <= 0L) {
                    throw IOException("下载文件为空")
                }
                if (update.apkSizeBytes > 0L && partial.length() < update.apkSizeBytes) {
                    throw IOException("下载不完整：${partial.length()} / ${update.apkSizeBytes} 字节")
                }
                if (target.exists()) {
                    target.delete()
                }
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                emit(
                    DownloadState.Success(
                        filePath = target.absolutePath,
                        sourceLabel = source.label,
                        sourceIndex = source.index,
                    ),
                )
                return@flow
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastErrorMessage = error.message ?: "下载失败"
                if (partial.exists()) {
                    partial.delete()
                }
            }
        }
        emit(
            DownloadState.Failed(
                message = lastErrorMessage ?: "下载失败",
                sourceLabel = lastSourceLabel,
                sourceIndex = lastSourceIndex,
            ),
        )
    }.flowOn(Dispatchers.IO)

    fun install(apkFile: File) {
        if (!apkFile.exists()) {
            throw IOException("安装包不存在")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("下载失败：响应体为空")
            val total = body.contentLength()
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        onProgress(readTotal, total)
                    }
                    output.flush()
                }
            }
        }
    }

    private fun updateDir(): File {
        return context.getExternalFilesDir(UPDATES_DIR_NAME)
            ?: File(context.filesDir, UPDATES_DIR_NAME)
    }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    companion object {
        private const val UPDATES_DIR_NAME = "updates"
        private const val USER_AGENT = "roco-catcher-android"
        private val DOWNLOAD_MIRROR_PREFIXES = arrayOf(
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
        )
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024

        private val defaultDownloadClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun sourceHostLabel(url: String): String {
            return runCatching {
                val host = java.net.URI(url.trim()).host
                host?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: url.trim()
        }

        fun buildDownloadUrls(officialUrl: String): List<String> {
            val url = officialUrl.trim()
            if (url.isEmpty()) return emptyList()

            val result = LinkedHashSet<String>()
            result += url
            for (prefix in DOWNLOAD_MIRROR_PREFIXES) {
                val normalized = prefix.trim()
                if (normalized.isEmpty()) continue
                val withSlash = if (normalized.endsWith("/")) normalized else "$normalized/"
                if (url.startsWith(withSlash)) continue
                result += withSlash + url
            }
            return result.toList()
        }

        fun selectApkAsset(assets: List<GithubAsset>): GithubAsset? {
            if (assets.isEmpty()) return null
            val apkAssets = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) ||
                    it.contentType.contains("android.package-archive", ignoreCase = true)
            }
            if (apkAssets.isEmpty()) return null
            return apkAssets.firstOrNull {
                it.name.startsWith("com.roco.catcher", ignoreCase = true)
            } ?: apkAssets.first()
        }

        fun resolveVersionName(release: GithubRelease, asset: GithubAsset): String {
            val fromTag = normalizeVersionName(release.tagName)
            if (fromTag.isNotBlank()) return fromTag

            val fromReleaseName = normalizeVersionName(release.name)
            if (fromReleaseName.isNotBlank()) return fromReleaseName

            val fromAsset = Regex(
                """v?(\d+(?:\.\d+)+)""",
                RegexOption.IGNORE_CASE,
            ).find(asset.name)?.groupValues?.getOrNull(1)
            return fromAsset.orEmpty()
        }

        fun resolveVersionCode(body: String): Int? {
            if (body.isBlank()) return null
            val patterns = listOf(
                Regex("""versionCode\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE),
                Regex(""""versionCode"\s*:\s*(\d+)"""),
            )
            for (pattern in patterns) {
                val code = pattern.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (code != null && code > 0) return code
            }
            return null
        }

        fun normalizeVersionName(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val match = Regex(
                """v?(\d+(?:\.\d+)+)""",
                RegexOption.IGNORE_CASE,
            ).find(trimmed)
            return match?.groupValues?.getOrNull(1).orEmpty()
        }

        fun compareVersionName(left: String, right: String): Int {
            val leftParts = normalizeVersionName(left)
                .split('.')
                .map { it.toIntOrNull() ?: 0 }
            val rightParts = normalizeVersionName(right)
                .split('.')
                .map { it.toIntOrNull() ?: 0 }
            val size = max(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val l = leftParts.getOrElse(index) { 0 }
                val r = rightParts.getOrElse(index) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }
    }
}
