/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.compose

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme

/**
 * Tellomi（交互审计 A-30「撤销形态」）：可撤销的提示条左边倒计时环和剩下的整秒数，走完自己收起；普通提示条没有环。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiCountdownSnackbarTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun `seconds are rounded up so the last second shows 1`() {
    assertThat(TellomiCountdownSnackbar.displayedSeconds(5_000)).isEqualTo(5)
    assertThat(TellomiCountdownSnackbar.displayedSeconds(4_200)).isEqualTo(5)
    assertThat(TellomiCountdownSnackbar.displayedSeconds(4_000)).isEqualTo(4)
    assertThat(TellomiCountdownSnackbar.displayedSeconds(400)).isEqualTo(1)
    assertThat(TellomiCountdownSnackbar.displayedSeconds(0)).isEqualTo(0)
  }

  @Test
  fun `the undo bar counts down and goes away when the time is up`() {
    var result: SnackbarResult? = null
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      SignalTheme {
        val hostState = remember { SnackbarHostState() }
        Snackbars.Host(hostState)
        LaunchedEffect(Unit) {
          result = hostState.showCountdownSnackbar(message = "Chat archived", actionLabel = "Undo", countdownMillis = 5_000)
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(300)
    composeTestRule.onNodeWithText("Chat archived").assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiCountdownSnackbar.RING_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("5", useUnmergedTree = true).assertExists()

    composeTestRule.mainClock.advanceTimeBy(1_500)
    composeTestRule.onNodeWithText("4", useUnmergedTree = true).assertExists()

    composeTestRule.mainClock.advanceTimeBy(4_000)
    assertThat(result).isEqualTo(SnackbarResult.Dismissed)
  }

  @Test
  fun `tapping undo reports the action`() {
    var result: SnackbarResult? = null
    // 倒计时每帧都在走，测试时钟自己推
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      SignalTheme {
        val hostState = remember { SnackbarHostState() }
        Snackbars.Host(hostState)
        LaunchedEffect(Unit) {
          result = hostState.showCountdownSnackbar(message = "Chat archived", actionLabel = "Undo", countdownMillis = 5_000)
        }
      }
    }

    composeTestRule.mainClock.advanceTimeBy(300)
    composeTestRule.onNodeWithText("Undo").performClick()
    composeTestRule.mainClock.advanceTimeBy(300)
    assertThat(result).isEqualTo(SnackbarResult.ActionPerformed)
  }

  @Test
  fun `a plain bar has no countdown`() {
    var shown: Boolean? = null
    composeTestRule.setContent {
      SignalTheme {
        val hostState = remember { SnackbarHostState() }
        Snackbars.Host(hostState)
        LaunchedEffect(Unit) {
          shown = true
          hostState.showSnackbar(message = "Copied", duration = Snackbars.Duration.LONG)
        }
      }
    }

    composeTestRule.onNodeWithText("Copied").assertIsDisplayed()
    assertThat(shown).isEqualTo(true)
    assertThat(composeTestRule.countNodesWithTag(TellomiCountdownSnackbar.RING_TEST_TAG)).isEqualTo(0)
  }

  private fun ComposeContentTestRule.countNodesWithTag(tag: String): Int {
    return onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
  }
}
