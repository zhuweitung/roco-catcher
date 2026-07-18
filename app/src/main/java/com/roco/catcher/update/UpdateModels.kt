package com.roco.catcher.update

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int?,
    val releaseName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
)

data class DownloadSource(
    val index: Int,
    val label: String,
    val url: String,
)

sealed class UpdateCheckResult {
    data class UpToDate(val currentVersionName: String) : UpdateCheckResult()
    data class UpdateAvailable(val update: AppUpdateInfo) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

sealed class DownloadState {
    data class Progress(
        val bytesRead: Long,
        val totalBytes: Long,
        val sourceLabel: String = "",
        val sourceIndex: Int = 0,
    ) : DownloadState() {
        val fraction: Float
            get() = if (totalBytes > 0L) {
                (bytesRead.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

        val indeterminate: Boolean
            get() = totalBytes <= 0L
    }

    data class Success(
        val filePath: String,
        val sourceLabel: String = "",
        val sourceIndex: Int = 0,
    ) : DownloadState()

    data class Failed(
        val message: String,
        val sourceLabel: String = "",
        val sourceIndex: Int = 0,
    ) : DownloadState()
}