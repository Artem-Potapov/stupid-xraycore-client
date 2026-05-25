package com.justme.xtls_core_proxy.state

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveProfileRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearState()
    }

    @After
    fun tearDown() {
        clearState()
    }

    private fun clearState() {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit { clear() }
        AppDatabase.get(context).clearAllTables()
    }

    @Test
    fun getActiveProfileId_returnsNullWhenAbsent() {
        assertNull(ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun getActiveProfileId_returnsNullForSentinelValue() {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .edit { putLong("active_profile_id", -1L) }
        assertNull(ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun setActiveProfileId_null_removesKey() {
        ActiveProfileRepository.setActiveProfileId(context, 42L)
        ActiveProfileRepository.setActiveProfileId(context, null)
        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("active_profile_id"))
    }

    @Test
    fun setActiveProfileId_long_writesKey() {
        ActiveProfileRepository.setActiveProfileId(context, 7L)
        assertEquals(7L, ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun pickOrPersistActive_emptyDbNoStored_returnsNull() = runBlocking {
        assertNull(ActiveProfileRepository.pickOrPersistActive(context))
    }

    @Test
    fun pickOrPersistActive_emptyDbStaleStored_returnsNullAndClearsKey() = runBlocking {
        ActiveProfileRepository.setActiveProfileId(context, 99L)
        assertNull(ActiveProfileRepository.pickOrPersistActive(context))
        assertNull(ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun pickOrPersistActive_validStoredId_returnsItUnchanged() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        val id = dao.insert(Profile(name = "alpha", config = "x"))
        ActiveProfileRepository.setActiveProfileId(context, id)
        assertEquals(id, ActiveProfileRepository.pickOrPersistActive(context))
        assertEquals(id, ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun pickOrPersistActive_noStoredPicksFirstByIdAsc() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "alpha", config = "x"))
        dao.insert(Profile(name = "beta", config = "y"))
        dao.insert(Profile(name = "gamma", config = "z"))
        val picked = ActiveProfileRepository.pickOrPersistActive(context)
        assertEquals(firstId, picked)
        assertEquals(firstId, ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun pickOrPersistActive_staleStoredFallsBackToFirstAndPersists() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "alpha", config = "x"))
        dao.insert(Profile(name = "beta", config = "y"))
        ActiveProfileRepository.setActiveProfileId(context, 99_999L)  // not in DB
        val picked = ActiveProfileRepository.pickOrPersistActive(context)
        assertEquals(firstId, picked)
        assertEquals(firstId, ActiveProfileRepository.getActiveProfileId(context))
    }
}
