package com.justme.xtls_core_proxy.state

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for VpnViewModel's observation of the shared
 * ActiveProfileRepository.activeProfileIdFlow and LogRepository.errorEvents
 * surfaces. Each test resets the shared singletons in @Before/@After so the
 * suite can be re-run in either order.
 */
@RunWith(AndroidJUnit4::class)
class VpnViewModelStateObservationTest {

    private lateinit var application: Application
    private lateinit var testDb: AppDatabase
    private lateinit var vm: VpnViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        testDb = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        AppDatabase.setInstanceForTests(testDb)
        application.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .edit { clear() }
        ActiveProfileRepository.resetForTests()
        LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
        vm = VpnViewModel(application)
    }

    @After
    fun tearDown() {
        LogRepository.setConnectionState(VpnConnectionState.DISCONNECTED)
        ActiveProfileRepository.resetForTests()
        AppDatabase.setInstanceForTests(null)
        testDb.close()
    }

    @Test
    fun activeProfileId_reflectsRepositoryWrite(): Unit = runBlocking {
        // first { predicate } subscribes for the wait — required because
        // activeProfileId is stateIn(WhileSubscribed(5_000)) and its upstream
        // stays dormant without a downstream collector. .value reads alone
        // do not subscribe and would never observe the repo's emissions.
        ActiveProfileRepository.setActiveProfileId(application, 12L)
        withTimeout(2_000) { vm.activeProfileId.first { it == 12L } }

        ActiveProfileRepository.setActiveProfileId(application, null)
        withTimeout(2_000) { vm.activeProfileId.first { it == null } }
    }

    @Test
    fun activeProfileId_reflectsExternalWrite_simulatedTilePath(): Unit = runBlocking {
        // Simulate XrayVpnTileService calling ActiveProfileRepository
        // directly (bypassing the VM's connect/disconnect methods).
        ActiveProfileRepository.setActiveProfileId(application, 99L)
        withTimeout(2_000) { vm.activeProfileId.first { it == 99L } }
    }

    @Test
    fun error_setOnEmitError_clearedOnConnectingTransition(): Unit = runBlocking {
        LogRepository.emitError(R.string.vpn_start_failed_error)
        val expected = SupportedLanguage.localize(application)
            .getString(R.string.vpn_start_failed_error)
        withTimeout(2_000) {
            while (vm.error.value != expected) delay(20)
        }
        assertEquals(expected, vm.error.value)

        // The CONNECTING transition is what clears the error.
        LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
        withTimeout(2_000) {
            while (vm.error.value != null) delay(20)
        }
        assertNull(vm.error.value)
    }

    @Test
    fun error_isLocalizedAgainstAppLocale(): Unit = runBlocking {
        LogRepository.emitError(R.string.vpn_permission_revoked_error)
        val expected = SupportedLanguage.localize(application)
            .getString(R.string.vpn_permission_revoked_error)
        withTimeout(2_000) {
            while (vm.error.value != expected) delay(20)
        }
        assertEquals(expected, vm.error.value)
    }
}
