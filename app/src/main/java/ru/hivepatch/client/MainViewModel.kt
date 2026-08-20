package ru.hivepatch.client

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hivepatch.client.data.AppEntry
import ru.hivepatch.client.data.HiveGroup
import ru.hivepatch.client.data.InstalledApps
import ru.hivepatch.client.data.Node
import ru.hivepatch.client.data.Prefs
import ru.hivepatch.client.data.SplitMode
import ru.hivepatch.client.data.Subscription
import ru.hivepatch.client.data.normalizeSubUrl
import ru.hivepatch.client.vpn.ConnStatus
import ru.hivepatch.client.vpn.HiveVpnService
import ru.hivepatch.client.vpn.NodePicker
import ru.hivepatch.client.vpn.VpnBus
import ru.hivepatch.client.vpn.XrayCore

data class ScreenState(
    val subInput: String = "",
    val group: HiveGroup? = null,
    val busy: Boolean = false,
    val pickerOpen: Boolean = false,
    val settingsOpen: Boolean = false,
    val logsOpen: Boolean = false,
    val appsOpen: Boolean = false,
    val appsLoading: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val appQuery: String = "",
    val splitMode: String = SplitMode.ALL,
    val splitPackages: Set<String> = emptySet(),
    val hint: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(
        ScreenState(
            subInput = Prefs.subUrl.ifBlank { "" },
            splitMode = Prefs.splitMode,
            splitPackages = Prefs.splitPackages,
        ),
    )
    val ui: StateFlow<ScreenState> = _ui
    val vpn = VpnBus.state
    val logs = VpnBus.logs
    private var connectJob: Job? = null
    private var probeJob: Job? = null
    private var rebuildJob: Job? = null

    init {
        VpnBus.hydrateTraffic(Prefs.trafficBytes)
        val cached = Prefs.cachedGroup
        if (cached.isNotBlank()) {
            runCatching { HiveGroup.parse(cached) }.onSuccess { g ->
                _ui.value = _ui.value.copy(group = g)
                restoreLastNode(g)
            }
        }
        if (Prefs.subUrl.isNotBlank() && _ui.value.group == null) {
            refresh(silent = true)
        }
    }

    fun setSubInput(v: String) {
        _ui.value = _ui.value.copy(subInput = v, hint = "")
    }

    fun openPicker(v: Boolean) {
        _ui.value = _ui.value.copy(pickerOpen = v)
    }

    fun openSettings(v: Boolean) {
        _ui.value = _ui.value.copy(settingsOpen = v, hint = "", appsOpen = if (v) _ui.value.appsOpen else false)
    }

    fun openLogs(v: Boolean) {
        _ui.value = _ui.value.copy(logsOpen = v)
    }

    fun openApps(v: Boolean) {
        _ui.value = _ui.value.copy(appsOpen = v, settingsOpen = if (v) true else _ui.value.settingsOpen)
        if (v) loadApps()
    }

    fun setAppQuery(q: String) {
        _ui.value = _ui.value.copy(appQuery = q)
    }

    fun setSplitMode(mode: String) {
        Prefs.splitMode = mode
        _ui.value = _ui.value.copy(splitMode = mode)
        scheduleTunRebuild()
    }

    fun toggleApp(pkg: String) {
        val next = _ui.value.splitPackages.toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        Prefs.splitPackages = next
        _ui.value = _ui.value.copy(splitPackages = next)
        scheduleTunRebuild()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(appsLoading = true)
            val list = withContext(Dispatchers.IO) { InstalledApps.list(getApplication()) }
            _ui.value = _ui.value.copy(apps = list, appsLoading = false)
        }
    }

    private fun scheduleTunRebuild() {
        if (VpnBus.state.value.status != ConnStatus.On) return
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(800)
            val node = VpnBus.state.value.node ?: return@launch
            if (VpnBus.state.value.status != ConnStatus.On) return@launch
            HiveVpnService.rebuild(getApplication(), node)
        }
    }

    fun clearLogs() {
        VpnBus.clearLogs()
    }

    fun updateServers() {
        val url = normalizeSubUrl(_ui.value.subInput)
        if (url.isBlank()) {
            _ui.value = _ui.value.copy(hint = "вставьте ссылку подписки", settingsOpen = true)
            VpnBus.log("вставьте ссылку подписки")
            return
        }
        Prefs.subUrl = url
        _ui.value = _ui.value.copy(subInput = url)
        refresh()
    }

    fun refresh(silent: Boolean = false) {
        val url = Prefs.subUrl.ifBlank { normalizeSubUrl(_ui.value.subInput) }
        if (url.isBlank()) {
            VpnBus.log("вставьте ссылку в настройках")
            _ui.value = _ui.value.copy(settingsOpen = true, hint = "вставьте ссылку подписки")
            return
        }
        Prefs.subUrl = url
        viewModelScope.launch {
            if (!silent) _ui.value = _ui.value.copy(busy = true, hint = "обновление…")
            try {
                val group = withContext(Dispatchers.IO) { Subscription.fetch(url) }
                cacheGroup(group)
                _ui.value = _ui.value.copy(
                    group = group,
                    subInput = url,
                    hint = "загружено ${group.nodes.size} узлов",
                )
                restoreLastNode(group)
                VpnBus.log("группа ${group.nodes.size} инбаундов")
            } catch (e: Exception) {
                val msg = "подписка: ${e.message}"
                VpnBus.log(msg)
                _ui.value = _ui.value.copy(hint = msg)
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun connectAuto() {
        val group = _ui.value.group
        if (group == null) {
            refresh()
            VpnBus.log("сначала загрузите группу")
            return
        }
        connectJob?.cancel()
        probeJob?.cancel()
        connectJob = viewModelScope.launch {
            VpnBus.setStatus(ConnStatus.Checking)
            _ui.value = _ui.value.copy(busy = true)
            try {
                val last = group.nodes.find { it.id == Prefs.lastNodeId }
                if (last != null) {
                    VpnBus.log("проверка ${last.shortLabel}")
                    val ms = withContext(Dispatchers.IO) {
                        XrayCore.measureMs(last, group.probeUrl)
                    }
                    if (ms != null) {
                        VpnBus.sample(last.id, ms, true)
                        Prefs.lastNodeId = last.id
                        VpnBus.setStatus(ConnStatus.Connecting, node = last, delayMs = ms)
                        HiveVpnService.start(getApplication(), last)
                        return@launch
                    }
                    VpnBus.sample(last.id, null, false)
                    VpnBus.log("нет ${last.shortLabel}, url-test…")
                } else {
                    VpnBus.log("url-test…")
                }
                val pick = probeAll(group, sticky = true)
                Prefs.lastNodeId = pick.node.id
                VpnBus.setStatus(ConnStatus.Connecting, node = pick.node, delayMs = pick.delayMs)
                HiveVpnService.start(getApplication(), pick.node)
            } catch (e: CancellationException) {
                VpnBus.log("отмена")
                throw e
            } catch (e: Exception) {
                VpnBus.log("подбор: ${e.message}")
                VpnBus.setStatus(ConnStatus.Off, error = e.message ?: "нет узлов")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun pickFastest() {
        val group = _ui.value.group
        if (group == null) {
            openSettings(true)
            VpnBus.log("сначала загрузите группу")
            return
        }
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            VpnBus.log("выбор самого быстрого…")
            val wasOn = VpnBus.state.value.status == ConnStatus.On ||
                VpnBus.state.value.status == ConnStatus.Connecting
            try {
                val pick = probeAll(group, sticky = false)
                Prefs.lastNodeId = pick.node.id
                VpnBus.setStatus(VpnBus.state.value.status, node = pick.node, delayMs = pick.delayMs)
                VpnBus.log("самый быстрый ${pick.node.shortLabel} · ${pick.delayMs} мс")
                if (wasOn) {
                    HiveVpnService.switchNode(getApplication(), pick.node)
                }
            } catch (e: CancellationException) {
                VpnBus.log("отмена")
                throw e
            } catch (e: Exception) {
                VpnBus.log("подбор: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        probeJob?.cancel()
        probeJob = null
        rebuildJob?.cancel()
        rebuildJob = null
        HiveVpnService.stop(getApplication())
    }

    fun switchTo(node: Node) {
        val group = _ui.value.group ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(pickerOpen = false, busy = true)
            VpnBus.log("проверка ${node.shortLabel}")
            try {
                val ms = withContext(Dispatchers.IO) {
                    XrayCore.measureMs(node, group.probeUrl)
                }
                VpnBus.sample(node.id, ms, ms != null)
                if (ms == null) {
                    VpnBus.log("недоступен ${node.shortLabel}")
                    return@launch
                }
                Prefs.lastNodeId = node.id
                VpnBus.setStatus(VpnBus.state.value.status, node = node, delayMs = ms)
                if (VpnBus.state.value.status == ConnStatus.On || VpnBus.state.value.status == ConnStatus.Connecting) {
                    HiveVpnService.switchNode(getApplication(), node)
                } else {
                    HiveVpnService.start(getApplication(), node)
                }
            } catch (e: Exception) {
                VpnBus.log("узел: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(busy = false)
            }
        }
    }

    private suspend fun probeAll(group: HiveGroup, sticky: Boolean) = withContext(Dispatchers.IO) {
        VpnBus.resetHealth()
        NodePicker.pickFastest(
            nodes = group.nodes,
            probeUrl = group.probeUrl,
            preferId = if (sticky) Prefs.lastNodeId else "",
            sticky = sticky,
        ) { sample ->
            val ok = sample.delayMs != null && sample.delayMs > 0
            VpnBus.sample(sample.node.id, sample.delayMs, ok)
            VpnBus.log(
                if (ok) "ok ${sample.node.shortLabel} ${sample.delayMs} мс"
                else "нет ${sample.node.shortLabel}",
            )
        }.also { pick ->
            val alive = pick.samples.count { it.delayMs != null && it.delayMs > 0 }
            val dead = pick.samples.size - alive
            VpnBus.log("выбран ${pick.node.shortLabel} · ${pick.delayMs} мс · $alive/$dead доступно/нет")
        }
    }

    private fun restoreLastNode(group: HiveGroup) {
        if (VpnBus.state.value.node != null) return
        val last = group.nodes.find { it.id == Prefs.lastNodeId } ?: return
        VpnBus.setStatus(VpnBus.state.value.status, node = last)
    }

    private fun cacheGroup(group: HiveGroup) {
        Prefs.cachedGroup = org.json.JSONObject().apply {
            put("name", group.name)
            put("probe_url", group.probeUrl)
            put("nodes", org.json.JSONArray().also { arr ->
                group.nodes.forEach { n ->
                    arr.put(
                        org.json.JSONObject()
                            .put("id", n.id)
                            .put("node_id", n.nodeId)
                            .put("profile", n.profile)
                            .put("name", n.name)
                            .put("uri", n.uri)
                            .put("port", n.port)
                            .put("network", n.network)
                            .put("zone", n.zone),
                    )
                }
            })
        }.toString()
    }
}
