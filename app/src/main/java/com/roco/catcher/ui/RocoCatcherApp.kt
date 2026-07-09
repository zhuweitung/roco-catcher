package com.roco.catcher.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.HelperUser
import com.roco.catcher.model.LowSpeedKind
import com.roco.catcher.model.NotifyMode
import com.roco.catcher.model.RatePoint
import com.roco.catcher.model.TargetSearchResult
import com.roco.catcher.model.TaskStatus
import com.roco.catcher.monitor.RateCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val Ink = Color(0xFF141414)
private val ControlInk = Color(0xFF1F1F1F)
private val CardCream = Color(0xFFFFFAF2)
private val SoftCream = Color(0xFFF4F0E8)
private val WarmAmber = Color(0xFFFFC65F)
private val WarmAmberDeep = Color(0xFFEAA42D)
private val MutedLine = Color(0xFFD7CABB)
private val SuccessGreen = Color(0xFF2F9E66)
private val TextMuted = Color(0xFF6F665C)

private enum class AppTab(val label: String, val icon: ImageVector) {
    CaptureTask("捕获任务", Icons.Filled.Timeline),
    Settings("设置", Icons.Filled.Settings),
}

interface MainActions {
    fun updateTargetCount(value: String)
    fun updateMinRate(value: String)
    fun updateTargetQuery(value: String)
    fun selectTarget(result: com.roco.catcher.model.TargetSearchResult)
    fun selectUser(user: HelperUser)
    fun saveSettings(helperIp: String, helperPortText: String, targetNotifyMode: NotifyMode, lowSpeedNotifyMode: NotifyMode)
    fun testConnection(helperIp: String, helperPortText: String, targetNotifyMode: NotifyMode, lowSpeedNotifyMode: NotifyMode)
    fun loadUsers()
    fun startTask()
    fun continueTask()
    fun retryTask()
    fun pauseTask()
    fun clearMessage()
}

@Composable
fun RocoCatcherApp(
    uiState: MainUiState,
    actions: MainActions,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CaptureTask) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { CompactBottomBar(selectedTab = selectedTab, onSelect = { selectedTab = it }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 10.dp),
        ) {
            when (selectedTab) {
                AppTab.CaptureTask -> MonitorScreen(
                    uiState = uiState,
                    actions = actions,
                    onOpenSettings = { selectedTab = AppTab.Settings },
                )
                AppTab.Settings -> SettingsScreen(uiState = uiState, actions = actions)
            }
        }
    }
}

@Composable
private fun CompactBottomBar(selectedTab: AppTab, onSelect: (AppTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(CardCream)
            .padding(horizontal = 42.dp),
        horizontalArrangement = Arrangement.spacedBy(44.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val selected = selectedTab == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(28.dp)
                        .background(
                            color = if (selected) WarmAmber else Color.Transparent,
                            shape = RoundedCornerShape(18.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(21.dp),
                        tint = if (selected) Ink else TextMuted,
                    )
                }
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Ink else TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MonitorScreen(
    uiState: MainUiState,
    actions: MainActions,
    onOpenSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
    val state = uiState.taskState
    val effectiveRunMillis = effectiveRunMillis(state, uiState.clockMillis)
    val averageRate = RateCalculator.averageRate(state.caughtCount, effectiveRunMillis)
    val currentRate = RateCalculator.currentRate(state.caughtEvents, effectiveRunMillis)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!uiState.settings.hasEndpoint) {
            SectionCard(title = "需要配置") {
                Text("请先在设置页填写洛克助手 IP 和端口。")
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenSettings,
                    colors = appPrimaryButtonColors(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("去设置")
                }
            }
            return@Column
        }

        SectionCard(title = "任务配置") {
            UserPicker(uiState, actions)
            Spacer(Modifier.height(10.dp))
            TargetSearchDropdown(uiState, actions)
            Spacer(Modifier.height(10.dp))
            LabeledField("目标数量") {
                OutlinedTextField(
                    value = uiState.targetCountText,
                    onValueChange = actions::updateTargetCount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "目标数量" },
                    placeholder = { Text("请输入目标数量") },
                    leadingIcon = { Icon(Icons.Filled.FormatListNumbered, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LabeledField("最低速率") {
                OutlinedTextField(
                    value = uiState.minRateText,
                    onValueChange = actions::updateMinRate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "最低速率" },
                    placeholder = { Text("只/分钟；0 表示关闭") },
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(12.dp))
            ActionButtons(state.status, actions)
        }

        SectionCard(title = "任务状态") {
            MetricRow("状态", state.status.label)
            MetricRow("小洛克", uiState.selectedUser?.toString() ?: state.config?.user?.toString() ?: "未选择")
            MetricRow("目标", uiState.selectedTarget?.displayName ?: state.config?.target?.displayName ?: "未选择")
            MetricRow("运行时长", formatDuration(effectiveRunMillis))
            state.errorMessage?.let { WarningText(it) }
        }

        SectionCard(title = "捕获统计") {
            val targetCount = state.config?.targetCount ?: uiState.targetCountText.toIntOrNull() ?: 1
            MetricRow("当前捕获", "${state.caughtCount}/$targetCount")
            MetricRow("平均速率", "${formatRate(averageRate)} / 分钟")
            MetricRow("当前速率", "${formatRate(currentRate)} / 分钟")
            MetricRow("低速提醒", lowSpeedLabel(state.lowSpeedState.kind))
        }

        SectionCard(title = "历史速率") {
            RateLineChart(
                points = state.rateHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .semantics { contentDescription = "历史速率折线图" },
            )
        }

        SectionCard(title = "最近捕获") {
            if (state.caughtEvents.isEmpty()) {
                Text("暂无捕获记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }
                state.caughtEvents.takeLast(10).asReversed().forEach { event ->
                    Text(
                        text = "${timeFormat.format(Date(event.caughtAtMillis))}  " +
                            "${event.petName ?: event.baseConfId}  gid=${event.gid}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserPicker(uiState: MainUiState, actions: MainActions) {
    UserDropdown(
        users = uiState.users,
        selectedUser = uiState.selectedUser,
        loadingUsers = uiState.loadingUsers,
        onRequestLoad = actions::loadUsers,
        onSelect = actions::selectUser,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDropdown(
    users: List<HelperUser>,
    selectedUser: HelperUser?,
    loadingUsers: Boolean,
    onRequestLoad: () -> Unit,
    onSelect: (HelperUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    LabeledField("小洛克", modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded && users.isNotEmpty(),
            onExpandedChange = {
                if (users.isEmpty() && !loadingUsers) {
                    onRequestLoad()
                }
                expanded = it
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedUser?.toString().orEmpty(),
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .semantics { contentDescription = "选择小洛克" },
                placeholder = { Text(if (loadingUsers) "读取小洛克中..." else "请选择小洛克") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && users.isNotEmpty())
                },
                readOnly = true,
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = appTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = expanded && users.isNotEmpty(),
                onDismissRequest = { expanded = false },
                modifier = Modifier.exposedDropdownSize(),
            ) {
                users.forEach { user ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = user.toString(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelect(user)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSearchDropdown(uiState: MainUiState, actions: MainActions) {
    var expanded by remember { mutableStateOf(false) }
    val taskTargetName = uiState.taskState.config?.target?.displayName
    val selectedTargetName = uiState.selectedTarget?.displayName
        ?: taskTargetName?.takeIf { uiState.targetQuery.isBlank() || uiState.targetQuery == it }

    LabeledField("捕获目标") {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = uiState.targetQuery,
                onValueChange = {
                    actions.updateTargetQuery(it)
                    expanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .semantics { contentDescription = "捕获目标" },
                placeholder = { Text(selectedTargetName ?: "输入精灵或进化链名称") },
                leadingIcon = { Icon(Icons.Filled.Pets, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = appTextFieldColors(),
            )

            ExposedDropdownMenu(
                expanded = expanded && uiState.targetResults.isNotEmpty(),
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .exposedDropdownSize()
                    .heightIn(max = 320.dp),
            ) {
                uiState.targetResults.forEach { result ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = result.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            actions.selectTarget(result)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(status: TaskStatus, actions: MainActions) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (status) {
            TaskStatus.Idle -> PrimaryActionButton("开始执行", Icons.Filled.PlayArrow, actions::startTask)
            TaskStatus.Connecting, TaskStatus.Running, TaskStatus.Reconnecting ->
                DangerActionButton("暂停", Icons.Filled.Pause, actions::pauseTask)
            TaskStatus.Paused -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryActionButton(
                        label = "继续执行",
                        icon = Icons.Filled.PlayArrow,
                        onClick = actions::continueTask,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTaskButton(
                        label = "重新开始",
                        icon = Icons.Filled.Refresh,
                        onClick = actions::startTask,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            TaskStatus.Failed -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryActionButton(
                        label = "重试",
                        icon = Icons.Filled.Refresh,
                        onClick = actions::retryTask,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTaskButton(
                        label = "重新开始",
                        icon = Icons.Filled.PlayArrow,
                        onClick = actions::startTask,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DangerActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PrimaryActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = appPrimaryButtonColors(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutlinedTaskButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = appSecondaryButtonColors(),
        border = appSecondaryButtonBorder(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        content()
    }
}

@Composable
private fun appPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = WarmAmber,
    contentColor = Ink,
    disabledContainerColor = Color(0xFFEAD7B2),
    disabledContentColor = TextMuted,
)

@Composable
private fun appSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = SoftCream,
    contentColor = Ink,
    disabledContainerColor = SoftCream,
    disabledContentColor = TextMuted,
)

@Composable
private fun appSecondaryButtonBorder() = BorderStroke(1.dp, MutedLine)

@Composable
private fun appDarkPillButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = ControlInk,
    contentColor = Color.White,
    disabledContainerColor = ControlInk,
    disabledContentColor = Color(0xFFBEB8AD),
)

@Composable
private fun appTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color(0xFFBEB8AD),
    errorTextColor = Color.White,
    focusedContainerColor = ControlInk,
    unfocusedContainerColor = ControlInk,
    disabledContainerColor = ControlInk,
    errorContainerColor = ControlInk,
    cursorColor = WarmAmber,
    focusedBorderColor = WarmAmber,
    unfocusedBorderColor = Color(0xFF6F675F),
    disabledBorderColor = Color(0xFF514C45),
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLabelColor = WarmAmber,
    unfocusedLabelColor = SoftCream,
    disabledLabelColor = Color(0xFFBEB8AD),
    errorLabelColor = MaterialTheme.colorScheme.error,
    focusedPlaceholderColor = Color(0xFFBEB8AD),
    unfocusedPlaceholderColor = Color(0xFFBEB8AD),
    disabledPlaceholderColor = Color(0xFF8D867C),
    focusedLeadingIconColor = WarmAmber,
    unfocusedLeadingIconColor = SoftCream,
    disabledLeadingIconColor = Color(0xFFBEB8AD),
    focusedTrailingIconColor = WarmAmber,
    unfocusedTrailingIconColor = SoftCream,
    disabledTrailingIconColor = Color(0xFFBEB8AD),
)

@Composable
private fun SettingsScreen(uiState: MainUiState, actions: MainActions) {
    var ip by rememberSaveable { mutableStateOf(uiState.settings.helperIp) }
    var port by rememberSaveable { mutableStateOf(uiState.settings.helperPort?.toString().orEmpty()) }
    var targetNotifyMode by rememberSaveable { mutableStateOf(uiState.settings.targetNotifyMode) }
    var lowSpeedNotifyMode by rememberSaveable { mutableStateOf(uiState.settings.lowSpeedNotifyMode) }
    val context = LocalContext.current
    val notificationGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    LaunchedEffect(uiState.settings) {
        ip = uiState.settings.helperIp
        port = uiState.settings.helperPort?.toString().orEmpty()
        targetNotifyMode = uiState.settings.targetNotifyMode
        lowSpeedNotifyMode = uiState.settings.lowSpeedNotifyMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "洛克助手连接") {
            LabeledField("IP 地址") {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "IP 地址" },
                    placeholder = { Text("请输入洛克助手 IP") },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LabeledField("端口") {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "端口" },
                    placeholder = { Text("请输入端口") },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            NotifyModeDropdown("目标达成通知", targetNotifyMode) { targetNotifyMode = it }
            Spacer(Modifier.height(10.dp))
            NotifyModeDropdown("低速提醒通知", lowSpeedNotifyMode) { lowSpeedNotifyMode = it }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.saveSettings(ip, port, targetNotifyMode, lowSpeedNotifyMode) },
                    modifier = Modifier.weight(1f),
                    colors = appPrimaryButtonColors(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("保存")
                }
                OutlinedButton(
                    onClick = { actions.testConnection(ip, port, targetNotifyMode, lowSpeedNotifyMode) },
                    enabled = !uiState.loadingUsers,
                    modifier = Modifier.weight(1f),
                    colors = appSecondaryButtonColors(),
                    border = appSecondaryButtonBorder(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(if (uiState.loadingUsers) "测试中" else "测试连接")
                }
            }
        }

        if (!notificationGranted) {
            SectionCard(title = "通知权限") {
                WarningText("通知权限未开启，目标达成和低速提醒可能无法显示。")
                Text("请在系统设置中为本 App 开启通知权限。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NotifyModeDropdown(label: String, value: NotifyMode, onChange: (NotifyMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = appDarkPillButtonColors(),
                border = BorderStroke(1.dp, ControlInk),
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(value.label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NotifyMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            onChange(mode)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardCream),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextMuted)
        Text(value, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WarningText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RateLineChart(points: List<RatePoint>, modifier: Modifier = Modifier) {
    val lineColor = WarmAmberDeep
    val dotColor = SuccessGreen
    val axisColor = MutedLine
    val textColor = TextMuted

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 46.dp.toPx()
            val top = 16.dp.toPx()
            val right = size.width - 10.dp.toPx()
            val bottom = size.height - 34.dp.toPx()
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

            val visible = points.takeLast(24)
            if (visible.isEmpty()) return@Canvas

            val maxRate = max(1.0, visible.maxOf { it.ratePerMinute })
            val xStep = if (visible.size == 1) 0f else (right - left) / (visible.size - 1)
            val path = Path()
            visible.forEachIndexed { index, point ->
                val x = if (visible.size == 1) (left + right) / 2f else left + xStep * index
                val y = bottom - ((point.ratePerMinute / maxRate).toFloat() * (bottom - top))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(dotColor, radius = 5.dp.toPx(), center = Offset(x, y))
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        if (points.isEmpty()) {
            Text(
                "暂无速率数据",
                color = textColor,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun effectiveRunMillis(state: CaptureTaskState, nowMillis: Long): Long {
    val active = state.activeRunStartedAtMillis
    val activeElapsed = if (active != null) (nowMillis - active).coerceAtLeast(0L) else 0L
    return state.accumulatedRunMillis + activeElapsed
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}分${seconds.toString().padStart(2, '0')}秒"
}

private fun formatRate(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun lowSpeedLabel(kind: LowSpeedKind): String {
    return when (kind) {
        LowSpeedKind.Disabled -> "关闭"
        LowSpeedKind.WarmingUp -> "预热中"
        LowSpeedKind.Normal -> "正常"
        LowSpeedKind.Pending -> "低速确认中"
        LowSpeedKind.Alerted -> "已提醒，等待恢复"
        LowSpeedKind.SuppressedAfterTargetReached -> "达标后停止"
    }
}
