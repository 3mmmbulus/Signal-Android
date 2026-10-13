/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.net.FeedInterest
import org.thoughtcrime.securesms.feed.net.FeedInterests
import org.thoughtcrime.securesms.feed.net.FeedPreferences
import java.io.File

/** 动态设置：个性化开关、兴趣标签、重置推荐的两次确认。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedRecommendationSettingsScreenTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val events = mutableListOf<String>()

  private val interests = FeedInterests(
    topics = listOf(FeedInterest("photo", score = 1.0), FeedInterest("baking", score = 0.6), FeedInterest("unknown-slug", score = 0.1)),
    authors = listOf(FeedInterest("n:p1", label = "Shanfeng", score = 1.0)),
    sources = listOf(FeedInterest("unsplash", label = "Unsplash", score = 0.5))
  )

  private fun show(state: FeedRecommendationSettingsState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedRecommendationSettingsScreen(
          state = state,
          onBack = {},
          onRetry = { events += "retry" },
          onPersonalizedChanged = { events += "personalized=$it" },
          onDeleteInterest = { dim, interest -> events += "delete $dim/${interest.key}" },
          onReset = { events += "reset" },
          onNoticeShown = {}
        )
      }
    }
  }

  @Test
  fun `tags are named, deletable, and reset asks twice`() {
    show(FeedRecommendationSettingsState(preferences = FeedPreferences(), interests = interests))

    composeTestRule.onNodeWithText("Photography").assertExists()
    composeTestRule.onNodeWithText("Baking & desserts").assertExists()
    composeTestRule.onNodeWithText("#unknown-slug").assertExists()
    composeTestRule.onNodeWithText("Shanfeng").assertExists()
    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_recommendation_settings.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.PERSONALIZED).performClick()
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.DELETE + "topic-photo").performClick()
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.RESET).performClick()
    composeTestRule.onNodeWithText("Continue").performClick()
    composeTestRule.onNodeWithText("Are you sure?").assertExists()
    composeTestRule.onAllNodesWithText("Reset recommendations").onLast().performClick()
    assertThat(events).isEqualTo(listOf("personalized=false", "delete topic/photo", "reset"))
  }

  @Test
  fun `no tags yet`() {
    show(FeedRecommendationSettingsState(preferences = FeedPreferences(), interests = FeedInterests()))
    composeTestRule.onNodeWithText("No interest tags yet").assertExists()
  }

  @Test
  fun `a failed load offers retry`() {
    show(FeedRecommendationSettingsState(loadFailed = true))
    composeTestRule.onNodeWithText("Retry").performClick()
    assertThat(events).isEqualTo(listOf("retry"))
  }
}
