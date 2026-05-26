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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XrayVpnTileService : TileService() {

    private val serviceScope = MainScope()
    private var listenJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = serviceScope.launch {
            LogRepository.connectionState.collect { state -> updateTile(state) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        clickJob?.cancel()
        clickJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        handleClick()
    }

    private fun handleClick() {
        val state = LogRepository.connectionState.value
        if (state == VpnConnectionState.CONNECTING ||
            state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.PAUSED
        ) {
            // Stop path needs no IO; only the dispatch waits for unlock.
            runOrDeferUnlock { executeDecision(TileClickDecision.Stop) }
            return
        }

        // Start path: do the DB lookup on IO immediately (it does not require
        // an unlocked device), then wrap only the Main-thread permission
        // decision + dispatch in unlockAndRun. This shrinks the time spent
        // inside the unlock callback to the minimum and avoids the case where
        // the device re-locks while we are still resolving the active profile.
        clickJob?.cancel()
        clickJob = serviceScope.launch(Dispatchers.IO) {
            val appCtx = applicationContext
            val profileId = ActiveProfileRepository.pickOrPersistActive(appCtx)

            withContext(Dispatchers.Main) {
                runOrDeferUnlock {
                    // Short-circuits via decideTileClick when profileId is
                    // null, so VpnService.prepare runs only when needed.
                    val needsVpn = profileId != null &&
                        VpnService.prepare(this@XrayVpnTileService) != null
                    val needsNotif = needsNotificationPermission()
                    executeDecision(
                        decideTileClick(state, profileId, needsVpn, needsNotif)
                    )
                }
            }
        }
    }

    private fun executeDecision(decision: TileClickDecision) {
        when (decision) {
            TileClickDecision.Stop -> sendStopIntent()
            TileClickDecision.NoProfileToast -> showNoProfileToast()
            is TileClickDecision.Start -> sendStartIntent(decision.profileId)
            is TileClickDecision.HandoffToMainActivity ->
                launchActivityForAutoConnect(decision.profileId)
        }
    }

    private fun showNoProfileToast() {
        val appCtx = applicationContext
        val toastText = SupportedLanguage.localize(appCtx)
            .getString(R.string.tile_toast_no_profiles)
        Toast.makeText(appCtx, toastText, Toast.LENGTH_LONG).show()
    }

    private fun runOrDeferUnlock(block: () -> Unit) {
        if (isLocked) unlockAndRun { block() } else block()
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
        startForegroundService(intent)
    }

    private fun sendStopIntent() {
        // XrayVpnService is a foreground service, and onClick() only reaches
        // this method when the tile observed an active state (CONNECTING /
        // CONNECTED / PAUSED) — so the service is running. Using
        // startForegroundService avoids the API 31+ background-start
        // restriction that can deny plain startService() if the tile's
        // foreground grant has already elapsed by the time we dispatch.
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
        }
        startForegroundService(intent)
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
