/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedCard
import java.io.File

/** 「不感兴趣」原地换成反馈卡（第三节第 11 条）。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedNotInterestedTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var failUndo: Boolean = false) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    override suspend fun notInterested(contentId: String) {
      calls += "hide $contentId"
    }
    override suspend fun undoNotInterested(contentId: String) {
      if (failUndo) error("offline")
      calls += "undo $contentId"
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "x")

  @Test
  fun `hiding marks the card, undo brings it back, twice sends once`() {
    val source = FakeSource()
    val viewModel = FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords())

    viewModel.notInterested(card)
    viewModel.notInterested(card)
    assertThat(viewModel.state.value.hidden).isEqualTo(setOf("c1"))

    viewModel.undoNotInterested(card)
    assertThat(viewModel.state.value.hidden).isEqualTo(emptySet())
    assertThat(source.calls).isEqualTo(listOf("hide c1", "undo c1"))
  }

  @Test
  fun `a failed undo keeps it hidden and says so`() {
    val viewModel = FeedViewModel(FakeSource(failUndo = true), dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords())
    viewModel.notInterested(card)
    viewModel.undoNotInterested(card)
    assertThat(viewModel.state.value.hidden).isEqualTo(setOf("c1"))
    assertThat(viewModel.state.value.transientError).isEqualTo(FeedState.TransientError.ACTION_FAILED)
  }

  @Test
  fun `feedback card offers undo, preferences and report`() {
    val events = mutableListOf<String>()
    composeTestRule.setContent {
      SignalTheme {
        FeedNotInterestedCard(onUndo = { events += "undo" }, onManagePreferences = { events += "prefs" }, onUncomfortable = { events += "report" })
      }
    }
    composeTestRule.onNodeWithText("Post hidden. You'll see fewer posts like this.").assertExists()
    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_not_interested.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    composeTestRule.onNodeWithTag(FeedNotInterestedCardTestTags.UNDO).performClick()
    composeTestRule.onNodeWithTag(FeedNotInterestedCardTestTags.PREFERENCES).performClick()
    composeTestRule.onNodeWithTag(FeedNotInterestedCardTestTags.UNCOMFORTABLE).performClick()
    assertThat(events).isEqualTo(listOf("undo", "prefs", "report"))
  }
}
