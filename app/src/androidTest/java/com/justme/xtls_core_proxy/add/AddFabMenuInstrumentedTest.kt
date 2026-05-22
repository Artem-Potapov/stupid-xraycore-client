package com.justme.xtls_core_proxy.add

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddFabMenuInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fabOpensMenuWithAllFourItems() {
        val activity = composeRule.activity
        val fabCd = activity.getString(R.string.main_cd_add_profile)

        composeRule.onNodeWithContentDescription(fabCd).performClick()

        // Clipboard label may include a hint suffix; match on the localized prefix.
        composeRule.onNodeWithText(
            activity.getString(R.string.add_menu_from_clipboard),
            substring = true
        ).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_subscription))
            .assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_vless))
            .assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_json))
            .assertIsDisplayed()
    }

    @Test
    fun tappingAddSubscriptionOpensPasteDialog() {
        val activity = composeRule.activity
        val fabCd = activity.getString(R.string.main_cd_add_profile)

        composeRule.onNodeWithContentDescription(fabCd).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_subscription)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.add_dialog_subscription_title))
            .assertIsDisplayed()
    }

    @Test
    fun tappingAddVlessOpensPasteDialog() {
        val activity = composeRule.activity
        val fabCd = activity.getString(R.string.main_cd_add_profile)

        composeRule.onNodeWithContentDescription(fabCd).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_vless)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.add_dialog_vless_title))
            .assertIsDisplayed()
    }

    @Test
    fun tappingAddJsonOpensPasteDialog() {
        val activity = composeRule.activity
        val fabCd = activity.getString(R.string.main_cd_add_profile)

        composeRule.onNodeWithContentDescription(fabCd).performClick()
        composeRule.onNodeWithText(activity.getString(R.string.add_menu_json)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.add_dialog_json_title))
            .assertIsDisplayed()
    }
}
