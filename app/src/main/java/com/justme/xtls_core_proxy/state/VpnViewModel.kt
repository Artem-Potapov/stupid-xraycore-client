package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).profileDao()
    private val prefs = application.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

    val profiles: StateFlow<List<Profile>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeProfileId = MutableStateFlow(
        prefs.getLong(KEY_ACTIVE_PROFILE_ID, -1L).takeIf { it != -1L }
    )
    val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val logs = LogRepository.logs
    val connectionState = LogRepository.connectionState

    fun clearError() {
        _error.value = null
    }

    fun addProfile(name: String, config: String) {
        viewModelScope.launch {
            dao.insert(Profile(name = name, config = config))
        }
    }

    fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            dao.update(profile)
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            dao.delete(profile)
            if (_activeProfileId.value == profile.id) {
                setActiveProfileId(null)
            }
        }
    }

    fun connect(context: Context, profileId: Long): Boolean {
        setActiveProfileId(profileId)
        _error.value = null

        val appContext = context.applicationContext
        val startIntent = Intent(appContext, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
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
        setActiveProfileId(null)
    }

    private fun setActiveProfileId(id: Long?) {
        _activeProfileId.value = id
        prefs.edit {
            if (id != null) putLong(KEY_ACTIVE_PROFILE_ID, id)
            else remove(KEY_ACTIVE_PROFILE_ID)
        }
    }

    companion object {
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
