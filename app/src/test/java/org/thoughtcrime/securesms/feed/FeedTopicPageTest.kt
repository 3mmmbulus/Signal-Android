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
import assertk.assertions.isNull
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
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedTopic
import org.thoughtcrime.securesms.feed.net.FeedTopicPage
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode
import org.thoughtcrime.securesms.feed.people.FeedPeopleScreen
import org.thoughtcrime.securesms.feed.people.FeedPeopleState
import org.thoughtcrime.securesms.feed.people.FeedPeopleViewModel
import org.thoughtcrime.securesms.feed.topic.FeedTopicScreen
import org.thoughtcrime.securesms.feed.topic.FeedTopicState
import org.thoughtcrime.securesms.feed.topic.FeedTopicTestTags
import org.thoughtcrime.securesms.feed.topic.FeedTopicViewModel
import java.io.File

/** 话题页：带话题的帖子 + 关注话题（第三节第 18 条），和「关注的话题」名单。设了 `FEED_SCREENSHOT_DIR` 时截图。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedTopicPageTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()

  private fun post(id: String, text: String = "周末去爬山") = FeedCard(
    id = id,
    kind = FeedCard.KIND_POST,
    text = text,
    author = FeedAuthor(profileId = "p1", displayName = "山风"),
    topics = listOf(FeedTopic(slug = "hiking", label = "徒步"), FeedTopic(slug = "photo", label = "摄影"))
  )

  private inner class FakeSource(var followed: List<String> = emptyList()) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    var followError: Exception? = null
    var pageError: Exception? = null
    val pages = mutableMapOf<String?, FeedTopicPage>()
    override suspend fun followedTopics(): List<String> = followed
    override suspend fun setFollowingTopic(slug: String, following: Boolean) {
      followError?.let { throw it }
      calls += "$slug $following"
    }
    override suspend fun topicPosts(slug: String, cursor: String?): FeedTopicPage {
      pageError?.let { throw it }
      calls += "page $slug ${cursor ?: "-"}"
      return pages[cursor] ?: FeedTopicPage()
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
  fun `the page loads posts and whether I follow, then pages on`() {
    val source = FakeSource().apply {
      pages[null] = FeedTopicPage(posts = listOf(post("a"), post("b")), cursor = "c1", following = true)
      pages["c1"] = FeedTopicPage(posts = listOf(post("b"), post("c")), cursor = null, following = true)
    }
    val viewModel = FeedTopicViewModel("hiking", source, dispatcher)
    assertThat(viewModel.state.value.following).isTrue()

    viewModel.loadMore()

    assertThat(viewModel.state.value.posts.map { it.id }).containsExactly("a", "b", "c")
    assertThat(viewModel.state.value.cursor).isNull()
    assertThat(source.calls).containsExactly("page hiking -", "page hiking c1")
  }

  @Test
  fun `following flips at once, a failure turns it back`() {
    val source = FakeSource().apply { pages[null] = FeedTopicPage(following = false) }
    val viewModel = FeedTopicViewModel("hiking", source, dispatcher)

    viewModel.toggleFollow()
    assertThat(viewModel.state.value.following).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedTopicState.Notice.FOLLOWED)

    source.followError = IllegalStateException("offline")
    viewModel.toggleFollow()
    assertThat(viewModel.state.value.following).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedTopicState.Notice.FAILED)
    assertThat(source.calls).containsExactly("page hiking -", "hiking true")
  }

  @Test
  fun `an unknown topic says so and cannot be followed`() {
    val source = FakeSource().apply { pageError = FeedApiException(404, code = "not_found") }
    val viewModel = FeedTopicViewModel("bad slug", source, dispatcher)

    viewModel.toggleFollow()

    assertThat(viewModel.state.value.gone).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
    assertThat(viewModel.state.value.following).isFalse()
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
    var tapped: FeedTopic? = null
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = post("c"), showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onTopicClick = { tapped = it })
      }
    }
    composeTestRule.onNodeWithText("#摄影").assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.topic("hiking")).performClick()
    assertThat(tapped?.slug).isEqualTo("hiking")
  }

  @Test
  fun `the page shows the topic, a follow button and its posts`() {
    var toggled = 0
    composeTestRule.setContent {
      SignalTheme {
        FeedTopicScreen(
          label = "徒步",
          state = FeedTopicState(posts = listOf(post("a"), post("b", text = "山顶的风很大")), loading = false, loadedOnce = true),
          onBack = {},
          onRetry = {},
          onToggleFollow = { toggled++ },
          onLoadMore = {},
          onLikeClick = {},
          onOpenPost = {},
          onReportPost = {},
          onTopicClick = {},
          onNoticeShown = {}
        )
      }
    }
    composeTestRule.onNodeWithText("山顶的风很大").assertExists()
    composeTestRule.onNodeWithTag(FeedTopicTestTags.FOLLOW).performClick()
    assertThat(toggled).isEqualTo(1)

    System.getenv("FEED_SCREENSHOT_DIR")?.let { dir ->
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_topic_page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `following and empty read differently`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedTopicScreen(
          label = "徒步",
          state = FeedTopicState(following = true, loading = false, loadedOnce = true),
          onBack = {},
          onRetry = {},
          onToggleFollow = {},
          onLoadMore = {},
          onLikeClick = {},
          onOpenPost = {},
          onReportPost = {},
          onTopicClick = {},
          onNoticeShown = {}
        )
      }
    }
    composeTestRule.onNodeWithTag(FeedTopicTestTags.UNFOLLOW).assertExists()
    composeTestRule.onNodeWithText("No posts with this topic yet.").assertExists()
  }

  @Test
  fun `a followed topic in the list opens its page`() {
    val source = FakeSource(followed = listOf("food", "my-own-tag"))
    val viewModel = FeedPeopleViewModel(FeedPeopleMode.FOLLOWED_TOPICS, source, dispatcher)
    var opened: String? = null
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
          onRemove = viewModel::remove,
          onOpenTopic = { opened = it }
        )
      }
    }
    composeTestRule.onNodeWithText("#my-own-tag").performClick()
    assertThat(opened).isEqualTo("my-own-tag")
  }
}
