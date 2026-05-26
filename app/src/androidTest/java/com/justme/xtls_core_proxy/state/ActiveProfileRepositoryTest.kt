package com.justme.xtls_core_proxy.state

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
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

/**
 * Note: this suite mutates two pieces of shared global state — the `vpn_prefs`
 * SharedPreferences file and `AppDatabase.INSTANCE` (via `setInstanceForTests`).
 * Both are reset in @Before/@After, but the tests assume sequential execution
 * (JUnit4's default). Do not enable parallel test classes without first giving
 * each test class its own prefs name and serializing access to the AppDatabase
 * singleton.
 */
@RunWith(AndroidJUnit4::class)
class ActiveProfileRepositoryTest {

    private lateinit var context: Context
    private lateinit var testDb: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setInstanceForTests(testDb)
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
        testDb.close()
        AppDatabase.setInstanceForTests(null)
    }

    private fun clearPrefs() {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit { clear() }
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
    fun pickOrPersistActive_noStoredReturnsDaoGetFirst() = runBlocking {
        // Contract: when no profile id is stored, the repository delegates to
        // ProfileDao.getFirst() (SELECT * FROM profiles ORDER BY id ASC LIMIT 1)
        // and persists that id. We assert against the lowest inserted id, which
        // also happens to be the autogen primary key of the first insertion.
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "alpha", config = "x"))
        dao.insert(Profile(name = "beta", config = "y"))
        dao.insert(Profile(name = "gamma", config = "z"))
        val picked = ActiveProfileRepository.pickOrPersistActive(context)
        assertEquals(firstId, picked)
        assertEquals(firstId, ActiveProfileRepository.getActiveProfileId(context))
    }

    @Test
    fun pickOrPersistActive_staleStoredFallsBackToDaoGetFirst() = runBlocking {
        // Contract: when the stored id is missing from the DB, the repository
        // falls back to ProfileDao.getFirst() and persists the new id.
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "alpha", config = "x"))
        dao.insert(Profile(name = "beta", config = "y"))
        ActiveProfileRepository.setActiveProfileId(context, 99_999L)  // not in DB
        val picked = ActiveProfileRepository.pickOrPersistActive(context)
        assertEquals(firstId, picked)
        assertEquals(firstId, ActiveProfileRepository.getActiveProfileId(context))
    }
}
