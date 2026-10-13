/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CompletableDeferred
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
import org.thoughtcrime.securesms.feed.compose.FeedReplyPolicy
import org.thoughtcrime.securesms.feed.compose.FeedReplySettings
import org.thoughtcrime.securesms.feed.compose.FeedReplySettingsContent
import org.thoughtcrime.securesms.feed.compose.FeedReplySettingsTestTags
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedPostInsights
import org.thoughtcrime.securesms.feed.net.FeedPostSettings
import org.thoughtcrime.securesms.feed.net.FeedPostSettingsUpdate
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.net.FeedViewer
import java.io.File

/** 自己帖子「···」里的置顶、隐藏点赞数、查看数据（第三节第 26 条）。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedOwnPostTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private val me = FeedAuthor(profileId = "me", displayName = "我")
  private val mine = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "我发的", author = me, stats = FeedStats(likes = 5), viewer = FeedViewer(isAuthor = true))

  private class FakeSource(val card: FeedCard) : FeedSource by EmptyFeedSource {
    val updates = mutableListOf<FeedPostSettingsUpdate>()
    var settings = FeedPostSettings(replyPolicy = "friends", replyModeration = true)
    override suspend fun postSettings(contentId: String): FeedPostSettings = settings
    var settingsError: Exception? = null
    var insightsGate: CompletableDeferred<Unit>? = null
    override suspend fun timeline(tab: FeedTab, cursor: String?) = FeedTimelinePage(entries = listOf(FeedEntry(id = card.id, card = card)))
    override suspend fun updatePostSettings(contentId: String, update: FeedPostSettingsUpdate) {
      settingsError?.let { throw it }
      updates += update
    }
    override suspend fun postInsights(contentId: String): FeedPostInsights {
      insightsGate?.await()
      return FeedPostInsights(opens = 40, likes = 5, comments = 2, reposts = 1, saves = 3)
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

  private fun viewModelWith(source: FakeSource): FeedViewModel {
    return FeedViewModel(source, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords()).also { it.onTabShown(FeedTab.FOLLOWING) }
  }

  private fun FeedViewModel.card() = state.value.tab(FeedTab.FOLLOWING).cards.single()

  @Test
  fun `only my own posts get the actions, and each one flips`() {
    assertThat(FeedOwnPostAction.available(mine)).containsExactly(FeedOwnPostAction.PIN, FeedOwnPostAction.REPLY_SETTINGS, FeedOwnPostAction.HIDE_LIKES, FeedOwnPostAction.INSIGHTS)
    val flipped = mine.copy(pinned = true, stats = mine.stats.copy(likeCountHidden = true))
    assertThat(FeedOwnPostAction.available(flipped)).containsExactly(FeedOwnPostAction.UNPIN, FeedOwnPostAction.REPLY_SETTINGS, FeedOwnPostAction.SHOW_LIKES, FeedOwnPostAction.INSIGHTS)
    assertThat(FeedOwnPostAction.available(mine.copy(viewer = FeedViewer()))).isEmpty()
    assertThat(FeedOwnPostAction.available(mine.copy(kind = FeedCard.KIND_COMMENT))).isEmpty()
  }

  @Test
  fun `pin and hide likes change the card first and send only that field`() {
    val source = FakeSource(mine)
    val viewModel = viewModelWith(source)

    viewModel.onOwnPostAction(viewModel.card(), FeedOwnPostAction.PIN)
    viewModel.onOwnPostAction(viewModel.card(), FeedOwnPostAction.HIDE_LIKES)

    assertThat(viewModel.card().pinned).isTrue()
    assertThat(viewModel.card().stats.likeCountHidden).isTrue()
    assertThat(source.updates).containsExactly(FeedPostSettingsUpdate(pinned = true), FeedPostSettingsUpdate(likeCountHidden = true))
  }

  @Test
  fun `a fourth pin is turned back and says the limit`() {
    val source = FakeSource(mine).apply { settingsError = FeedApiException(409, code = "too_many_pinned") }
    val viewModel = viewModelWith(source)

    viewModel.onOwnPostAction(viewModel.card(), FeedOwnPostAction.PIN)

    assertThat(viewModel.card().pinned).isFalse()
    assertThat(viewModel.state.value.transientError).isEqualTo(FeedState.TransientError.PIN_LIMIT)
  }

  @Test
  fun `insights load into the dialog, closing it while loading keeps it closed`() {
    val source = FakeSource(mine)
    val viewModel = viewModelWith(source)

    viewModel.onOwnPostAction(viewModel.card(), FeedOwnPostAction.INSIGHTS)
    assertThat((viewModel.state.value.insights as FeedInsightsState.Ready).insights.opens).isEqualTo(40)
    viewModel.onInsightsDismissed()

    val gate = CompletableDeferred<Unit>()
    source.insightsGate = gate
    viewModel.onOwnPostAction(viewModel.card(), FeedOwnPostAction.INSIGHTS)
    assertThat(viewModel.state.value.insights).isEqualTo(FeedInsightsState.Loading)
    viewModel.onInsightsDismissed()
    gate.complete(Unit)
    assertThat(viewModel.state.value.insights).isNull()
  }

  @Test
  fun `my menu offers pin, hide likes and insights, pinned posts say so`() {
    val picked = mutableListOf<FeedOwnPostAction>()
    val pinned = mine.copy(id = "c2", text = "置顶的那条", pinned = true, stats = FeedStats(likes = 7, likeCountHidden = true))
    composeTestRule.setContent {
      SignalTheme {
        Column {
          FeedPostCard(card = pinned, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onOwnPostAction = { _, action -> picked += action })
        }
      }
    }

    // 隐藏了点赞数，作者自己照常看到数字
    composeTestRule.onNodeWithText("7").assertExists()
    composeTestRule.onNodeWithText("Pinned").assertExists()

    composeTestRule.onNodeWithContentDescription("More").performClick()
    composeTestRule.onNodeWithText("Show like count").assertExists()
    composeTestRule.onNodeWithText("View insights").assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.ownAction(FeedOwnPostAction.UNPIN)).performClick()
    assertThat(picked).containsExactly(FeedOwnPostAction.UNPIN)
  }

  @Test
  fun `insights dialog lists the five numbers`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedInsightsDialog(state = FeedInsightsState.Ready(FeedPostInsights(opens = 40, likes = 5, comments = 2, reposts = 1, saves = 3)), onDismiss = {})
      }
    }
    composeTestRule.onNodeWithText("Opens").assertExists()
    composeTestRule.onNodeWithText("40").assertExists()
    composeTestRule.onNodeWithText("Saves").assertExists()

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onNodeWithTag(FeedOwnPostTestTags.INSIGHTS_DIALOG).captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_insights.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `reply options load the current values and send only what changed`() {
    val source = FakeSource(mine)
    val viewModel = FeedPostReplySettingsViewModel("c1", source, dispatcher)
    assertThat(viewModel.state.value.settings).isEqualTo(FeedReplySettings(policy = FeedReplyPolicy.FRIENDS, moderation = true))

    viewModel.onChange(viewModel.state.value.settings.copy(commentsClosed = true))
    viewModel.onChange(viewModel.state.value.settings.copy(policy = FeedReplyPolicy.EVERYONE))

    assertThat(source.updates).containsExactly(FeedPostSettingsUpdate(commentsClosed = true), FeedPostSettingsUpdate(replyPolicy = "everyone"))
    assertThat(viewModel.state.value.settings).isEqualTo(FeedReplySettings(policy = FeedReplyPolicy.EVERYONE, commentsClosed = true, moderation = true))
  }

  @Test
  fun `a failed reply option change turns back`() {
    val source = FakeSource(mine).apply { settingsError = IllegalStateException("offline") }
    val viewModel = FeedPostReplySettingsViewModel("c1", source, dispatcher)

    viewModel.onChange(viewModel.state.value.settings.copy(moderation = false))

    assertThat(viewModel.state.value.settings.moderation).isTrue()
    assertThat(viewModel.state.value.saveFailed).isTrue()
  }

  @Test
  fun `an unknown reply policy reads as everyone`() {
    assertThat(FeedPostSettings(replyPolicy = "mentioned").toReplySettings().policy).isEqualTo(FeedReplyPolicy.EVERYONE)
    assertThat(FeedReplySettings().diff(FeedReplySettings())).isNull()
  }

  @Test
  fun `voice comments switch reads the setting and sends only voice_comments`() {
    val before = FeedPostSettings(voiceComments = false).toReplySettings()
    assertThat(before.voiceComments).isFalse()
    assertThat(FeedPostSettings().toReplySettings().voiceComments).isTrue()
    assertThat(before.diff(before.copy(voiceComments = true))).isEqualTo(FeedPostSettingsUpdate(voiceComments = true))
  }

  @Test
  fun `voice comments switch is only on a published post, not on the compose sheet`() {
    var changed: FeedReplySettings? = null
    composeTestRule.setContent {
      SignalTheme {
        Column {
          FeedPostReplySettingsContent(state = FeedPostReplySettingsState(loading = false), onChange = { changed = it }, onRetry = {})
          FeedReplySettingsContent(settings = FeedReplySettings(), onChange = {})
        }
      }
    }
    composeTestRule.onAllNodesWithTag(FeedReplySettingsTestTags.VOICE).assertCountEquals(1)
    composeTestRule.onNodeWithTag(FeedReplySettingsTestTags.VOICE).performClick()
    assertThat(changed?.voiceComments).isEqualTo(false)
  }

  @Test
  fun `reply options sheet shows the current values and the note`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedPostReplySettingsContent(
          state = FeedPostReplySettingsState(loading = false, settings = FeedReplySettings(policy = FeedReplyPolicy.FRIENDS, moderation = true)),
          onChange = {},
          onRetry = {}
        )
      }
    }
    composeTestRule.onNodeWithText("Reply options").assertExists()
    composeTestRule.onNodeWithText("New settings apply to comments from now on. Existing comments stay as they are.").assertExists()

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onNodeWithTag(FeedOwnPostTestTags.REPLY_SETTINGS).captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_reply_settings_after.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
