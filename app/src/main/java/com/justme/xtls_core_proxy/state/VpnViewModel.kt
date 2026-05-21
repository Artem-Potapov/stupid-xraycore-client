package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justme.xtls_core_proxy.BuildConfig
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.subs.SubscriptionRefreshCoordinator
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SubGroup(val subscription: Subscription, val profiles: List<Profile>)
data class ProfilesView(val manual: List<Profile>, val groups: List<SubGroup>) {
    companion object {
        val EMPTY = ProfilesView(emptyList(), emptyList())
    }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val dao = db.profileDao()
    private val subDao = db.subscriptionDao()
    private val prefs = application.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

    val profiles: StateFlow<List<Profile>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptions: StateFlow<List<Subscription>> = subDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupedProfiles: StateFlow<ProfilesView> =
        combine(profiles, subscriptions) { allProfiles, subs ->
            val bySubId = allProfiles.groupBy { it.subscriptionId }
            ProfilesView(
                manual = bySubId[null].orEmpty(),
                groups = subs.map { sub -> SubGroup(sub, bySubId[sub.id].orEmpty()) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesView.EMPTY)

    private val _activeProfileId = MutableStateFlow(
        prefs.getLong(KEY_ACTIVE_PROFILE_ID, -1L).takeIf { it != -1L }
    )
    val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val logs = LogRepository.logs
    val connectionState = LogRepository.connectionState

    private val defaultUserAgent = "XTLSCoreProxy/${BuildConfig.VERSION_NAME}"

    fun clearError() {
        _error.value = null
    }

    fun addProfile(name: String, config: String) {
        viewModelScope.launch {
            val storedConfig = ConfigBuilder.toProfileStorageConfig(config)
            dao.insert(Profile(name = name, config = storedConfig))
        }
    }

    fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            val storedConfig = ConfigBuilder.toProfileStorageConfig(profile.config)
            dao.update(profile.copy(config = storedConfig))
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

    fun addSubscription(
        name: String,
        url: String,
        userAgentOverride: String? = null,
        allowInsecureTls: Boolean = false,
        userIntervalHours: Int? = null,
        refreshAfterInsert: Boolean = false
    ): Job = viewModelScope.launch {
        val newId = subDao.insert(
            Subscription(
                name = name,
                url = url,
                userAgentOverride = userAgentOverride,
                allowInsecureTls = allowInsecureTls,
                userIntervalHours = userIntervalHours
            )
        )
        if (refreshAfterInsert) {
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = getApplication(),
                subId = newId,
                activeProfileIdProvider = { _activeProfileId.value },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
        }
    }

    fun updateSubscription(sub: Subscription, refreshAfterUpdate: Boolean = false): Job =
        viewModelScope.launch {
            subDao.update(sub)
            if (refreshAfterUpdate) {
                SubscriptionRefreshCoordinator.refresh(
                    scope = viewModelScope,
                    context = getApplication(),
                    subId = sub.id,
                    activeProfileIdProvider = { _activeProfileId.value },
                    db = db,
                    defaultUserAgent = defaultUserAgent
                )
            }
        }

    fun deleteSubscription(context: Context, sub: Subscription): Job = viewModelScope.launch {
        val activeId = _activeProfileId.value
        if (activeId != null) {
            val activeProfile = dao.getById(activeId)
            if (activeProfile?.subscriptionId == sub.id) {
                disconnect(context)
            }
        }
        subDao.delete(sub)
    }

    fun refreshSubscription(context: Context, subId: Long): Job =
        SubscriptionRefreshCoordinator.refresh(
            scope = viewModelScope,
            context = context.applicationContext,
            subId = subId,
            activeProfileIdProvider = { _activeProfileId.value },
            db = db,
            defaultUserAgent = defaultUserAgent
        )

    fun refreshAllStaleSubscriptions(context: Context): Job = viewModelScope.launch {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val candidates = subscriptions.value.filter { sub ->
            val last = sub.lastFetchedAt
            if (last == null) return@filter true
            val intervalMs = sub.effectiveIntervalHours().toLong() * 3_600_000L
            now - last >= intervalMs
        }
        candidates.forEach { sub ->
            SubscriptionRefreshCoordinator.refresh(
                scope = viewModelScope,
                context = appContext,
                subId = sub.id,
                activeProfileIdProvider = { _activeProfileId.value },
                db = db,
                defaultUserAgent = defaultUserAgent
            )
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
