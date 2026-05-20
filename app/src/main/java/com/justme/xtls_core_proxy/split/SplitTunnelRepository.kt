package com.justme.xtls_core_proxy.split

import android.content.Context

object SplitTunnelRepository {
    private const val PREFS_NAME = "xray_prefs"
    private const val KEY_MODE = "split_tunnel_mode"
    private const val KEY_PACKAGES = "split_tunnel_packages"

    data class SplitTunnelPreferences(
        val mode: SplitTunnelMode,
        val packages: Set<String>
    )

    fun load(context: Context): SplitTunnelPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = SplitTunnelMode.fromValue(prefs.getString(KEY_MODE, SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED.value))
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet<String>())?.toSet() ?: emptySet()
        return SplitTunnelPreferences(mode = mode, packages = packages)
    }

    fun save(context: Context, mode: SplitTunnelMode, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_MODE, mode.value)
            putStringSet(KEY_PACKAGES, HashSet(packages))
            apply()
        }
    }

    fun loadInstalledApps(context: Context): List<AppEntry> =
        com.justme.xtls_core_proxy.apps.InstalledAppsLoader.loadInstalled(context)
}
