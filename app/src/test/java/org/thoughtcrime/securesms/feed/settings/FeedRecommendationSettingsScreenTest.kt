/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import org.thoughtcrime.securesms.feed.net.FeedRecentTopic
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

  private fun scrollTo(matcher: SemanticsMatcher) {
    composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
  }

  private fun show(state: FeedRecommendationSettingsState) {
    composeTestRule.setContent {
      SignalTheme {
        FeedRecommendationSettingsScreen(
          state = state,
          onBack = {},
          onRetry = { events += "retry" },
          onPersonalizedChanged = { events += "personalized=$it" },
          onCategoryChanged = { slug, value -> events += "category $slug=$value" },
          onPause = { events += "pause" },
          onResume = { events += "resume" },
          onAddKeyword = { k, mode, days -> events += "add $k $mode $days" },
          onRemoveKeyword = { events += "remove ${it.keyword}" },
          onDeleteInterest = { dim, interest -> events += "delete $dim/${interest.key}" },
          onReset = { events += "reset" },
          onNoticeShown = {}
        )
      }
    }
  }

  @Test
  fun `tags are named, deletable, and reset asks twice`() {
    show(FeedRecommendationSettingsState(preferences = FeedPreferences(), interests = interests, personalizationToggleVisible = true))

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
    scrollTo(hasTestTag(FeedRecommendationSettingsTestTags.RESET))
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

  @Test
  fun `editorial mode has no switch, categories open and change, pause asks first`() {
    val preferences = FeedPreferences(
      categories = mapOf("food" to FeedPreferences.CATEGORY_INCREASE),
      recentTopics = listOf(FeedRecentTopic("photo", 0.42), FeedRecentTopic("food", 0.21))
    )
    show(FeedRecommendationSettingsState(preferences = preferences, interests = FeedInterests(), region = "cn"))

    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.PERSONALIZED).assertDoesNotExist()
    composeTestRule.onNodeWithText("Recommendations right now are editors’ picks in time order, not based on your interests.").assertExists()
    composeTestRule.onNodeWithText("42%").assertExists()
    composeTestRule.onNodeWithText("1 category adjusted").assertExists()
    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_recommendation_preferences.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.CATEGORIES).performClick()
    scrollTo(hasTestTag(FeedRecommendationSettingsTestTags.CATEGORY + "ai"))
    composeTestRule.onAllNodesWithText("Less").onFirst().performClick()
    scrollTo(hasTestTag(FeedRecommendationSettingsTestTags.PAUSE))
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.PAUSE).performClick()
    composeTestRule.onNodeWithText("Pause").performClick()
    assertThat(events).isEqualTo(listOf("category ai=reduce", "pause"))
  }

  @Test
  fun `paused shows when it resumes`() {
    show(FeedRecommendationSettingsState(preferences = FeedPreferences(pausedUntil = "2026-11-12T08:00:00Z"), interests = FeedInterests()))

    scrollTo(hasTestTag(FeedRecommendationSettingsTestTags.PAUSE))
    composeTestRule.onNodeWithText("Paused until", substring = true).performClick()
    assertThat(events).isEqualTo(listOf("resume"))
  }

  @Test
  fun `muted words list and add dialog`() {
    val keywords = listOf(
      FeedKeyword("考研", FeedKeyword.Mode.BLOCK),
      FeedKeyword("剧透", FeedKeyword.Mode.DOWNRANK)
    )
    show(FeedRecommendationSettingsState(preferences = FeedPreferences(), interests = FeedInterests(), keywords = keywords))

    scrollTo(hasTestTag(FeedRecommendationSettingsTestTags.KEYWORD_ADD))
    composeTestRule.onNodeWithText("Hide completely · Forever").assertExists()
    composeTestRule.onNodeWithText("Show less · Forever").assertExists()
    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_keywords_list.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.KEYWORD_DELETE + "剧透").performClick()

    assertThat(events).isEqualTo(listOf("remove 剧透"))
  }

  @Test
  fun `add word form`() {
    var form = KeywordForm()
    // 对话框窗口里的输入框在 Robolectric 下等不到空闲，单独测表单
    composeTestRule.setContent {
      SignalTheme {
        var current by remember { mutableStateOf(KeywordForm(text = "减肥")) }
        AddKeywordForm(form = current, onChange = {
          current = it
          form = it
        })
      }
    }
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.KEYWORD_BLOCK).performClick()
    composeTestRule.onNodeWithTag(FeedRecommendationSettingsTestTags.KEYWORD_DAYS + 7).performClick()
    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_keywords_add.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    assertThat(form).isEqualTo(KeywordForm(text = "减肥", block = true, days = 7))
    assertThat(KeywordForm(text = "  ").valid).isEqualTo(false)
    assertThat(KeywordForm(text = "字".repeat(21)).valid).isEqualTo(false)
  }
}
