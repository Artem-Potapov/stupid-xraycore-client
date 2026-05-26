package com.justme.xtls_core_proxy.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import androidx.annotation.StringRes
import com.justme.xtls_core_proxy.geo.GeoAssetPreparer
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.AndroidUsageStatsEventSource
import com.justme.xtls_core_proxy.killswitch.ForegroundAppMonitor
import com.justme.xtls_core_proxy.killswitch.KillSwitchRepository
import com.justme.xtls_core_proxy.killswitch.UsageStatsForegroundAppMonitor
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.split.SplitTunnelMode
import com.justme.xtls_core_proxy.split.SplitTunnelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@SuppressLint("VpnServicePolicy")
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.justme.xtls_core_proxy.action.START"
        const val ACTION_STOP = "com.justme.xtls_core_proxy.action.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val NOTIFICATION_ID = 1101
        private const val ERROR_CHANNEL_ID = "xray_vpn_error_channel"
        private const val ERROR_NOTIFICATION_ID = 1102
    }

    private val lock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var running = false

    @Volatile private var currentProfileId: Long = -1L

    private var killSwitchMonitor: UsageStatsForegroundAppMonitor? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var settingsObserverJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tunnelOpScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (profileId == -1L) {
                    LogRepository.setConnectionState(VpnConnectionState.ERROR)
                    LogRepository.emitError(R.string.vpn_start_failed_error)
                    LogRepository.append("Refused to start: no profile ID provided")
                    stopSelf()
                } else {
                    startVpn(profileId)
                }
            }

            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        LogRepository.append("VPN permission revoked by system")
        LogRepository.emitError(R.string.vpn_permission_revoked_error)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tunnelOpScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(profileId: Long) {
        synchronized(lock) {
            if (running) {
                LogRepository.append("VPN already running")
                return
            }
            running = true
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(localizedString(R.string.vpn_status_connecting)))

        // Defensive re-check: a caller (e.g. the QS tile) may have pre-flighted
        // VpnService.prepare() before dispatching ACTION_START, and the user
        // could have revoked permission in the gap before we got here. Without
        // this guard, establish() would later fail with a silent
        // SecurityException and the user would only see ERROR state with no
        // explanation. startForeground above satisfies the FGS contract before
        // we stop ourselves.
        if (VpnService.prepare(this) != null) {
            LogRepository.setConnectionState(VpnConnectionState.ERROR)
            LogRepository.emitError(R.string.vpn_permission_revoked_error)
            LogRepository.append("Refused to start: VPN permission not granted")
            postPermissionRevokedNotification()
            stopVpn()
            return
        }

        LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
        LogRepository.append("Starting VPN service")

        Thread {
            try {
                val profile = runBlocking {
                    AppDatabase.get(this@XrayVpnService).profileDao().getById(profileId)
                }
                if (profile == null) {
                    LogRepository.setConnectionState(VpnConnectionState.ERROR)
                    LogRepository.emitError(R.string.vpn_start_failed_error)
                    LogRepository.append("Profile not found (id=$profileId)")
                    stopVpn()
                    return@Thread
                }

                currentProfileId = profileId

                bringUpTunnel(profile)
                    .onSuccess {
                        LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                        updateNotification(localizedString(R.string.vpn_status_connected))
                        val prefs = KillSwitchRepository.load(this@XrayVpnService)
                        applyKillSwitchPreferences(prefs)
                        settingsObserverJob?.cancel()
                        settingsObserverJob = serviceScope.launch {
                            KillSwitchRepository.state.collect { newPrefs ->
                                applyKillSwitchPreferences(newPrefs)
                            }
                        }
                    }
                    .onFailure { error ->
                        LogRepository.setConnectionState(VpnConnectionState.ERROR)
                        LogRepository.emitError(R.string.vpn_start_failed_error)
                        LogRepository.append("Xray start failed: ${error.message}")
                        stopVpn()
                    }
            } catch (error: Throwable) {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_start_failed_error)
                LogRepository.append("VPN start failed: ${error.message}")
                stopVpn()
            }
        }.start()
    }

    private fun bringUpTunnel(profile: Profile): Result<Unit> {
        return runCatching {
            val configJson = ConfigBuilder.buildRuntimeConfig(profile.config)

            val geoAssetDir = GeoAssetPreparer.prepare(this)
                .getOrElse { error ->
                    throw IllegalStateException("Geofile preparation failed: ${error.message}", error)
                }

            val builder = Builder()
                .setSession(localizedString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.7.0.1", 32)
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("2606:4700:4700::1111")

            val splitPrefs = SplitTunnelRepository.load(this@XrayVpnService)
            when (splitPrefs.mode) {
                SplitTunnelMode.ALLOW_ONLY -> {
                    if (splitPrefs.packages.isEmpty()) {
                        LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
                    }
                    splitPrefs.packages.forEach { pkg ->
                        if (pkg == packageName) {
                            LogRepository.append("Ignoring self package in allow-only split tunnel mode")
                            return@forEach
                        }
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            LogRepository.append("Split tunnel skipped missing package: $pkg")
                        }
                    }
                }
                SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                    val blockedPackages = splitPrefs.packages + packageName
                    blockedPackages.forEach { pkg ->
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            LogRepository.append("Split tunnel skipped missing package: $pkg")
                        }
                    }
                }
            }

            val pfd = builder.establish()
                ?: throw IllegalStateException("VpnService.establish() returned null")

            tunInterface = pfd
            val fd = pfd.fd
            LogRepository.append("TUN established with fd=$fd")
            LogRepository.append("Using geofiles from ${geoAssetDir.absolutePath}")

            XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
            LogRepository.append("Xray core started")
        }
    }

    private fun tearDownTunnel() {
        XrayBridge.stopXray().onFailure { error ->
            LogRepository.append("Xray stop warning: ${error.message}")
        }
        try {
            tunInterface?.close()
        } catch (error: Throwable) {
            LogRepository.append("TUN close warning: ${error.message}")
        } finally {
            tunInterface = null
        }
    }

    private fun killTunnel(triggerPackageLabel: String) {
        tunnelOpScope.launch {
            LogRepository.append("Kill-switch: tearing down tunnel for $triggerPackageLabel")
            try {
                tearDownTunnel()
                updateNotification(localizedString(R.string.vpn_status_paused, triggerPackageLabel))
                LogRepository.setConnectionState(VpnConnectionState.PAUSED)
            } catch (error: Throwable) {
                LogRepository.append("killTunnel failed: ${error.message}")
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_revive_error)
                stopVpn()
            }
        }
    }

    private fun reviveTunnel() {
        tunnelOpScope.launch {
            val profileId = currentProfileId
            if (profileId == -1L) {
                LogRepository.append("reviveTunnel: no current profile, cannot revive")
                stopVpn()
                return@launch
            }
            LogRepository.append("Kill-switch: reviving tunnel for profile id=$profileId")
            val profile = AppDatabase.get(this@XrayVpnService).profileDao().getById(profileId)
            if (profile == null) {
                LogRepository.append("reviveTunnel: profile $profileId not found")
                LogRepository.emitError(R.string.vpn_revive_error)
                postReviveErrorNotification()
                stopVpn()
                return@launch
            }
            bringUpTunnel(profile)
                .onSuccess {
                    updateNotification(localizedString(R.string.vpn_status_connected))
                    LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                }
                .onFailure { error ->
                    LogRepository.append("reviveTunnel failed: ${error.message}")
                    LogRepository.emitError(R.string.vpn_revive_error)
                    postReviveErrorNotification()
                    stopVpn()
                }
        }
    }

    private inner class KillSwitchListener : ForegroundAppMonitor.Listener {
        override fun onControlledAppForeground(packageName: String) {
            val label = runCatching {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrElse { packageName }
            killTunnel(label)
        }

        override fun onControlledAppLeftForeground() {
            reviveTunnel()
        }
    }

    private fun applyKillSwitchPreferences(prefs: KillSwitchRepository.Preferences) {
        val shouldRun = prefs.enabled && prefs.packages.isNotEmpty() && running

        if (!shouldRun) {
            val wasPaused = LogRepository.connectionState.value == VpnConnectionState.PAUSED
            killSwitchMonitor?.stop()
            killSwitchMonitor = null
            unregisterScreenReceiver()
            // If the user disabled the feature (or cleared all packages) while
            // the tunnel was paused, restore the tunnel. Without this the user
            // has to manually stop+restart the VPN to recover.
            if (wasPaused && running) {
                reviveTunnel()
            }
            return
        }

        if (killSwitchMonitor == null) {
            val source = AndroidUsageStatsEventSource(this)
            val monitor = UsageStatsForegroundAppMonitor(source)
            killSwitchMonitor = monitor
            monitor.start(prefs.packages, KillSwitchListener())
            registerScreenReceiver()
            LogRepository.append("Kill-switch monitor started with ${prefs.packages.size} package(s)")
        } else {
            killSwitchMonitor?.updatePackages(prefs.packages)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> killSwitchMonitor?.pausePolling()
                    Intent.ACTION_SCREEN_ON -> killSwitchMonitor?.resumePolling()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(receiver, filter)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
                // not registered
            }
        }
        screenReceiver = null
    }

    private fun stopVpn() {
        val shouldStop: Boolean
        synchronized(lock) {
            shouldStop = running
            running = false
        }
        if (!shouldStop && tunInterface == null) {
            stopSelf()
            return
        }

        killSwitchMonitor?.stop()
        killSwitchMonitor = null
        settingsObserverJob?.cancel()
        settingsObserverJob = null
        unregisterScreenReceiver()

        tearDownTunnel()

        currentProfileId = -1L
        LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
        LogRepository.append("VPN stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Resolves a string in the user's chosen app locale. We can't rely on the
     * service's own getString because Service contexts don't pick up per-app locale
     * changes mid-session on API <33 — wrap via SupportedLanguage.localize each call.
     * (Notification channel name/description are still cached by the system at channel
     * creation time; that is an Android limitation and unavoidable here.)
     */
    private fun localizedString(@StringRes resId: Int, vararg args: Any): String =
        SupportedLanguage.localize(this).getString(resId, *args)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = localizedString(R.string.vpn_channel_description)
        manager.createNotificationChannel(channel)

        val errorChannel = NotificationChannel(
            ERROR_CHANNEL_ID,
            localizedString(R.string.vpn_error_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        errorChannel.description = localizedString(R.string.vpn_error_channel_description)
        manager.createNotificationChannel(errorChannel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun postReviveErrorNotification() {
        postErrorNotification(R.string.vpn_revive_error)
    }

    private fun postPermissionRevokedNotification() {
        postErrorNotification(R.string.vpn_permission_revoked_error)
    }

    private fun postErrorNotification(@StringRes messageRes: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(localizedString(R.string.vpn_notification_title))
            .setContentText(localizedString(messageRes))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }
}
