package com.dhanuk.photodoctorpro

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dhanuk.photodoctorpro.ui.screens.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke instrumentation test: ensures MainActivity launches and HomeScreen
 * is reachable from the navigation graph.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_loadsAndShowsAppName() {
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.app_name))
            .assertExists()
    }
}
