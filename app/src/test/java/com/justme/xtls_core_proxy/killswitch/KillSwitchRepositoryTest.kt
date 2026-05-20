package com.justme.xtls_core_proxy.killswitch

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KillSwitchRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putBoolean(any(), any()) } doReturn it
            on { putStringSet(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

    @Test
    fun load_returnsDefaults_whenPrefsEmpty() {
        whenever(prefs.getBoolean(eq("kill_switch_enabled"), eq(false))).thenReturn(false)
        whenever(prefs.getStringSet(eq("kill_switch_packages"), any())).thenReturn(null)

        val result = KillSwitchRepository.load(context)

        assertFalse(result.enabled)
        assertTrue(result.packages.isEmpty())
    }

    @Test
    fun load_returnsStoredValues() {
        whenever(prefs.getBoolean(eq("kill_switch_enabled"), eq(false))).thenReturn(true)
        whenever(prefs.getStringSet(eq("kill_switch_packages"), any()))
            .thenReturn(setOf("com.example.a", "com.example.b"))

        val result = KillSwitchRepository.load(context)

        assertTrue(result.enabled)
        assertEquals(setOf("com.example.a", "com.example.b"), result.packages)
    }

    @Test
    fun save_writesEnabledAndPackages() {
        KillSwitchRepository.save(context, enabled = true, packages = setOf("com.example.x"))

        verify(editor).putBoolean(eq("kill_switch_enabled"), eq(true))
        argumentCaptor<Set<String>>().apply {
            verify(editor).putStringSet(eq("kill_switch_packages"), capture())
            assertEquals(setOf("com.example.x"), firstValue)
        }
        verify(editor).apply()
    }
}
