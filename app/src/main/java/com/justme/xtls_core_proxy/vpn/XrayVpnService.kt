package com.justme.xtls_core_proxy.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.geo.GeoAssetPreparer
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.split.SplitTunnelMode
import com.justme.xtls_core_proxy.split.SplitTunnelRepository
import kotlinx.coroutines.runBlocking

@SuppressLint("VpnServicePolicy")
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.justme.xtls_core_proxy.action.START"
        const val ACTION_STOP = "com.justme.xtls_core_proxy.action.STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val NOTIFICATION_ID = 1101
    }

    private val lock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (profileId == -1L) {
                    LogRepository.setConnectionState(VpnConnectionState.ERROR)
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
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
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
        startForeground(NOTIFICATION_ID, buildNotification("Connecting"))
        LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
        LogRepository.append("Starting VPN service")

        Thread {
            try {
                val profile = runBlocking {
                    AppDatabase.get(this@XrayVpnService).profileDao().getById(profileId)
                }
                if (profile == null) {
                    LogRepository.setConnectionState(VpnConnectionState.ERROR)
                    LogRepository.append("Profile not found (id=$profileId)")
                    stopVpn()
                    return@Thread
                }

                val configJson = ConfigBuilder.buildRuntimeConfig(profile.config)

                val geoAssetDir = GeoAssetPreparer.prepare(this)
                    .getOrElse { error ->
                        throw IllegalStateException("Geofile preparation failed: ${error.message}", error)
                    }

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
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

                XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath)
                    .onSuccess {
                        LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
                        LogRepository.append("Xray core started")
                        updateNotification("Connected")
                    }
                    .onFailure { error ->
                        LogRepository.setConnectionState(VpnConnectionState.ERROR)
                        LogRepository.append("Xray start failed: ${error.message}")
                        stopVpn()
                    }
            } catch (error: Throwable) {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.append("VPN start failed: ${error.message}")
                stopVpn()
            }
        }.start()
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

        LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
        LogRepository.append("VPN stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.vpn_channel_description)
        manager.createNotificationChannel(channel)
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
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
