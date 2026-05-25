package com.justme.xtls_core_proxy.tile

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XrayVpnTileService : TileService() {

    private var listenJob: Job? = null
    private var clickScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            LogRepository.connectionState.collect { state -> updateTile(state) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        clickScope?.cancel()
        clickScope = null
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { handleClick() }
        } else {
            handleClick()
        }
    }

    private fun handleClick() {
        val state = LogRepository.connectionState.value
        if (state == VpnConnectionState.CONNECTING ||
            state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.PAUSED
        ) {
            sendStopIntent()
            return
        }

        clickScope?.cancel()
        clickScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope ->
            scope.launch {
                val appCtx = applicationContext
                val profileId = ActiveProfileRepository.pickOrPersistActive(appCtx)
                if (profileId == null) {
                    withContext(Dispatchers.Main) {
                        val toastText = SupportedLanguage.localize(appCtx)
                            .getString(R.string.tile_toast_no_profiles)
                        Toast.makeText(appCtx, toastText, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val needsVpn = VpnService.prepare(this@XrayVpnTileService) != null
                    val needsNotif = needsNotificationPermission()
                    if (needsVpn || needsNotif) {
                        launchActivityForAutoConnect(profileId)
                    } else {
                        sendStartIntent(profileId)
                    }
                }
            }
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun sendStartIntent(profileId: Long) {
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun sendStopIntent() {
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun launchActivityForAutoConnect(profileId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TILE_AUTOCONNECT, true)
            putExtra(MainActivity.EXTRA_TILE_PROFILE_ID, profileId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: VpnConnectionState) {
        val tile = qsTile ?: return
        val ctx = SupportedLanguage.localize(applicationContext)
        when (state) {
            VpnConnectionState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = ctx.getString(R.string.main_state_disconnected)
            }
            VpnConnectionState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = ctx.getString(R.string.main_state_connecting)
            }
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = ctx.getString(R.string.main_state_connected)
            }
            VpnConnectionState.PAUSED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = ctx.getString(R.string.main_state_paused)
            }
            VpnConnectionState.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = ctx.getString(R.string.main_state_error)
            }
        }
        tile.updateTile()
    }
}
