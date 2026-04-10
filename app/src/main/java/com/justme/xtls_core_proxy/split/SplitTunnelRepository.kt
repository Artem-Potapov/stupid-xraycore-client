package com.justme.xtls_core_proxy.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.LinkedHashMap

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

    fun loadInstalledApps(context: Context): List<AppEntry> {
        val packageManager = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolvedActivities = packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val unique = LinkedHashMap<String, AppEntry>()

        for (activity in resolvedActivities) {
            val packageName = activity.activityInfo?.packageName ?: continue
            val activityInfo = activity.activityInfo
            val appName = activity.loadLabel(packageManager)
                .toString()
                .ifBlank { packageName }
            val icon = runCatching { activityInfo.loadIcon(packageManager) }.getOrNull()
            unique[packageName] = AppEntry(packageName = packageName, appName = appName, icon = icon)
        }

        return unique.values.sortedBy { it.appName.lowercase() }
    }
}
