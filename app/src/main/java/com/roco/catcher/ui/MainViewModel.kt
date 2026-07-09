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
import com.roco.catcher.model.NotifyMode
import com.roco.catcher.model.TargetSearchResult
import com.roco.catcher.monitor.CaptureMonitorService
import com.roco.catcher.monitor.CaptureTaskManager
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
    val selectedTarget: CaptureTarget? = null,
    val targetCountText: String = "1",
    val minRateText: String = "0",
    val targetQuery: String = "",
    val targetResults: List<TargetSearchResult> = emptyList(),
    val taskState: CaptureTaskState = CaptureTaskState(),
    val clockMillis: Long = System.currentTimeMillis(),
    val loadingUsers: Boolean = false,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application), MainActions {
    private val settingsStore = SettingsStore(application)
    private val chains = EvolutionChainRepository(application)
    private val helperApi = HelperApi()
    private val _uiState = MutableStateFlow(MainUiState(targetResults = chains.searchTargets("")))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var taskUnsubscribe: (() -> Unit)? = null
    private var clockJob: Job? = null

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
            val recent = settingsStore.loadRecentTask()
            val target = recent.targetName?.let(::findTargetByName)
            _uiState.update {
                it.copy(
                    selectedTarget = target,
                    targetQuery = target?.displayName.orEmpty(),
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
            val selectedTarget = it.selectedTarget.takeIf { target -> target?.displayName == value }
            it.copy(
                targetQuery = value,
                selectedTarget = selectedTarget,
                targetResults = chains.searchTargets(value),
            )
        }
    }

    override fun selectTarget(result: TargetSearchResult) {
        _uiState.update {
            it.copy(
                selectedTarget = result.target,
                targetQuery = result.title,
                targetResults = chains.searchTargets(result.title),
                message = "已选择 ${result.target.displayName}",
            )
        }
    }

    override fun selectUser(user: HelperUser) {
        _uiState.update { it.copy(selectedUser = user) }
    }

    override fun saveSettings(
        helperIp: String,
        helperPortText: String,
        targetNotifyMode: NotifyMode,
        lowSpeedNotifyMode: NotifyMode,
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
                    targetNotifyMode = targetNotifyMode,
                    lowSpeedNotifyMode = lowSpeedNotifyMode,
                ),
            )
            setMessage("设置已保存")
        }
    }

    override fun testConnection(
        helperIp: String,
        helperPortText: String,
        targetNotifyMode: NotifyMode,
        lowSpeedNotifyMode: NotifyMode,
    ) {
        val port = helperPortText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (helperIp.isBlank() || port == null) {
            setMessage("请填写有效 IP 和端口")
            return
        }

        val candidate = AppSettings(
            helperIp = helperIp.trim(),
            helperPort = port,
            targetNotifyMode = targetNotifyMode,
            lowSpeedNotifyMode = lowSpeedNotifyMode,
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
                targetName = config.target.displayName,
            )
        }
        CaptureTaskManager.startNewTask(config)
        CaptureMonitorService.start(getApplication())
        _uiState.update {
            it.copy(
                targetCountText = config.targetCount.toString(),
                minRateText = formatNumber(config.minRatePerMinute),
                message = null,
            )
        }
    }

    override fun continueTask() {
        CaptureTaskManager.continueCurrentTask()
        CaptureMonitorService.start(getApplication())
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
        val taskTarget = state.taskState.config?.target
        val target = state.selectedTarget ?: taskTarget?.takeIf {
            state.targetQuery.isBlank() || state.targetQuery == it.displayName
        }
        if (target == null) {
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
        return CaptureTaskConfig(user, target, targetCount, minRate)
    }

    private fun findTargetByName(name: String): CaptureTarget? {
        return chains.searchTargets(name)
            .firstOrNull { it.target.displayName == name }
            ?.target
    }

    private fun setMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    }
}

