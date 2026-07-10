package com.roco.catcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Numbers
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roco.catcher.model.CaptureTaskState
import com.roco.catcher.model.HelperUser
import com.roco.catcher.model.LOW_SPEED_PENDING_MILLIS
import com.roco.catcher.model.LOW_SPEED_WARM_UP_MILLIS
import com.roco.catcher.model.LowSpeedKind
import com.roco.catcher.model.LowSpeedState
import com.roco.catcher.model.RatePoint
import com.roco.catcher.model.TaskStatus
import com.roco.catcher.monitor.RateCalculator
import com.roco.catcher.notification.NotificationChannels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
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

private fun Modifier.clearFocusOnTapOutsideInputs(
    focusManager: FocusManager,
    inputFieldBounds: Map<String, Rect>,
): Modifier = pointerInput(inputFieldBounds) {
    detectTapGestures { offset ->
        val tappedInput = inputFieldBounds.values.any { bounds -> bounds.contains(offset) }
        if (!tappedInput) {
            focusManager.clearFocus()
        }
    }
}

private fun Modifier.trackInputFieldBounds(
    inputFieldBounds: MutableMap<String, Rect>,
    key: String,
): Modifier = onGloballyPositioned { coordinates ->
    inputFieldBounds[key] = coordinates.boundsInRoot()
}

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
    fun saveSettings(helperIp: String, helperPortText: String, targetNotifyEnabled: Boolean, lowSpeedNotifyEnabled: Boolean)
    fun testConnection(helperIp: String, helperPortText: String, targetNotifyEnabled: Boolean, lowSpeedNotifyEnabled: Boolean)
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
    val focusManager = LocalFocusManager.current
    val inputFieldBounds = remember { mutableMapOf<String, Rect>() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.clearMessage()
        }
    }

    LaunchedEffect(selectedTab) {
        inputFieldBounds.clear()
    }

    Scaffold(
        modifier = Modifier.clearFocusOnTapOutsideInputs(focusManager, inputFieldBounds),
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
                    inputFieldBounds = inputFieldBounds,
                )
                AppTab.Settings -> SettingsScreen(
                    uiState = uiState,
                    actions = actions,
                    inputFieldBounds = inputFieldBounds,
                )
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
    inputFieldBounds: MutableMap<String, Rect>,
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
            UserPicker(uiState, actions, inputFieldBounds)
            Spacer(Modifier.height(10.dp))
            TargetSearchDropdown(uiState, actions, inputFieldBounds)
            Spacer(Modifier.height(10.dp))
            LabeledField("目标数量") {
                OutlinedTextField(
                    value = uiState.targetCountText,
                    onValueChange = actions::updateTargetCount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .trackInputFieldBounds(inputFieldBounds, "target_count")
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
                        .trackInputFieldBounds(inputFieldBounds, "min_rate")
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
            MetricRow("平均速率", "${formatRate(averageRate)}/分钟")
            MetricRow("当前速率", "${formatRate(currentRate)}/分钟")
            MetricRow("预计时间", estimatedCompletionLabel(targetCount, state.caughtCount, currentRate, uiState.clockMillis))
            MetricRow("低速提醒", lowSpeedLabel(state.lowSpeedState, effectiveRunMillis))
        }

        SectionCard(title = "历史速率") {
            RateLineChart(
                points = state.rateHistory,
                effectiveRunMillis = effectiveRunMillis,
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
private fun UserPicker(
    uiState: MainUiState,
    actions: MainActions,
    inputFieldBounds: MutableMap<String, Rect>,
) {
    UserDropdown(
        users = uiState.users,
        selectedUser = uiState.selectedUser,
        loadingUsers = uiState.loadingUsers,
        onRequestLoad = actions::loadUsers,
        onSelect = actions::selectUser,
        inputFieldBounds = inputFieldBounds,
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
    inputFieldBounds: MutableMap<String, Rect>,
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
                    .trackInputFieldBounds(inputFieldBounds, "user_picker")
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
private fun TargetSearchDropdown(
    uiState: MainUiState,
    actions: MainActions,
    inputFieldBounds: MutableMap<String, Rect>,
) {
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
                    .trackInputFieldBounds(inputFieldBounds, "target_search")
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
            color = TextMuted,
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
    unfocusedBorderColor = ControlInk,
    disabledBorderColor = ControlInk,
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLabelColor = SoftCream,
    unfocusedLabelColor = SoftCream,
    disabledLabelColor = SoftCream,
    errorLabelColor = SoftCream,
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
private fun SettingsScreen(
    uiState: MainUiState,
    actions: MainActions,
    inputFieldBounds: MutableMap<String, Rect>,
) {
    var ip by rememberSaveable { mutableStateOf(uiState.settings.helperIp) }
    var port by rememberSaveable { mutableStateOf(uiState.settings.helperPort?.toString().orEmpty()) }
    var targetNotifyEnabled by rememberSaveable { mutableStateOf(uiState.settings.targetNotifyEnabled) }
    var lowSpeedNotifyEnabled by rememberSaveable { mutableStateOf(uiState.settings.lowSpeedNotifyEnabled) }
    val context = LocalContext.current
    val notificationGranted = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    LaunchedEffect(uiState.settings) {
        ip = uiState.settings.helperIp
        port = uiState.settings.helperPort?.toString().orEmpty()
        targetNotifyEnabled = uiState.settings.targetNotifyEnabled
        lowSpeedNotifyEnabled = uiState.settings.lowSpeedNotifyEnabled
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "设置") {
            LabeledField("洛克助手IP地址") {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .trackInputFieldBounds(inputFieldBounds, "helper_ip")
                        .semantics { contentDescription = "IP 地址" },
                    placeholder = { Text("请输入洛克助手 IP") },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LabeledField("洛克助手端口") {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .trackInputFieldBounds(inputFieldBounds, "helper_port")
                        .semantics { contentDescription = "端口" },
                    placeholder = { Text("请输入端口") },
                    leadingIcon = { Icon(Icons.Filled.Numbers, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    colors = appTextFieldColors(),
                )
            }
            Spacer(Modifier.height(10.dp))
            NotificationSettingField(
                label = "目标达成提醒",
                checked = targetNotifyEnabled,
                onCheckedChange = { targetNotifyEnabled = it },
                onOpenSoundSettings = {
                    openNotificationChannelSettings(context, NotificationChannels.TARGET_REACHED)
                },
            )
            Spacer(Modifier.height(10.dp))
            NotificationSettingField(
                label = "低速提醒",
                checked = lowSpeedNotifyEnabled,
                onCheckedChange = { lowSpeedNotifyEnabled = it },
                onOpenSoundSettings = {
                    openNotificationChannelSettings(context, NotificationChannels.LOW_SPEED)
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.saveSettings(ip, port, targetNotifyEnabled, lowSpeedNotifyEnabled) },
                    modifier = Modifier.weight(1f),
                    colors = appPrimaryButtonColors(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("保存")
                }
                OutlinedButton(
                    onClick = { actions.testConnection(ip, port, targetNotifyEnabled, lowSpeedNotifyEnabled) },
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
private fun NotificationSettingField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenSoundSettings: () -> Unit,
) {
    LabeledField(label) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(ControlInk, RoundedCornerShape(22.dp))
                .padding(start = 16.dp, end = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable(onClick = onOpenSoundSettings)
                    .semantics { contentDescription = "$label 声音设置" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = SoftCream,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "声音设置",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = label },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SuccessGreen,
                    checkedBorderColor = SuccessGreen,
                    uncheckedThumbColor = SoftCream,
                    uncheckedTrackColor = Color(0xFF6F675F),
                    uncheckedBorderColor = Color(0xFF6F675F),
                ),
            )
        }
    }
}

private fun openNotificationChannelSettings(context: Context, channelId: String) {
    NotificationChannels.ensure(context)
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    val fallbackIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    runCatching {
        context.startActivity(channelIntent)
    }.onFailure {
        runCatching {
            context.startActivity(fallbackIntent)
        }.onFailure {
            Toast.makeText(context, "无法打开通知设置", Toast.LENGTH_SHORT).show()
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
        Text(
            text = value,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            color = Ink,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun WarningText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RateLineChart(
    points: List<RatePoint>,
    effectiveRunMillis: Long,
    modifier: Modifier = Modifier,
) {
    val lineColor = WarmAmberDeep
    val dotColor = SuccessGreen
    val axisColor = MutedLine
    val textColor = TextMuted
    val windowEndMillis = effectiveRunMillis.coerceAtLeast(points.lastOrNull()?.displayTimeMillis ?: 0L)
    val windowStartMillis = (windowEndMillis - RATE_CHART_WINDOW_MILLIS).coerceAtLeast(0L)
    val windowDurationMillis = (windowEndMillis - windowStartMillis).coerceAtLeast(1L)
    val visible = points.filter { it.displayTimeMillis in windowStartMillis..windowEndMillis }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val top = 16.dp.toPx()
            val right = size.width - 14.dp.toPx()
            val bottom = size.height - 40.dp.toPx()
            val tickSize = 4.dp.toPx()
            val labelGap = 8.dp.toPx()
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor.toArgb()
                textSize = 11.dp.toPx()
            }
            val maxRate = chartMaxRate(visible.maxOfOrNull { it.ratePerMinute } ?: 0.0)
            val rateTicks = listOf(0.0, maxRate / 2.0, maxRate)
            val widestRateLabel = rateTicks.maxOf { textPaint.measureText(formatChartRateTick(it)) }
            val left = max(24.dp.toPx(), widestRateLabel + labelGap + 2.dp.toPx())
            val gridColor = axisColor.copy(alpha = 0.45f)

            drawLine(axisColor, Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

            rateTicks.forEach { tick ->
                val y = bottom - ((tick / maxRate).toFloat() * (bottom - top))
                if (tick > 0.0) {
                    drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
                drawLine(axisColor, Offset(left - tickSize, y), Offset(left, y), strokeWidth = 2f)
                textPaint.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartRateTick(tick),
                    left - labelGap,
                    y + textPaint.textSize / 3f,
                    textPaint,
                )
            }

            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val x = left + (right - left) * fraction
                if (fraction > 0f && fraction < 1f) {
                    drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                }
                drawLine(axisColor, Offset(x, bottom), Offset(x, bottom + tickSize), strokeWidth = 2f)
                textPaint.textAlign = when (fraction) {
                    0f -> Paint.Align.LEFT
                    1f -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    formatChartMinuteTick(windowDurationMillis, fraction),
                    x,
                    bottom + 22.dp.toPx(),
                    textPaint,
                )
            }

            val path = Path()
            visible.forEachIndexed { index, point ->
                val fraction = ((point.displayTimeMillis - windowStartMillis).toFloat() / windowDurationMillis)
                    .coerceIn(0f, 1f)
                val x = left + (right - left) * fraction
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
        if (visible.isEmpty()) {
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

private fun estimatedCompletionLabel(
    targetCount: Int,
    caughtCount: Int,
    currentRate: Double,
    nowMillis: Long,
): String {
    val remaining = (targetCount - caughtCount).coerceAtLeast(0)
    if (remaining == 0) return "已达成"
    if (currentRate <= 0.0) return "无法估算"

    val remainingMillis = ceil(remaining / currentRate * 60_000.0).toLong().coerceAtLeast(1_000L)
    val estimatedAtMillis = nowMillis + remainingMillis
    val timePattern = if (remainingMillis >= 24L * 60L * 60L * 1000L) "MM-dd HH:mm" else "HH:mm"
    val estimatedAt = SimpleDateFormat(timePattern, Locale.CHINA).format(Date(estimatedAtMillis))
    return "$estimatedAt（${formatEstimatedRemaining(remainingMillis)}）"
}

private fun formatEstimatedRemaining(millis: Long): String {
    val totalSeconds = ceil(millis / 1000.0).toLong().coerceAtLeast(1L)
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0 -> "约${days}天${hours}小时"
        hours > 0 -> "约${hours}小时${minutes}分"
        minutes > 0 -> "约${minutes}分"
        else -> "约${seconds}秒"
    }
}

private fun chartMaxRate(value: Double): Double {
    val rate = max(1.0, value)
    val step = when {
        rate <= 5.0 -> 1.0
        rate <= 20.0 -> 5.0
        rate <= 50.0 -> 10.0
        else -> 25.0
    }
    return ceil(rate / step) * step
}

private fun formatChartRateTick(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

private const val RATE_CHART_WINDOW_MILLIS = 30L * 60_000L

private fun formatChartMinuteTick(windowDurationMillis: Long, fraction: Float): String {
    val minutes = (windowDurationMillis * fraction / 60_000f).toInt()
    return minutes.coerceIn(0, 30).toString()
}

private fun lowSpeedLabel(state: LowSpeedState, effectiveRunMillis: Long): String {
    return when (state.kind) {
        LowSpeedKind.Disabled -> "关闭"
        LowSpeedKind.WarmingUp -> lowSpeedCountdownLabel(
            label = "预热中",
            expiredLabel = "即将结束",
            durationMillis = LOW_SPEED_WARM_UP_MILLIS,
            state = state,
            effectiveRunMillis = effectiveRunMillis,
        )
        LowSpeedKind.Normal -> "正常"
        LowSpeedKind.Pending -> lowSpeedCountdownLabel(
            label = "低速确认中",
            expiredLabel = "即将触发",
            durationMillis = LOW_SPEED_PENDING_MILLIS,
            state = state,
            effectiveRunMillis = effectiveRunMillis,
        )
        LowSpeedKind.Alerted -> "已提醒，等待恢复"
        LowSpeedKind.SuppressedAfterTargetReached -> "达标后停止"
    }
}

private fun lowSpeedCountdownLabel(
    label: String,
    expiredLabel: String,
    durationMillis: Long,
    state: LowSpeedState,
    effectiveRunMillis: Long,
): String {
    val elapsedMillis = (effectiveRunMillis - state.startedEffectiveMillis).coerceAtLeast(0L)
    val remainingMillis = (durationMillis - elapsedMillis).coerceAtLeast(0L)
    if (remainingMillis == 0L) return "$label（$expiredLabel）"

    val remainingSeconds = (remainingMillis + 999L) / 1_000L
    return "$label（剩余 ${remainingSeconds} 秒）"
}
