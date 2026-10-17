/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.mediasend.MediaSendDependenciesRule
import org.signal.mediasend.TellomiViewOnceChoice

/**
 * Tellomi（tellomi/tellomi#1714，ADR-0079 §4.4 第 1 条）：查看器说明框右侧的计时键——五项菜单（一次性查看 / 3 / 10 / 30 秒 / 不删除）、
 * 最上面一句说明、当前那项打勾；设了键上显示秒数；只在一对一会话里有，那时上游的一次性查看开关让位给它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class AlbumViewOnceTimerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @get:Rule
  val mediaSendDependenciesRule = MediaSendDependenciesRule(ApplicationProvider.getApplicationContext())

  private val selected = mutableListOf<Int?>()

  @Test
  fun `the menu has a header and five choices in Telegram's order`() {
    setButton(choice = null)
    composeTestRule.onNodeWithTag(AlbumViewOnceTimer.BUTTON_TEST_TAG).performClick()

    composeTestRule.onNodeWithText("Choose how long the media will be kept after opening.").assertIsDisplayed()
    val tops = TellomiViewOnceChoice.MENU_ORDER.map { choice ->
      composeTestRule.onNodeWithTag(AlbumViewOnceTimer.itemTestTag(choice)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
    }
    assertThat(tops.zipWithNext().all { (a, b) -> a < b }).isTrue()
    composeTestRule.onNodeWithText("View Once").assertIsDisplayed()
    composeTestRule.onNodeWithText("3 Seconds").assertIsDisplayed()
    composeTestRule.onNodeWithText("Do Not Delete").assertIsDisplayed()

    composeTestRule.onNodeWithText("10 Seconds").performClick()
    assertThat(selected).containsExactly(10)
  }

  @Test
  fun `a set timer shows its seconds on the button`() {
    setButton(choice = 30)
    composeTestRule.onNodeWithText("30").assertIsDisplayed()
  }

  @Test
  fun `in a one to one chat the viewer shows the timer instead of the upstream view once switch`() {
    setAlbum(canSetViewOnceTimer = true)
    composeTestRule.onNodeWithTag(AlbumViewOnceTimer.BUTTON_TEST_TAG).assertIsDisplayed()

    composeTestRule.onNodeWithTag(AlbumViewOnceTimer.BUTTON_TEST_TAG).performClick()
    composeTestRule.onNodeWithText("3 Seconds").performClick()
    assertThat(events.filterIsInstance<MediaEditScreenEvents.SetAlbumViewOnceChoice>().map { it.choice }).containsExactly(3)
    composeTestRule.onNodeWithTag(AlbumViewOnceTimer.HINT_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun `elsewhere there is no timer`() {
    setAlbum(canSetViewOnceTimer = false)
    assertThat(composeTestRule.onAllNodesWithTag(AlbumViewOnceTimer.BUTTON_TEST_TAG).fetchSemanticsNodes().size).isEqualTo(0)
  }

  private val events = mutableListOf<MediaEditScreenEvents>()

  private fun setButton(choice: Int?) {
    composeTestRule.setContent {
      SignalTheme {
        AlbumViewOnceTimerButton(choice = choice, accentColor = Color.Blue, onSelect = { selected += it })
      }
    }
    composeTestRule.waitForIdle()
  }

  private fun setAlbum(canSetViewOnceTimer: Boolean) {
    val photo = Media(
      uri = "content://media/1".toUri(),
      contentType = "image/jpeg",
      date = 0,
      width = 100,
      height = 100,
      size = 1024,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = "bucket",
      caption = null,
      transformProperties = null,
      fileName = null
    )
    composeTestRule.setContent {
      SignalTheme {
        MediaAlbumScreen(
          state = MediaEditState(
            selectedMedia = listOf(photo),
            album = listOf(photo),
            albumFocusedMedia = photo,
            isBrowsingAlbum = true,
            isViewOnceAvailable = true,
            canSetViewOnceTimer = canSetViewOnceTimer
          ),
          onEvent = { events += it }
        )
      }
    }
    composeTestRule.waitForIdle()
  }
}
