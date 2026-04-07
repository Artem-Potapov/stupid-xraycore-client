package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val logs = LogRepository.logs
    val connectionState = LogRepository.connectionState

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    fun clearError() {
        _error.value = null
    }

    fun connect(context: Context): Boolean {
        val runtimeConfig = try {
            ConfigBuilder.buildRuntimeConfig(_inputText.value)
        } catch (error: IllegalArgumentException) {
            LogRepository.setConnectionState(VpnConnectionState.ERROR)
            LogRepository.append("Invalid configuration: ${error.message}")
            _error.value = error.message ?: "Invalid configuration"
            return false
        } catch (error: Exception) {
            LogRepository.setConnectionState(VpnConnectionState.ERROR)
            LogRepository.append("Configuration failure: ${error.message}")
            _error.value = error.message ?: "Configuration failure"
            return false
        }

        _error.value = null
        val appContext = context.applicationContext
        val startIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG, runtimeConfig)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(startIntent)
        } else {
            appContext.startService(startIntent)
        }
        return true
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        val stopIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
        }
        appContext.startService(stopIntent)
    }
}
