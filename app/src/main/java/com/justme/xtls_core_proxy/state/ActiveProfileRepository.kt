package com.justme.xtls_core_proxy.state

import android.content.Context
import androidx.core.content.edit
import com.justme.xtls_core_proxy.db.AppDatabase
import kotlinx.coroutines.flow.first

object ActiveProfileRepository {
    private const val PREFS_NAME = "vpn_prefs"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val SENTINEL = -1L

    fun getActiveProfileId(context: Context): Long? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_ACTIVE_PROFILE_ID, SENTINEL).takeIf { it != SENTINEL }
    }

    fun setActiveProfileId(context: Context, id: Long?) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (id != null) putLong(KEY_ACTIVE_PROFILE_ID, id)
            else remove(KEY_ACTIVE_PROFILE_ID)
        }
    }

    suspend fun pickOrPersistActive(context: Context): Long? {
        val appCtx = context.applicationContext
        val dao = AppDatabase.get(appCtx).profileDao()

        val stored = getActiveProfileId(appCtx)
        if (stored != null && dao.getById(stored) != null) return stored

        val first = dao.getAll().first().firstOrNull()
        if (first == null) {
            if (stored != null) setActiveProfileId(appCtx, null)
            return null
        }
        setActiveProfileId(appCtx, first.id)
        return first.id
    }
}
