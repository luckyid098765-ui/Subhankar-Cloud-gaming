package com.example

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CoinPricingCalculator
import com.example.data.model.WalletSummary
import com.example.streaming.ControllerInputBus
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.ShubhankarCloudTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShubhankarCloudRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name matches Shubhankar Cloud`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Shubhankar Cloud", context.getString(R.string.app_name))
    }

    @Test
    fun `coin economy rules enforce 10 coins per hour and prevent negative balance`() {
        assertEquals(10, CoinPricingCalculator.coinsRequiredForHours(1))
        assertEquals(20, CoinPricingCalculator.coinsRequiredForHours(2))
        assertEquals(120, CoinPricingCalculator.minutesForCoins(20))
        assertTrue(CoinPricingCalculator.canAfford(balance = 20, hours = 2))
        assertFalse(CoinPricingCalculator.canAfford(balance = 9, hours = 1))
        assertFalse(CoinPricingCalculator.canAfford(balance = 0, hours = 1))
    }

    @Test
    fun `controller input bus serializes gamepad button presses`() = runTest {
        val bus = ControllerInputBus()
        var emitted: String? = null
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { emitted = bus.packets.first() }
        val event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, 0, 0, 0, 0, InputDevice.SOURCE_GAMEPAD)
        assertTrue(bus.onKeyEvent(event))
        job.join()
        assertTrue(emitted?.contains("GamepadState") == true)
        assertTrue(emitted?.contains("\"buttonsMask\":1") == true)
    }

    @Test
    fun `auth screen renders login and switches to register tab`() {
        composeRule.setContent {
            ShubhankarCloudTheme {
                AuthScreen(
                    isBusy = false,
                    authError = null,
                    serverReachable = true,
                    onlineNodes = 2,
                    currentServerUrl = "http://10.0.2.2:4000/api/v1/",
                    onLogin = { _, _ -> },
                    onRegister = { _, _, _ -> },
                    onGoogleToken = {},
                    onAuthError = {},
                    onSaveServerUrl = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("auth_email_input").assertIsDisplayed()
        composeRule.onNodeWithTag("auth_tab_register").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("register_username_input").assertIsDisplayed()
    }

    @Test
    fun `wallet screen displays coin balance and daily reward button`() {
        composeRule.setContent {
            ShubhankarCloudTheme {
                WalletScreen(
                    wallet = WalletSummary(balance = 20, dailyRewardClaimedToday = false),
                    fallbackBalance = 20,
                    isBusy = false,
                    onClaimDailyReward = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("wallet_balance_text").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_claim_daily_button").assertIsDisplayed()
    }
}
