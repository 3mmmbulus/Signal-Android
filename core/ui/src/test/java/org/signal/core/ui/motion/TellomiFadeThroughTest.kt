/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi (interaction audit A-23): switching tabs or entering the archive is a fade through — the old content
 * fades out first and the new one only starts once it is gone, instead of the two crossfading or one jumping in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiFadeThroughTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `the old content leaves before the new content arrives`() {
    var tab by mutableStateOf("chats")
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.setContent {
      AnimatedContent(targetState = tab, transitionSpec = { TellomiFadeThrough.transform() }, label = "test") { shown ->
        Box(Modifier.size(10.dp).testTag(shown))
      }
    }
    composeTestRule.mainClock.advanceTimeByFrame()

    tab = "calls"
    Snapshot.sendApplyNotifications()
    composeTestRule.mainClock.advanceTimeBy(TellomiFadeThrough.OUT_MS / 2L)

    assertTrue("the old tab is still fading out", exists("chats"))

    composeTestRule.mainClock.advanceTimeBy(TellomiFadeThrough.OUT_MS + TellomiFadeThrough.IN_MS + 100L)

    assertFalse("the old tab is gone", exists("chats"))
    assertTrue("the new tab is shown", exists("calls"))
  }

  private fun exists(tag: String): Boolean {
    return composeTestRule.onNodeWithTag(tag).fetchSemanticsNodeOrNull() != null
  }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodeOrNull() = try {
  fetchSemanticsNode()
} catch (e: AssertionError) {
  null
}
