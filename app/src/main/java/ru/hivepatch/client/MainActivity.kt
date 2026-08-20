package ru.hivepatch.client

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import ru.hivepatch.client.data.AppEntry
import ru.hivepatch.client.data.Node
import ru.hivepatch.client.data.SplitMode
import ru.hivepatch.client.vpn.ConnStatus
import ru.hivepatch.client.vpn.VpnUiState
import ru.hivepatch.client.vpn.formatBytes

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pendingConnect = false

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && pendingConnect) {
            pendingConnect = false
            vm.connectAuto()
        } else {
            pendingConnect = false
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val screen by vm.ui.collectAsState()
            val vpn by vm.vpn.collectAsState()
            val logs by vm.logs.collectAsState()
            HiveTheme {
                HomeScreen(
                    screen = screen,
                    vpn = vpn,
                    logs = logs,
                    onToggle = { toggle(vpn.status) },
                    onOpenPicker = { vm.openPicker(true) },
                    onClosePicker = { vm.openPicker(false) },
                    onPick = { vm.switchTo(it) },
                    onOpenSettings = { vm.openSettings(true) },
                    onCloseSettings = { vm.openSettings(false) },
                    onOpenLogs = { vm.openLogs(true) },
                    onCloseLogs = { vm.openLogs(false) },
                    onClearLogs = vm::clearLogs,
                    onSubChange = vm::setSubInput,
                    onUpdateServers = vm::updateServers,
                    onPickFastest = vm::pickFastest,
                    onOpenApps = { vm.openApps(true) },
                    onCloseApps = { vm.openApps(false) },
                    onAppQuery = vm::setAppQuery,
                    onSplitMode = vm::setSplitMode,
                    onToggleApp = vm::toggleApp,
                )
            }
        }
    }

    private fun toggle(status: ConnStatus) {
        when (status) {
            ConnStatus.On, ConnStatus.Connecting, ConnStatus.Checking -> vm.disconnect()
            else -> {
                val prep = VpnService.prepare(this)
                if (prep != null) {
                    pendingConnect = true
                    vpnConsent.launch(prep)
                } else {
                    vm.connectAuto()
                }
            }
        }
    }
}

private val Bg = Color(0xFF0B1220)
private val Card = Color(0xFF151C2C)
private val Green = Color(0xFF34D399)
private val Red = Color(0xFFF87171)
private val Mute = Color(0xFF94A3B8)
private val TextC = Color(0xFFE2E8F0)

@Composable
private fun HiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Card,
            primary = Green,
            onPrimary = Bg,
            onBackground = TextC,
            onSurface = TextC,
        ),
        content = content,
    )
}

@Composable
private fun HomeScreen(
    screen: ScreenState,
    vpn: VpnUiState,
    logs: List<String>,
    onToggle: () -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onPick: (Node) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onCloseLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onSubChange: (String) -> Unit,
    onUpdateServers: () -> Unit,
    onPickFastest: () -> Unit,
    onOpenApps: () -> Unit,
    onCloseApps: () -> Unit,
    onAppQuery: (String) -> Unit,
    onSplitMode: (String) -> Unit,
    onToggleApp: (String) -> Unit,
) {
    val on = vpn.status == ConnStatus.On
    val working = vpn.status == ConnStatus.Checking || vpn.status == ConnStatus.Connecting || screen.busy
    val overlay = screen.settingsOpen || screen.logsOpen || screen.pickerOpen || screen.appsOpen || screen.group == null
    BackHandler(enabled = overlay) {
        when {
            screen.appsOpen -> onCloseApps()
            screen.pickerOpen -> onClosePicker()
            screen.logsOpen -> onCloseLogs()
            screen.settingsOpen && screen.group != null -> onCloseSettings()
        }
    }
    Surface(Modifier.fillMaxSize(), color = Bg) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("HivePatch", color = Green, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(screen.group?.name ?: "группа не загружена", color = Mute, fontSize = 13.sp)
                }
                TextButton(onClick = onOpenLogs) { Text("Лог", color = Mute) }
                TextButton(onClick = onOpenSettings) { Text("Настройки", color = Mute) }
            }
            Spacer(Modifier.height(32.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val color = when {
                    on -> Green
                    working -> Color(0xFFFBBF24)
                    else -> Color(0xFF334155)
                }
                Box(
                    Modifier
                        .size(148.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable(enabled = !working || on) { onToggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (vpn.status) {
                            ConnStatus.On -> "ВКЛ"
                            ConnStatus.Checking -> "ТЕСТ"
                            ConnStatus.Connecting -> "…"
                            ConnStatus.Stopping -> "…"
                            ConnStatus.Off -> "ВЫКЛ"
                        },
                        color = if (on) Bg else TextC,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val statusLine = when {
                screen.busy && vpn.status == ConnStatus.Off -> "проверка узлов"
                vpn.status == ConnStatus.On -> "доступно"
                vpn.status == ConnStatus.Checking -> "проверка узлов"
                vpn.status == ConnStatus.Connecting -> "подключение"
                vpn.status == ConnStatus.Stopping -> "отключение"
                else -> if (vpn.error.isNotBlank()) vpn.error else "выключено"
            }
            Text(
                statusLine,
                color = if (on) Green else Mute,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(18.dp))
            StatusCard(screen = screen, vpn = vpn, onOpenPicker = onOpenPicker)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPickFastest,
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (screen.busy) "проверка…" else "Выбрать самый быстрый сервер")
            }
        }
        if (screen.settingsOpen || screen.group == null) {
            SettingsOverlay(
                screen = screen,
                onClose = onCloseSettings,
                onSubChange = onSubChange,
                onUpdate = onUpdateServers,
                onOpenApps = onOpenApps,
                onSplitMode = onSplitMode,
            )
        }
        if (screen.appsOpen) {
            AppsOverlay(
                screen = screen,
                onClose = onCloseApps,
                onQuery = onAppQuery,
                onToggle = onToggleApp,
            )
        }
        if (screen.logsOpen) {
            LogsOverlay(logs = logs, onClose = onCloseLogs, onClear = onClearLogs)
        }
        if (screen.pickerOpen) {
            PickerOverlay(screen = screen, vpn = vpn, onClose = onClosePicker, onPick = onPick)
        }
        }
    }
}

@Composable
private fun StatusCard(screen: ScreenState, vpn: VpnUiState, onOpenPicker: () -> Unit) {
    val node = vpn.node
    val ping = vpn.delayMs ?: vpn.health[node?.id.orEmpty()]?.delayMs
    val total = screen.group?.nodes?.size ?: 0
    val alive = vpn.health.values.count { it.available == true }
    val dead = vpn.health.values.count { it.available == false }
    val probed = alive + dead
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoRow(
            label = "текущий узел",
            value = node?.shortLabel ?: "не выбран",
            onClick = if (screen.group != null) onOpenPicker else null,
        )
        InfoRow(label = "пинг", value = ping?.let { "$it мс" } ?: "—")
        InfoRow(
            label = "трафик",
            value = buildString {
                append(formatBytes(vpn.trafficBytes))
                if (vpn.status == ConnStatus.On && vpn.sessionBytes > 0) {
                    append(" · сессия ")
                    append(formatBytes(vpn.sessionBytes))
                }
            },
        )
        InfoRow(
            label = "узлы",
            value = if (probed == 0) {
                if (total == 0) "не загружены" else "не проверены · всего $total"
            } else {
                "доступно $alive · недоступно $dead"
            },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Mute, fontSize = 13.sp)
        Text(value, color = TextC, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsOverlay(
    screen: ScreenState,
    onClose: () -> Unit,
    onSubChange: (String) -> Unit,
    onUpdate: () -> Unit,
    onOpenApps: () -> Unit,
    onSplitMode: (String) -> Unit,
) {
    val n = screen.splitPackages.size
    val modeHint = when (screen.splitMode) {
        SplitMode.BYPASS -> if (n == 0) "все приложения через VPN" else "обход $n приложений"
        SplitMode.ONLY -> if (n == 0) "список пуст — весь трафик через VPN" else "через VPN только $n"
        else -> "все приложения через VPN"
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00B1220)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Настройки", color = TextC, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (screen.group != null) {
                    TextButton(onClick = onClose) { Text("закрыть", color = Mute) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("ссылка подписки", color = Mute, fontSize = 13.sp)
            Text("панель: Клиенты → URL Android HivePatch", color = Mute, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = screen.subInput,
                onValueChange = onSubChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://vma.maybe-co.ru/sub/… или токен") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextC,
                    unfocusedTextColor = TextC,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Mute,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onUpdate,
                enabled = !screen.busy,
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (screen.busy) "обновление…" else "Обновить") }
            if (screen.hint.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(screen.hint, color = if (screen.hint.startsWith("подписка")) Red else Mute, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text("приложения", color = TextC, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("HivePatch всегда вне VPN. Если VPN включён, смена применится сразу.", color = Mute, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            ModeButton("все приложения", screen.splitMode == SplitMode.ALL) { onSplitMode(SplitMode.ALL) }
            Spacer(Modifier.height(8.dp))
            ModeButton("не проксировать выбранные", screen.splitMode == SplitMode.BYPASS) { onSplitMode(SplitMode.BYPASS) }
            Spacer(Modifier.height(8.dp))
            ModeButton("проксировать только выбранные", screen.splitMode == SplitMode.ONLY) { onSplitMode(SplitMode.ONLY) }
            Spacer(Modifier.height(8.dp))
            Text(modeHint, color = Mute, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenApps,
                colors = ButtonDefaults.buttonColors(containerColor = Card, contentColor = TextC),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Выбрать приложения") }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Green else Card,
            contentColor = if (selected) Bg else TextC,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
}

@Composable
private fun AppsOverlay(
    screen: ScreenState,
    onClose: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    val q = screen.appQuery.trim()
    val filtered = remember(screen.apps, screen.appQuery, screen.splitPackages) {
        screen.apps
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedWith(
                compareByDescending<AppEntry> { it.packageName in screen.splitPackages }
                    .thenBy { it.label.lowercase() },
            )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00B1220)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Приложения", color = TextC, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                TextButton(onClick = onClose) { Text("закрыть", color = Mute) }
            }
            Text(
                when (screen.splitMode) {
                    SplitMode.BYPASS -> "отмеченные идут в обход VPN"
                    SplitMode.ONLY -> "отмеченные идут через VPN"
                    else -> "режим «все» — отметки сохранятся для других режимов"
                },
                color = Mute,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = screen.appQuery,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("поиск") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextC,
                    unfocusedTextColor = TextC,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Mute,
                ),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Card),
            ) {
                if (screen.appsLoading && screen.apps.isEmpty()) {
                    item { Text("загрузка…", color = Mute, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                } else if (filtered.isEmpty()) {
                    item { Text("нет приложений", color = Mute, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                }
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in screen.splitPackages
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(app.packageName) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(app.label, color = TextC, fontSize = 14.sp)
                            Text(
                                if (app.system) "${app.packageName} · системное" else app.packageName,
                                color = Mute,
                                fontSize = 11.sp,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Green,
                                uncheckedColor = Mute,
                                checkmarkColor = Bg,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val image = remember(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap()
        }.getOrNull()
    }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF334155)))
    }
}

@Composable
private fun LogsOverlay(logs: List<String>, onClose: () -> Unit, onClear: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00B1220)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Лог", color = TextC, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row {
                    TextButton(onClick = onClear) { Text("очистить", color = Mute) }
                    TextButton(onClick = onClose) { Text("закрыть", color = Mute) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Card)
                    .padding(12.dp),
            ) {
                if (logs.isEmpty()) {
                    item { Text("пусто", color = Mute, fontSize = 13.sp) }
                }
                items(logs.asReversed()) { line ->
                    Text(line, color = Mute, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PickerOverlay(
    screen: ScreenState,
    vpn: VpnUiState,
    onClose: () -> Unit,
    onPick: (Node) -> Unit,
) {
    val nodes = screen.group?.nodes.orEmpty()
    Box(Modifier.fillMaxSize().background(Color(0xF00B1220))) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Card)
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("узел", color = TextC, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("закрыть", color = Mute) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(nodes, key = { it.id }) { n ->
                    val h = vpn.health[n.id]
                    val mark = when {
                        h?.available == true -> "доступно ${h.delayMs} мс"
                        h?.available == false -> "нет"
                        else -> n.zone
                    }
                    val color = when {
                        h?.available == true -> Green
                        h?.available == false -> Red
                        else -> Mute
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(n) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(n.shortLabel, color = if (vpn.node?.id == n.id) Green else TextC, fontSize = 14.sp)
                            Text("${n.profile} · ${n.network}", color = Mute, fontSize = 11.sp)
                        }
                        Text(mark, color = color, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
