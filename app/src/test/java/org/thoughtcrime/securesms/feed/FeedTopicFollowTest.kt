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
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode
import org.thoughtcrime.securesms.feed.people.FeedPeopleScreen
import org.thoughtcrime.securesms.feed.people.FeedPeopleState
import org.thoughtcrime.securesms.feed.people.FeedPeopleViewModel
import java.io.File

/** 关注话题和「关注的话题」名单（第三节第 18 条）。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedTopicFollowTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private class FakeSource(var followed: List<String> = emptyList()) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    var error: Exception? = null
    override suspend fun followedTopics(): List<String> = followed
    override suspend fun setFollowingTopic(slug: String, following: Boolean) {
      error?.let { throw it }
      calls += "$slug $following"
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

  @Test
  fun `the sheet knows whether I follow the topic and flips`() {
    val source = FakeSource(followed = listOf("food"))
    val viewModel = FeedTopicFollowViewModel("hiking", source, dispatcher)
    assertThat(viewModel.state.value.following).isFalse()

    viewModel.toggle()
    assertThat(viewModel.state.value.following).isTrue()
    viewModel.toggle()

    assertThat(source.calls).containsExactly("hiking true", "hiking false")
    assertThat(FeedTopicFollowViewModel("food", source, dispatcher).state.value.following).isTrue()
  }

  @Test
  fun `a failed follow turns back and says so`() {
    val source = FakeSource().apply { error = IllegalStateException("offline") }
    val viewModel = FeedTopicFollowViewModel("hiking", source, dispatcher)

    viewModel.toggle()

    assertThat(viewModel.state.value.following).isFalse()
    assertThat(viewModel.state.value.changeFailed).isTrue()
  }

  @Test
  fun `the followed list unfollows like the other lists remove`() {
    val source = FakeSource(followed = listOf("hiking", "food"))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOWED_TOPICS, source, dispatcher)
    assertThat(viewModel.state.value.rows.map { it.topic }).containsExactly("hiking", "food")

    viewModel.remove(viewModel.state.value.rows.first())

    assertThat(viewModel.state.value.rows.map { it.topic }).containsExactly("food")
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPeopleState.Notice.UNFOLLOWED)
    assertThat(source.calls).containsExactly("hiking false")
  }

  @Test
  fun `each topic on a card is its own tap target`() {
    val card = FeedCard(
      id = "c",
      kind = FeedCard.KIND_POST,
      text = "周末去爬山",
      author = FeedAuthor(profileId = "p1", displayName = "山风"),
      topics = listOf(FeedTopic(slug = "hiking", label = "徒步"), FeedTopic(slug = "photo", label = "摄影"))
    )
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = card, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
      }
    }
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.topic("hiking")).assertExists()
    composeTestRule.onNodeWithText("#摄影").assertExists()
  }

  @Test
  fun `the sheet offers follow, then following`() {
    var toggled = 0
    composeTestRule.setContent {
      SignalTheme {
        FeedTopicFollowContent(label = "徒步", state = FeedTopicFollowState(loading = false), onToggle = { toggled++ }, onRetry = {})
      }
    }
    composeTestRule.onNodeWithText("#徒步").assertExists()
    composeTestRule.onNodeWithTag(FeedTopicFollowTestTags.FOLLOW).performClick()
    assertThat(toggled).isEqualTo(1)

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onNodeWithTag(FeedTopicFollowTestTags.SHEET).captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_topic_follow.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `followed topics list shows names and an unfollow button`() {
    val source = FakeSource(followed = listOf("food", "my-own-tag"))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOWED_TOPICS, source, dispatcher)
    composeTestRule.setContent {
      SignalTheme {
        FeedPeopleScreen(
          state = viewModel.state.value,
          mode = FeedPeopleMode.FOLLOWED_TOPICS,
          onBack = {},
          onRetry = {},
          onApprove = {},
          onDecline = {},
          onUnblock = {},
          onOpenProfile = {},
          onNoticeShown = {},
          onRemove = viewModel::remove
        )
      }
    }
    composeTestRule.onNodeWithText("#my-own-tag").assertExists()
    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_followed_topics.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }
}
