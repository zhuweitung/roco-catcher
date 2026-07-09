package com.roco.capture.notify

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.roco.capture.notify.data.EvolutionChainRepository
import com.roco.capture.notify.data.HelperApi
import com.roco.capture.notify.data.SettingsStore
import com.roco.capture.notify.model.AppSettings
import com.roco.capture.notify.model.CaptureTarget
import com.roco.capture.notify.model.CaptureTaskConfig
import com.roco.capture.notify.model.HelperUser
import com.roco.capture.notify.model.LowSpeedKind
import com.roco.capture.notify.model.NotifyMode
import com.roco.capture.notify.model.TargetSearchMode
import com.roco.capture.notify.model.TargetSearchResult
import com.roco.capture.notify.model.TaskStatus
import com.roco.capture.notify.monitor.CaptureMonitorService
import com.roco.capture.notify.monitor.CaptureTaskManager
import com.roco.capture.notify.monitor.RateCalculator
import com.roco.capture.notify.ui.RateChartView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Tab { Monitor, Target, Settings }

    private lateinit var settingsStore: SettingsStore
    private lateinit var chains: EvolutionChainRepository
    private val helperApi = HelperApi()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var settings = AppSettings()
    private var users: List<HelperUser> = emptyList()
    private var selectedUser: HelperUser? = null
    private var selectedTarget: CaptureTarget? = null
    private var targetCountText = "1"
    private var minRateText = "0"
    private var targetQuery = ""
    private var targetMode = TargetSearchMode.Chain
    private var currentTab = Tab.Monitor
    private var loadingUsers = false
    private var statusMessage: String? = null
    private var unsubscribeState: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        chains = EvolutionChainRepository(this)
        settings = settingsStore.load()
        targetCountText = settingsStore.recentTargetCount().toString()
        minRateText = formatNumber(settingsStore.recentMinRate())
        restoreRecentTarget()
        requestNotificationPermissionIfNeeded()

        unsubscribeState = CaptureTaskManager.addListener {
            runOnUiThread {
                if (currentTab == Tab.Monitor) render()
            }
        }
        render()
    }

    override fun onDestroy() {
        unsubscribeState?.invoke()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun restoreRecentTarget() {
        val recentName = settingsStore.recentTargetName() ?: return
        selectedTarget = chains.search(recentName, TargetSearchMode.Chain)
            .firstOrNull { it.target.displayName == recentName }
            ?.target
            ?: chains.search(recentName, TargetSearchMode.SinglePet)
                .firstOrNull { it.target.displayName == recentName }
                ?.target
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        addHeader(root)
        when (currentTab) {
            Tab.Monitor -> renderMonitor(root)
            Tab.Target -> renderTargetPicker(root)
            Tab.Settings -> renderSettings(root)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun addHeader(parent: LinearLayout) {
        parent.addView(
            TextView(this).apply {
                text = "洛克捕获提醒"
                textSize = 24f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            },
        )
        parent.addView(
            TextView(this).apply {
                text = "监听捕获事件，按目标数量和速率发送系统提醒"
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(4), 0, dp(12))
            },
        )

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tabs.addView(tabButton("监控", Tab.Monitor), weightParams())
        tabs.addView(tabButton("目标", Tab.Target), weightParams())
        tabs.addView(tabButton("设置", Tab.Settings), weightParams())
        parent.addView(tabs)
        parent.addSpacer(12)
    }

    private fun tabButton(label: String, tab: Tab): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(44)
            setTextColor(if (currentTab == tab) Color.WHITE else COLOR_TEXT)
            background = rounded(if (currentTab == tab) COLOR_PRIMARY else Color.WHITE, dp(8), COLOR_BORDER)
            setOnClickListener {
                currentTab = tab
                render()
            }
        }
    }

    private fun renderMonitor(parent: LinearLayout) {
        val state = CaptureTaskManager.currentState()
        val effectiveRunMillis = CaptureTaskManager.effectiveRunMillis()
        val averageRate = CaptureTaskManager.averageRate()
        val currentRate = CaptureTaskManager.currentRate()

        if (!settings.hasEndpoint) {
            parent.addView(
                card("需要配置") {
                    addBodyText("请先在设置页填写洛克助手 IP 和端口。")
                    addPrimaryButton("去设置") {
                        currentTab = Tab.Settings
                        render()
                    }
                },
            )
            return
        }

        parent.addView(
            card("任务状态") {
                addMetricRow("状态", state.status.label)
                addMetricRow("用户", selectedUser?.toString() ?: state.config?.user?.toString() ?: "未选择")
                addMetricRow("目标", selectedTarget?.displayName ?: state.config?.target?.displayName ?: "未选择")
                addMetricRow("运行时长", formatDuration(effectiveRunMillis))
                state.errorMessage?.let { addWarningText(it) }
                statusMessage?.let { addBodyText(it) }
            },
        )

        parent.addView(
            card("任务配置") {
                addUsersSection(this)
                addTargetSection(this)
                addInput("目标数量", targetCountText, InputType.TYPE_CLASS_NUMBER) {
                    targetCountText = it
                }
                addInput(
                    "最低速率，只/分钟；0 表示关闭",
                    minRateText,
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
                ) {
                    minRateText = it
                }
                addActionButtons(this, state.status)
            },
        )

        parent.addView(
            card("捕获统计") {
                val targetCount = state.config?.targetCount ?: targetCountText.toIntOrNull() ?: 1
                addMetricRow("当前捕获", "${state.caughtCount}/$targetCount")
                addMetricRow("平均速率", "${formatRate(averageRate)} / 分钟")
                addMetricRow("当前速率", "${formatRate(currentRate)} / 分钟")
                addMetricRow("低速提醒", lowSpeedLabel(state.lowSpeedState.kind))
            },
        )

        parent.addView(
            card("历史速率") {
                val chart = RateChartView(this@MainActivity).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setPoints(state.rateHistory)
                }
                addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            },
        )

        parent.addView(
            card("最近捕获") {
                if (state.caughtEvents.isEmpty()) {
                    addBodyText("暂无捕获记录")
                } else {
                    state.caughtEvents.takeLast(10).asReversed().forEach { event ->
                        addBodyText(
                            "${timeFormat.format(Date(event.caughtAtMillis))}  " +
                                "${event.petName ?: event.baseConfId}  gid=${event.gid}",
                        )
                    }
                }
            },
        )
    }

    private fun addUsersSection(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            Button(this).apply {
                text = if (loadingUsers) "读取中" else "读取用户"
                isAllCaps = false
                isEnabled = !loadingUsers
                minHeight = dp(44)
                setOnClickListener { loadUsers() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f),
        )

        val spinner = Spinner(this)
        val userLabels = if (users.isEmpty()) listOf("未读取用户") else users.map { it.toString() }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, userLabels)
        val selectedIndex = users.indexOfFirst { it.uid == selectedUser?.uid }.takeIf { it >= 0 } ?: 0
        if (users.isNotEmpty()) spinner.setSelection(selectedIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedUser = users.getOrNull(position)
            }

            override fun onNothingSelected(parentView: AdapterView<*>?) = Unit
        }
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f))
        parent.addView(row)
        parent.addSpacer(10)
    }

    private fun addTargetSection(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = selectedTarget?.displayName ?: "未选择捕获目标"
                textSize = 15f
                setTextColor(COLOR_TEXT)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this).apply {
                text = "选择"
                isAllCaps = false
                minHeight = dp(44)
                setOnClickListener {
                    currentTab = Tab.Target
                    render()
                }
            },
        )
        parent.addView(row)
        parent.addSpacer(10)
    }

    private fun addActionButtons(parent: LinearLayout, status: TaskStatus) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when (status) {
            TaskStatus.Idle -> {
                row.addView(actionButton("开始监控") { startTask(restart = true) }, weightParams())
            }
            TaskStatus.Connecting, TaskStatus.Running, TaskStatus.Reconnecting -> {
                row.addView(actionButton("暂停") { pauseTask() }, weightParams())
            }
            TaskStatus.Paused -> {
                row.addView(actionButton("继续当前任务") { continueTask() }, weightParams())
                row.addView(actionButton("重新开始任务") { startTask(restart = true) }, weightParams())
            }
            TaskStatus.Failed -> {
                row.addView(actionButton("重试") { retryTask() }, weightParams())
                row.addView(actionButton("重新开始任务") { startTask(restart = true) }, weightParams())
            }
        }
        parent.addView(row)
    }

    private fun renderTargetPicker(parent: LinearLayout) {
        parent.addView(
            card("选择目标") {
                val search = EditText(this@MainActivity).apply {
                    hint = "搜索进化链名称、精灵名称或 ID"
                    setSingleLine(true)
                    setText(targetQuery)
                    setSelection(text.length)
                }
                addView(search, matchWrapParams())
                addSpacer(10)

                val group = RadioGroup(this@MainActivity).apply {
                    orientation = RadioGroup.HORIZONTAL
                }
                val chainId = View.generateViewId()
                val singleId = View.generateViewId()
                group.addView(radioButton(chainId, "进化链"))
                group.addView(radioButton(singleId, "单一精灵"))
                group.check(if (targetMode == TargetSearchMode.Chain) chainId else singleId)
                addView(group)
                addSpacer(10)

                val listView = ListView(this@MainActivity).apply {
                    dividerHeight = 1
                    minimumHeight = dp(360)
                }
                addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(480)))

                fun updateResults() {
                    val results = targetResults()
                    val labels = results.map { "${it.title}\n${it.subtitle}" }
                    listView.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        labels,
                    )
                    listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                        selectTarget(results[position])
                    }
                }

                search.addTextChangedListener(simpleWatcher {
                    targetQuery = it
                    updateResults()
                })
                group.setOnCheckedChangeListener { _, checkedId ->
                    targetMode = if (checkedId == chainId) TargetSearchMode.Chain else TargetSearchMode.SinglePet
                    updateResults()
                }
                updateResults()
            },
        )
    }

    private fun renderSettings(parent: LinearLayout) {
        var ipText = settings.helperIp
        var portText = settings.helperPort?.toString().orEmpty()
        var targetMode = settings.targetNotifyMode
        var lowMode = settings.lowSpeedNotifyMode

        parent.addView(
            card("洛克助手连接") {
                addInput("IP 地址", ipText, InputType.TYPE_CLASS_TEXT) { ipText = it }
                addInput("端口", portText, InputType.TYPE_CLASS_NUMBER) { portText = it }
                addModeSpinner("目标达成通知", targetMode) { targetMode = it }
                addModeSpinner("低速提醒通知", lowMode) { lowMode = it }

                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(
                    actionButton("保存") {
                        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                        if (ipText.isBlank() || port == null) {
                            toast("请填写有效 IP 和端口")
                            return@actionButton
                        }
                        settings = AppSettings(
                            helperIp = ipText.trim(),
                            helperPort = port,
                            targetNotifyMode = targetMode,
                            lowSpeedNotifyMode = lowMode,
                        )
                        settingsStore.save(settings)
                        statusMessage = "设置已保存"
                        toast("设置已保存")
                        render()
                    },
                    weightParams(),
                )
                row.addView(
                    actionButton("测试连接") {
                        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                        if (ipText.isBlank() || port == null) {
                            toast("请填写有效 IP 和端口")
                            return@actionButton
                        }
                        settings = AppSettings(
                            helperIp = ipText.trim(),
                            helperPort = port,
                            targetNotifyMode = targetMode,
                            lowSpeedNotifyMode = lowMode,
                        )
                        testConnection(settings)
                    },
                    weightParams(),
                )
                addView(row)

                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    addWarningText("通知权限未开启，目标达成和低速提醒可能无法显示。")
                    addPrimaryButton("请求通知权限") { requestNotificationPermissionIfNeeded() }
                }
            },
        )
    }

    private fun startTask(restart: Boolean) {
        val config = buildTaskConfig() ?: return
        settingsStore.saveRecentTask(
            targetCount = config.targetCount,
            minRate = config.minRatePerMinute,
            uid = config.user.uid,
            targetName = config.target.displayName,
        )
        targetCountText = config.targetCount.toString()
        minRateText = formatNumber(config.minRatePerMinute)
        if (restart) {
            CaptureTaskManager.startNewTask(config)
        }
        CaptureMonitorService.start(this)
        statusMessage = null
        render()
    }

    private fun continueTask() {
        CaptureTaskManager.continueCurrentTask()
        CaptureMonitorService.start(this)
        render()
    }

    private fun retryTask() {
        CaptureTaskManager.markConnecting()
        CaptureMonitorService.start(this)
        render()
    }

    private fun pauseTask() {
        CaptureTaskManager.pauseByUser()
        CaptureMonitorService.stop(this)
        render()
    }

    private fun buildTaskConfig(): CaptureTaskConfig? {
        if (!settings.hasEndpoint) {
            toast("请先配置 IP 和端口")
            currentTab = Tab.Settings
            render()
            return null
        }
        val user = selectedUser ?: CaptureTaskManager.currentState().config?.user
        if (user == null) {
            toast("请先读取并选择用户")
            return null
        }
        val target = selectedTarget ?: CaptureTaskManager.currentState().config?.target
        if (target == null) {
            toast("请先选择捕获目标")
            currentTab = Tab.Target
            render()
            return null
        }
        val targetCount = targetCountText.toIntOrNull()?.takeIf { it > 0 }
        if (targetCount == null) {
            toast("目标数量必须大于 0")
            return null
        }
        val minRate = minRateText.toDoubleOrNull()?.takeIf { it >= 0.0 }
        if (minRate == null) {
            toast("最低速率必须大于等于 0")
            return null
        }
        return CaptureTaskConfig(user, target, targetCount, minRate)
    }

    private fun loadUsers() {
        if (!settings.hasEndpoint) {
            toast("请先配置 IP 和端口")
            currentTab = Tab.Settings
            render()
            return
        }
        loadingUsers = true
        statusMessage = "正在读取用户..."
        render()
        executor.execute {
            runCatching { helperApi.fetchUsers(settings) }
                .onSuccess { loaded ->
                    runOnUiThread {
                        users = loaded
                        selectedUser = loaded.firstOrNull { it.uid == settingsStore.recentUid() }
                            ?: loaded.firstOrNull()
                        loadingUsers = false
                        statusMessage = "读取到 ${loaded.size} 个用户"
                        render()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        loadingUsers = false
                        statusMessage = error.message ?: "读取用户失败"
                        toast(statusMessage.orEmpty())
                        render()
                    }
                }
        }
    }

    private fun testConnection(candidate: AppSettings) {
        statusMessage = "正在测试连接..."
        render()
        executor.execute {
            runCatching { helperApi.fetchUsers(candidate) }
                .onSuccess { loaded ->
                    runOnUiThread {
                        settings = candidate
                        settingsStore.save(candidate)
                        users = loaded
                        selectedUser = loaded.firstOrNull()
                        statusMessage = "连接成功，读取到 ${loaded.size} 个用户"
                        toast("连接成功")
                        render()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        statusMessage = error.message ?: "连接失败"
                        toast(statusMessage.orEmpty())
                        render()
                    }
                }
        }
    }

    private fun targetResults(): List<TargetSearchResult> {
        return chains.search(targetQuery, targetMode)
    }

    private fun selectTarget(result: TargetSearchResult) {
        selectedTarget = result.target
        targetMode = result.mode
        statusMessage = "已选择 ${result.target.displayName}"
        currentTab = Tab.Monitor
        render()
    }

    private fun card(title: String, content: LinearLayout.() -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(8), COLOR_BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(COLOR_TEXT)
                    setPadding(0, 0, 0, dp(10))
                },
            )
            content()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun LinearLayout.addInput(
        label: String,
        value: String,
        inputType: Int,
        onChange: (String) -> Unit,
    ) {
        addView(
            TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(COLOR_MUTED)
            },
        )
        addView(
            EditText(this@MainActivity).apply {
                setText(value)
                setSingleLine(true)
                this.inputType = inputType
                background = rounded(Color.rgb(248, 250, 252), dp(8), COLOR_BORDER)
                setPadding(dp(10), 0, dp(10), 0)
                minHeight = dp(48)
                addTextChangedListener(simpleWatcher(onChange))
            },
            matchWrapParams(),
        )
        addSpacer(10)
    }

    private fun LinearLayout.addModeSpinner(
        label: String,
        selected: NotifyMode,
        onChange: (NotifyMode) -> Unit,
    ) {
        addView(
            TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(COLOR_MUTED)
            },
        )
        val spinner = Spinner(this@MainActivity)
        val modes = NotifyMode.entries
        spinner.adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label },
        )
        spinner.setSelection(modes.indexOf(selected).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onChange(modes[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(spinner, matchWrapParams())
        addSpacer(10)
    }

    private fun LinearLayout.addMetricRow(label: String, value: String) {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(
            TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(COLOR_MUTED)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f),
        )
        row.addView(
            TextView(this@MainActivity).apply {
                text = value
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f),
        )
        addView(row)
    }

    private fun LinearLayout.addBodyText(textValue: String) {
        addView(
            TextView(this@MainActivity).apply {
                text = textValue
                textSize = 14f
                setTextColor(COLOR_TEXT)
                setPadding(0, dp(2), 0, dp(2))
            },
        )
    }

    private fun LinearLayout.addWarningText(textValue: String) {
        addView(
            TextView(this@MainActivity).apply {
                text = textValue
                textSize = 14f
                setTextColor(Color.rgb(185, 28, 28))
                setPadding(0, dp(4), 0, dp(4))
            },
        )
    }

    private fun LinearLayout.addPrimaryButton(label: String, onClick: () -> Unit) {
        addView(actionButton(label, onClick), matchWrapParams())
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            background = rounded(COLOR_PRIMARY, dp(8), COLOR_PRIMARY)
            setOnClickListener { onClick() }
        }
    }

    private fun radioButton(idValue: Int, label: String): RadioButton {
        return RadioButton(this).apply {
            id = idValue
            text = label
            textSize = 15f
            setTextColor(COLOR_TEXT)
            minHeight = dp(44)
        }
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString().orEmpty())
            }
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(1, stroke)
        }
    }

    private fun matchWrapParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun LinearLayout.addSpacer(heightDp: Int) {
        addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "${minutes}分${seconds.toString().padStart(2, '0')}秒"
    }

    private fun formatRate(value: Double): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else formatRate(value)
    }

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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val COLOR_BACKGROUND = 0xFFF8FAFC.toInt()
        const val COLOR_TEXT = 0xFF1E293B.toInt()
        const val COLOR_MUTED = 0xFF64748B.toInt()
        const val COLOR_BORDER = 0xFFE2E8F0.toInt()
        const val COLOR_PRIMARY = 0xFF3B82F6.toInt()
    }
}
