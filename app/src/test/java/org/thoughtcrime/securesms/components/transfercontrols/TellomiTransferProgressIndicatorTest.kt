/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.transfercontrols

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme

/** Tellomi (interaction audit A-13): the transfer button changes state without the old 200 ms delay and one-second fade. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiTransferProgressIndicatorTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val icon = ImageVector.Builder("icon", 24.dp, 24.dp, 24f, 24f).build()
  private val ready = TransferProgressState.Ready(icon, "Download", "download", onStartClick = {})
  private val complete = TransferProgressState.Complete(icon, "Done", Color.Black)

  @Test
  fun `a finished download swaps the button for the done icon well within a second`() {
    val state = mutableStateOf<TransferProgressState>(ready)
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      SignalTheme {
        TransferProgressIndicator(state = state.value)
      }
    }
    composeTestRule.mainClock.advanceTimeBy(100)
    composeTestRule.onNodeWithContentDescription("Download").assertExists()

    state.value = complete
    Snapshot.sendApplyNotifications()
    composeTestRule.mainClock.advanceTimeByFrame()
    composeTestRule.onNodeWithContentDescription("Done").assertExists()

    composeTestRule.mainClock.advanceTimeBy(700)
    composeTestRule.onNodeWithContentDescription("Download").assertDoesNotExist()
  }
}
