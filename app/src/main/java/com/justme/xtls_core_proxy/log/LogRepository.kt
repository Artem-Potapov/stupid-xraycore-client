package com.justme.xtls_core_proxy.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object LogRepository {
    private const val MAX_LINES = 500

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _connectionState = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val connectionState: StateFlow<VpnConnectionState> = _connectionState

    fun append(line: String) {
        val timestamp = timeFormatter.format(Date())
        val sanitized = sanitize(line)
        _logs.update { prev ->
            (prev + "[$timestamp] $sanitized").takeLast(MAX_LINES)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun setConnectionState(newState: VpnConnectionState) {
        _connectionState.value = newState
    }

    private fun sanitize(raw: String): String {
        return raw
            .replace(Regex("""([0-9a-fA-F]{8}-[0-9a-fA-F-]{27})"""), "<redacted-uuid>")
            .replace(Regex("""("publicKey"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
            .replace(Regex("""("shortId"\s*:\s*")[^"]+(")"""), "$1<redacted>$2")
    }
}
