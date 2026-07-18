package com.roco.catcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roco.catcher.data.EvolutionChainRepository
import com.roco.catcher.data.HelperApi
import com.roco.catcher.data.SettingsStore
import com.roco.catcher.model.AppSettings
import com.roco.catcher.model.CaptureTarget
import com.roco.catcher.model.CaptureTaskConfig
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.HelperUser
import com.roco.catcher.model.TargetSearchResult
import com.roco.catcher.monitor.CaptureMonitorService
import com.roco.catcher.monitor.CaptureTaskManager
import com.roco.catcher.update.AppUpdateInfo
import com.roco.catcher.update.AppUpdateManager
import com.roco.catcher.update.DownloadState
import com.roco.catcher.update.UpdateCheckResult
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val users: List<HelperUser> = emptyList(),
    val selectedUser: HelperUser? = null,
    val selectedTargets: List<CaptureTarget> = emptyList(),
    val targetCountText: String = "",
    val minRateText: String = "",
    val targetQuery: String = "",
    val targetResults: List<TargetSearchResult> = emptyList(),
    val taskState: CaptureTaskState = CaptureTaskState(),
    val clockMillis: Long = System.currentTimeMillis(),
    val loadingUsers: Boolean = false,
    val message: String? = null,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val updateChecking: Boolean = false,
    val updateDownloading: Boolean = false,
    val updateDownloadProgress: Float? = null,
    val updateDownloadIndeterminate: Boolean = false,
    val pendingUpdate: AppUpdateInfo? = null,
    val downloadedApkPath: String? = null,
    val showUpdateDialog: Boolean = false,
    val downloadSourceIndex: Int = 0,
    val downloadSourceLabel: String = "官方",
    val downloadSourceCount: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application), MainActions {
    private val settingsStore = SettingsStore(application)
    private val chains = EvolutionChainRepository(application)
    private val helperApi = HelperApi()
    private val updateManager = AppUpdateManager(application)
    private val _uiState = MutableStateFlow(
        MainUiState(
            targetResults = chains.searchTargets(""),
            appVersionName = updateManager.currentVersionName(),
            appVersionCode = updateManager.currentVersionCode(),
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var taskUnsubscribe: (() -> Unit)? = null
    private var clockJob: Job? = null
    private var updateJob: Job? = null
    private var downloadSession: Int = 0

    init {
        taskUnsubscribe = CaptureTaskManager.addListener { taskState ->
            _uiState.update { it.copy(taskState = taskState) }
        }

        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }

        viewModelScope.launch {
            val persistedTask = settingsStore.loadActiveTask()
            if (persistedTask != null && CaptureTaskManager.currentState().config == null) {
                val shouldResume = persistedTask.status == com.roco.catcher.model.TaskStatus.Running ||
                    persistedTask.status == com.roco.catcher.model.TaskStatus.Connecting ||
                    persistedTask.status == com.roco.catcher.model.TaskStatus.Reconnecting
                if (CaptureTaskManager.restoreTask(persistedTask, resumeMonitoring = shouldResume) && shouldResume) {
                    CaptureMonitorService.start(application)
                }
            }

            val recent = settingsStore.loadRecentTask()
            val targets = CaptureTaskManager.currentState().config
                ?.resolvedTargets()
                .orEmpty()
                .ifEmpty { recent.targets }
                .ifEmpty { recent.resolvedTargetNames().mapNotNull(::findTargetByName) }
            _uiState.update {
                it.copy(
                    selectedTargets = targets,
                    targetQuery = "",
                    targetCountText = recent.targetCount.toString(),
                    minRateText = formatNumber(recent.minRatePerMinute),
                )
            }
        }

        clockJob = viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(clockMillis = System.currentTimeMillis()) }
                delay(1000L)
            }
        }
    }

    override fun onCleared() {
        taskUnsubscribe?.invoke()
        clockJob?.cancel()
        updateJob?.cancel()
        super.onCleared()
    }

    override fun updateTargetCount(value: String) {
        _uiState.update { it.copy(targetCountText = value.filter { char -> char.isDigit() }) }
    }

    override fun updateMinRate(value: String) {
        val filtered = value.filterIndexed { index, char ->
            char.isDigit() || (char == '.' && value.indexOf('.') == index)
        }
        _uiState.update { it.copy(minRateText = filtered) }
    }

    override fun updateTargetQuery(value: String) {
        _uiState.update {
            it.copy(
                targetQuery = value,
                targetResults = chains.searchTargets(value),
            )
        }
    }

    override fun selectTarget(result: TargetSearchResult) {
        _uiState.update { state ->
            val exists = state.selectedTargets.any { sameTarget(it, result.target) }
            val nextTargets = if (exists) {
                state.selectedTargets.filterNot { sameTarget(it, result.target) }
            } else {
                state.selectedTargets + result.target
            }
            val message = if (exists) {
                "已取消 ${result.target.displayName}"
            } else {
                "已添加 ${result.target.displayName}（${nextTargets.size}）"
            }
            state.copy(
                selectedTargets = nextTargets,
                targetQuery = "",
                targetResults = chains.searchTargets(""),
                message = message,
            )
        }
    }

    override fun removeTarget(target: CaptureTarget) {
        _uiState.update { state ->
            state.copy(
                selectedTargets = state.selectedTargets.filterNot { sameTarget(it, target) },
            )
        }
    }

    override fun selectUser(user: HelperUser) {
        _uiState.update { it.copy(selectedUser = user) }
    }

    override fun saveSettings(
        helperIp: String,
        helperPortText: String,
        targetNotifyEnabled: Boolean,
        lowSpeedNotifyEnabled: Boolean,
    ) {
        val port = helperPortText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (helperIp.isBlank() || port == null) {
            setMessage("请填写有效 IP 和端口")
            return
        }

        viewModelScope.launch {
            settingsStore.save(
                AppSettings(
                    helperIp = helperIp.trim(),
                    helperPort = port,
                    targetNotifyEnabled = targetNotifyEnabled,
                    lowSpeedNotifyEnabled = lowSpeedNotifyEnabled,
                ),
            )
            setMessage("设置已保存")
        }
    }

    override fun testConnection(
        helperIp: String,
        helperPortText: String,
        targetNotifyEnabled: Boolean,
        lowSpeedNotifyEnabled: Boolean,
    ) {
        val port = helperPortText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (helperIp.isBlank() || port == null) {
            setMessage("请填写有效 IP 和端口")
            return
        }

        val candidate = AppSettings(
            helperIp = helperIp.trim(),
            helperPort = port,
            targetNotifyEnabled = targetNotifyEnabled,
            lowSpeedNotifyEnabled = lowSpeedNotifyEnabled,
        )

        _uiState.update { it.copy(loadingUsers = true, message = "正在测试连接...") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { helperApi.fetchUsers(candidate) } }
                .onSuccess { loaded ->
                    settingsStore.save(candidate)
                    _uiState.update {
                        it.copy(
                            users = loaded,
                            selectedUser = loaded.firstOrNull(),
                            loadingUsers = false,
                            message = "连接成功，读取到 ${loaded.size} 个小洛克",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loadingUsers = false, message = error.message ?: "连接失败")
                    }
                }
        }
    }

    override fun loadUsers() {
        val settings = _uiState.value.settings
        if (!settings.hasEndpoint) {
            setMessage("请先配置 IP 和端口")
            return
        }

        _uiState.update { it.copy(loadingUsers = true, message = "正在读取小洛克...") }
        viewModelScope.launch {
            val recent = settingsStore.loadRecentTask()
            runCatching { withContext(Dispatchers.IO) { helperApi.fetchUsers(settings) } }
                .onSuccess { loaded ->
                    _uiState.update {
                        it.copy(
                            users = loaded,
                            selectedUser = loaded.firstOrNull { user -> user.uid == recent.uid } ?: loaded.firstOrNull(),
                            loadingUsers = false,
                            message = "读取到 ${loaded.size} 个小洛克",
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(loadingUsers = false, message = error.message ?: "读取小洛克失败")
                    }
                }
        }
    }

    override fun startTask() {
        val config = buildTaskConfig() ?: return
        viewModelScope.launch {
            settingsStore.saveRecentTask(
                targetCount = config.targetCount,
                minRate = config.minRatePerMinute,
                uid = config.user.uid,
                targetName = config.resolvedTargets().firstOrNull()?.displayName,
                targets = config.resolvedTargets(),
            )
        }
        CaptureTaskManager.startNewTask(config)
        CaptureMonitorService.start(getApplication())
        _uiState.update {
            it.copy(
                selectedTargets = config.resolvedTargets(),
                targetCountText = config.targetCount.toString(),
                minRateText = formatNumber(config.minRatePerMinute),
                message = null,
            )
        }
    }

    override fun continueTask() {
        val config = buildResumeTaskConfig() ?: return
        viewModelScope.launch {
            settingsStore.saveRecentTask(
                targetCount = config.targetCount,
                minRate = config.minRatePerMinute,
                uid = config.user.uid,
                targetName = config.resolvedTargets().firstOrNull()?.displayName,
                targets = config.resolvedTargets(),
            )
        }
        CaptureTaskManager.continueCurrentTask(config)
        CaptureMonitorService.start(getApplication())
        _uiState.update {
            it.copy(
                targetCountText = config.targetCount.toString(),
                minRateText = formatNumber(config.minRatePerMinute),
                message = null,
            )
        }
    }

    override fun retryTask() {
        CaptureTaskManager.markConnecting()
        CaptureMonitorService.start(getApplication())
    }

    override fun pauseTask() {
        CaptureTaskManager.pauseByUser()
        CaptureMonitorService.stop(getApplication())
    }

    override fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun checkForUpdate() {
        if (_uiState.value.updateChecking || _uiState.value.updateDownloading) return

        updateJob?.cancel()
        _uiState.update {
            it.copy(
                updateChecking = true,
                updateDownloadProgress = null,
                updateDownloadIndeterminate = false,
            )
        }
        updateJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
            when (result) {
                is UpdateCheckResult.UpToDate -> {
                    _uiState.update {
                        it.copy(
                            updateChecking = false,
                            pendingUpdate = null,
                            showUpdateDialog = false,
                            message = "已是最新版本 ${result.currentVersionName}",
                        )
                    }
                }
                is UpdateCheckResult.UpdateAvailable -> {
                    val sources = updateManager.listDownloadSources(result.update.apkDownloadUrl)
                    _uiState.update {
                        it.copy(
                            updateChecking = false,
                            pendingUpdate = result.update,
                            showUpdateDialog = true,
                            downloadedApkPath = null,
                            downloadSourceIndex = 0,
                            downloadSourceLabel = sources.firstOrNull()?.label ?: "官方",
                            downloadSourceCount = sources.size,
                            message = "发现新版本 ${result.update.versionName}",
                        )
                    }
                }
                is UpdateCheckResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            updateChecking = false,
                            message = result.message,
                        )
                    }
                }
            }
        }
    }

    override fun downloadUpdate() {
        val state = _uiState.value
        val update = state.pendingUpdate ?: return
        startDownload(update, state.downloadSourceIndex)
    }

    override fun cancelUpdateDownload() {
        if (!_uiState.value.updateDownloading) return
        downloadSession += 1
        updateJob?.cancel()
        updateJob = null
        _uiState.update {
            it.copy(
                updateDownloading = false,
                updateDownloadProgress = null,
                updateDownloadIndeterminate = false,
                message = "已取消下载",
            )
        }
    }

    override fun switchUpdateDownloadSource() {
        val state = _uiState.value
        val update = state.pendingUpdate ?: return
        val sources = updateManager.listDownloadSources(update.apkDownloadUrl)
        if (sources.size <= 1) {
            setMessage("没有可用的镜像源")
            return
        }

        val nextIndex = (state.downloadSourceIndex + 1) % sources.size
        val nextSource = sources[nextIndex]
        if (state.updateDownloading) {
            startDownload(update, nextIndex, statusMessage = "已切换到${nextSource.label}，重新下载...")
        } else {
            _uiState.update {
                it.copy(
                    downloadSourceIndex = nextIndex,
                    downloadSourceLabel = nextSource.label,
                    downloadSourceCount = sources.size,
                    message = "已切换到${nextSource.label}",
                )
            }
        }
    }

    override fun installDownloadedUpdate() {
        val path = _uiState.value.downloadedApkPath
        if (path.isNullOrBlank()) {
            setMessage("请先下载更新包")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            setMessage("更新包不存在，请重新下载")
            return
        }
        if (!updateManager.canRequestPackageInstalls()) {
            runCatching {
                getApplication<Application>().startActivity(updateManager.installPermissionSettingsIntent())
            }
            setMessage("请允许安装未知应用后，再点击安装")
            return
        }
        runCatching { updateManager.install(file) }
            .onFailure { error -> setMessage(error.message ?: "无法打开安装界面") }
    }

    override fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    private fun startDownload(
        update: AppUpdateInfo,
        sourceIndex: Int,
        statusMessage: String = "开始下载更新...",
    ) {
        if (_uiState.value.updateChecking) return

        val sources = updateManager.listDownloadSources(update.apkDownloadUrl)
        if (sources.isEmpty()) {
            setMessage("下载地址为空")
            return
        }
        val startIndex = sourceIndex.coerceIn(0, sources.lastIndex)
        val startSource = sources[startIndex]

        updateJob?.cancel()
        _uiState.update {
            it.copy(
                updateDownloading = true,
                updateDownloadProgress = 0f,
                updateDownloadIndeterminate = true,
                downloadedApkPath = null,
                downloadSourceIndex = startIndex,
                downloadSourceLabel = startSource.label,
                downloadSourceCount = sources.size,
                showUpdateDialog = true,
                message = statusMessage,
            )
        }
        val session = ++downloadSession
        updateJob = viewModelScope.launch {
            try {
                updateManager.download(update, startSourceIndex = startIndex).collect { state ->
                    if (session != downloadSession) return@collect
                    when (state) {
                        is DownloadState.Progress -> {
                            _uiState.update {
                                it.copy(
                                    updateDownloadProgress = if (state.indeterminate) null else state.fraction,
                                    updateDownloadIndeterminate = state.indeterminate,
                                    downloadSourceIndex = state.sourceIndex,
                                    downloadSourceLabel = state.sourceLabel.ifBlank { it.downloadSourceLabel },
                                )
                            }
                        }
                        is DownloadState.Success -> {
                            _uiState.update {
                                it.copy(
                                    updateDownloading = false,
                                    updateDownloadProgress = 1f,
                                    updateDownloadIndeterminate = false,
                                    downloadedApkPath = state.filePath,
                                    downloadSourceIndex = state.sourceIndex,
                                    downloadSourceLabel = state.sourceLabel.ifBlank { it.downloadSourceLabel },
                                    showUpdateDialog = true,
                                    message = "下载完成，准备安装",
                                )
                            }
                            val file = File(state.filePath)
                            if (updateManager.canRequestPackageInstalls()) {
                                runCatching { updateManager.install(file) }
                                    .onFailure { error -> setMessage(error.message ?: "无法打开安装界面") }
                            } else {
                                runCatching {
                                    getApplication<Application>().startActivity(
                                        updateManager.installPermissionSettingsIntent(),
                                    )
                                }
                                setMessage("请允许安装未知应用后，再点击安装")
                            }
                        }
                        is DownloadState.Failed -> {
                            _uiState.update {
                                it.copy(
                                    updateDownloading = false,
                                    updateDownloadProgress = null,
                                    updateDownloadIndeterminate = false,
                                    downloadSourceIndex = state.sourceIndex,
                                    downloadSourceLabel = state.sourceLabel.ifBlank { it.downloadSourceLabel },
                                    message = state.message,
                                )
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled by user or by switching download source.
            }
        }
    }
    private fun buildTaskConfig(): CaptureTaskConfig? {
        val state = _uiState.value
        if (!state.settings.hasEndpoint) {
            setMessage("请先配置 IP 和端口")
            return null
        }
        val user = state.selectedUser ?: state.taskState.config?.user
        if (user == null) {
            setMessage("请先读取并选择小洛克")
            return null
        }
        val targets = state.selectedTargets
        if (targets.isEmpty()) {
            setMessage("请先选择捕获目标")
            return null
        }
        val targetCount = state.targetCountText.toIntOrNull()?.takeIf { it > 0 }
        if (targetCount == null) {
            setMessage("目标数量必须大于 0")
            return null
        }
        val minRate = state.minRateText.toDoubleOrNull()?.takeIf { it >= 0.0 }
        if (minRate == null) {
            setMessage("最低速率必须大于等于 0")
            return null
        }
        return CaptureTaskConfig(
            user = user,
            target = targets.first(),
            targetCount = targetCount,
            minRatePerMinute = minRate,
            targets = targets,
        )
    }

    private fun buildResumeTaskConfig(): CaptureTaskConfig? {
        val state = _uiState.value
        if (!state.settings.hasEndpoint) {
            setMessage("请先配置 IP 和端口")
            return null
        }
        val currentConfig = state.taskState.config
        if (currentConfig == null) {
            setMessage("没有可继续的捕获任务")
            return null
        }
        val targetCount = state.targetCountText.toIntOrNull()?.takeIf { it > 0 }
        if (targetCount == null) {
            setMessage("目标数量必须大于 0")
            return null
        }
        val minRate = state.minRateText.toDoubleOrNull()?.takeIf { it >= 0.0 }
        if (minRate == null) {
            setMessage("最低速率必须大于等于 0")
            return null
        }
        return currentConfig.copy(
            targetCount = targetCount,
            minRatePerMinute = minRate,
        )
    }

    private fun findTargetByName(name: String): CaptureTarget? {
        return chains.searchTargets(name)
            .firstOrNull { it.target.displayName == name }
            ?.target
    }

    private fun sameTarget(left: CaptureTarget, right: CaptureTarget): Boolean {
        return when {
            left is CaptureTarget.SinglePet && right is CaptureTarget.SinglePet ->
                left.petId == right.petId
            left is CaptureTarget.Chain && right is CaptureTarget.Chain ->
                left.displayName == right.displayName
            else -> left.displayName == right.displayName &&
                left.targetBaseConfIds == right.targetBaseConfIds
        }
    }

    private fun setMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    }
}
