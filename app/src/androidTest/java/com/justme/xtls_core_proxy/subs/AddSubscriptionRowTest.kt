package com.justme.xtls_core_proxy.subs

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddSubscriptionRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<SubscriptionsActivity>()

    @Before
    fun setUpIntents() {
        Intents.init()
        // Stub the launched activity so it doesn't actually start.
        Intents.intending(hasComponent(SubscriptionEditActivity::class.java.name))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()))
    }

    @After
    fun releaseIntents() {
        Intents.release()
    }

    @Test
    fun addRowIsVisible() {
        val activity = composeRule.activity
        composeRule.onNodeWithText(activity.getString(R.string.subs_add_row_label))
            .assertIsDisplayed()
    }

    @Test
    fun tappingAddRowOpensSubscriptionEditActivity() {
        val activity = composeRule.activity
        composeRule.onNodeWithText(activity.getString(R.string.subs_add_row_label))
            .performClick()

        intended(hasComponent(SubscriptionEditActivity::class.java.name))
    }
}
