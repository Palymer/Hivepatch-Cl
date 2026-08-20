package ru.hivepatch.client.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.hivepatch.client.data.Node
import ru.hivepatch.client.data.Prefs

enum class ConnStatus { Off, Checking, Connecting, On, Stopping }

data class NodeHealth(
    val id: String,
    val delayMs: Long? = null,
    val available: Boolean? = null,
)

data class VpnUiState(
    val status: ConnStatus = ConnStatus.Off,
    val node: Node? = null,
    val delayMs: Long? = null,
    val error: String = "",
    val health: Map<String, NodeHealth> = emptyMap(),
    val trafficBytes: Long = 0,
    val sessionBytes: Long = 0,
)

fun formatBytes(n: Long): String {
    if (n < 1024) return "$n Б"
    val kb = n / 1024.0
    if (kb < 1024) return String.format("%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f МБ", mb)
    return String.format("%.2f ГБ", mb / 1024.0)
}

object VpnBus {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun hydrateTraffic(total: Long) {
        _state.update { it.copy(trafficBytes = total) }
    }

    fun resetSession() {
        _state.update { it.copy(sessionBytes = 0) }
    }

    fun addTraffic(delta: Long) {
        if (delta <= 0) return
        _state.update { cur ->
            val total = cur.trafficBytes + delta
            Prefs.trafficBytes = total
            cur.copy(trafficBytes = total, sessionBytes = cur.sessionBytes + delta)
        }
    }

    fun setStatus(status: ConnStatus, node: Node? = _state.value.node, delayMs: Long? = _state.value.delayMs, error: String = "") {
        _state.update { it.copy(status = status, node = node, delayMs = delayMs, error = error) }
    }

    fun sample(id: String, delayMs: Long?, available: Boolean) {
        _state.update { cur ->
            cur.copy(health = cur.health + (id to NodeHealth(id, delayMs, available)))
        }
    }

    fun resetHealth() {
        _state.update { it.copy(health = emptyMap()) }
    }

    fun log(line: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        _logs.update { (it + "$stamp  $line").takeLast(120) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
