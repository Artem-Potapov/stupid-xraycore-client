package com.justme.xtls_core_proxy.killswitch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence layer for the kill-on-foreground feature. Stored in the shared
 * "xray_prefs" SharedPreferences file so it lives alongside split-tunnel prefs.
 *
 * Exposes a process-wide StateFlow so consumers (XrayVpnService, UI) can react
 * to live edits without re-reading prefs on every check.
 */
object KillSwitchRepository {
    private const val PREFS_NAME = "xray_prefs"
    private const val KEY_ENABLED = "kill_switch_enabled"
    private const val KEY_PACKAGES = "kill_switch_packages"

    data class Preferences(
        val enabled: Boolean,
        val packages: Set<String>
    )

    private val _state = MutableStateFlow(Preferences(enabled = false, packages = emptySet()))
    val state: StateFlow<Preferences> = _state.asStateFlow()

    fun load(context: Context): Preferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()
        val loaded = Preferences(enabled = enabled, packages = packages)
        _state.value = loaded
        return loaded
    }

    fun save(context: Context, enabled: Boolean, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            putStringSet(KEY_PACKAGES, HashSet(packages))
            apply()
        }
        _state.value = Preferences(enabled = enabled, packages = packages)
    }
}
