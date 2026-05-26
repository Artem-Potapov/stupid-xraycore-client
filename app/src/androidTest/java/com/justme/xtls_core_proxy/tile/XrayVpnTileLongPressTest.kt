package com.justme.xtls_core_proxy.tile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test verifying that the QS tile long-press contract is wired
 * up: an implicit `Intent(ACTION_QS_TILE_PREFERENCES).setPackage(packageName)`
 * resolves to `MainActivity`, not to the system Settings app.
 *
 * This is the manifest invariant that overrides Android's default long-press
 * behavior (which would open `ACTION_APPLICATION_DETAILS_SETTINGS`). If the
 * intent-filter on MainActivity is ever removed or its DEFAULT category is
 * dropped, this test fails — catching a UX regression that would otherwise
 * only show up on hands-on QA.
 */
@RunWith(AndroidJUnit4::class)
class XrayVpnTileLongPressTest {

    // Intent.ACTION_QS_TILE_PREFERENCES exists in framework source since API 28
    // but is @hide in the public SDK. Use the literal string — it matches the
    // <action> in AndroidManifest.xml verbatim.
    private val QS_TILE_PREFERENCES_ACTION =
        "android.service.quicksettings.action.QS_TILE_PREFERENCES"

    @Test
    fun qsTilePreferencesAction_resolvesToMainActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val intent = Intent(QS_TILE_PREFERENCES_ACTION)
            .setPackage(context.packageName)

        val resolved = pm.queryIntentActivities(intent, 0)

        assertTrue(
            "Expected at least one activity resolving ACTION_QS_TILE_PREFERENCES; " +
                "long-press would fall back to system Settings.",
            resolved.isNotEmpty()
        )
        // Of the matches in our own package, exactly one must be MainActivity.
        val matchedNames = resolved.map { it.activityInfo.name }
        assertTrue(
            "Expected MainActivity to handle ACTION_QS_TILE_PREFERENCES; got $matchedNames.",
            matchedNames.contains(MainActivity::class.java.name)
        )
    }

    @Test
    fun qsTilePreferencesAction_resolvesUniquelyInOurPackage() {
        // Long-press dispatch should be unambiguous — exactly one activity in
        // our package should claim ACTION_QS_TILE_PREFERENCES. Two would let
        // the system pick arbitrarily.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val intent = Intent(QS_TILE_PREFERENCES_ACTION)
            .setPackage(context.packageName)

        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(
            "Expected exactly one activity handling ACTION_QS_TILE_PREFERENCES; " +
                "got: ${resolved.map { it.activityInfo.name }}",
            1, resolved.size
        )
        assertEquals(
            MainActivity::class.java.name,
            resolved.single().activityInfo.name
        )
    }
}
