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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.thoughtcrime.securesms.feed.compose.FeedComposeViewModel
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedDraftKind
import org.thoughtcrime.securesms.feed.compose.FeedQuoteTarget
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailState
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailViewModel
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedCreatePostResponse
import org.thoughtcrime.securesms.feed.net.FeedEntry
import org.thoughtcrime.securesms.feed.net.FeedProfilePage
import org.thoughtcrime.securesms.feed.net.FeedProfilePostsPage
import org.thoughtcrime.securesms.feed.net.FeedRepostedBy
import org.thoughtcrime.securesms.feed.net.FeedSearchPage
import org.thoughtcrime.securesms.feed.net.FeedStats
import org.thoughtcrime.securesms.feed.net.FeedTimelinePage
import org.thoughtcrime.securesms.feed.net.FeedTokenSource
import org.thoughtcrime.securesms.feed.net.FeedTopicPage
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageState
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageViewModel
import org.thoughtcrime.securesms.feed.search.FeedSearchState
import org.thoughtcrime.securesms.feed.search.FeedSearchViewModel
import org.thoughtcrime.securesms.feed.search.InMemoryFeedSearchHistory
import org.thoughtcrime.securesms.feed.topic.FeedTopicState
import org.thoughtcrime.securesms.feed.topic.FeedTopicViewModel
import java.io.File

/** 转发到动态（第三节第 26 条）：分享菜单里转发 / 取消，关注流里「谁 转发了」。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedRepostTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val dispatcher = UnconfinedTestDispatcher()
  private lateinit var server: MockWebServer
  private lateinit var source: RemoteFeedSource

  private val card = FeedCard(id = "c1", kind = FeedCard.KIND_POST, text = "山里起雾了", author = FeedAuthor(profileId = "p1", displayName = "山风"), shareable = true, stats = FeedStats(reposts = 2))

  private class FakeSource(val card: FeedCard) : FeedSource by EmptyFeedSource {
    val calls = mutableListOf<String>()
    var error: Exception? = null
    override suspend fun timeline(tab: FeedTab, cursor: String?) = FeedTimelinePage(entries = listOf(FeedEntry(id = card.id, card = card)))
    override suspend fun profile(profileId: String) = FeedProfilePage(id = profileId, displayName = "山风")
    override suspend fun profilePosts(profileId: String, cursor: String?) = FeedProfilePostsPage(posts = listOf(card))
    override suspend fun topicPosts(slug: String, cursor: String?) = FeedTopicPage(posts = listOf(card))
    override suspend fun search(query: String, scope: String, cursor: String?) = FeedSearchPage(posts = listOf(card))
    override suspend fun setReposted(contentId: String, reposted: Boolean) {
      error?.let { throw it }
      calls += "$contentId $reposted"
    }
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    server = MockWebServer()
    server.start()
    val tokens = object : FeedTokenSource {
      override suspend fun token(forceRefresh: Boolean) = "t"
    }
    source = RemoteFeedSource(FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens))
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    server.shutdown()
  }

  @Test
  fun `repost items get the reposter's name, looked up once per person`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","reason":"repost","reposted_by":"p9"},{"id":"b","reason":"repost","reposted_by":"p9"},{"id":"c","reason":"following"}]}"""))
    // 先查转发人，再取卡片
    server.enqueue(MockResponse().setBody("""{"id":"p9","display_name":"林间"}"""))
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post"},"me":{"reposted":true}},{"id":"b","state":"ok","card":{"kind":"post"}},{"id":"c","state":"ok","card":{"kind":"post"}}]}"""
      )
    )

    val cards = source.timeline(FeedTab.FOLLOWING, cursor = null).entries.map { it.card }

    assertThat(cards.map { it.repostedBy }).containsExactly(FeedRepostedBy("p9", "林间"), FeedRepostedBy("p9", "林间"), null)
    assertThat(cards[0].viewer.reposted).isTrue()
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun `a reposter whose profile cannot be read still shows as a repost`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","reason":"repost","reposted_by":"p9"}]}"""))
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setBody("""{"cards":[{"id":"a","state":"ok","card":{"kind":"post"}}]}"""))

    val cards = source.timeline(FeedTab.FOLLOWING, cursor = null).entries.map { it.card }

    assertThat(cards.single().repostedBy).isEqualTo(FeedRepostedBy("p9", null))
  }

  @Test
  fun `repost is PUT and undo is DELETE`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    source.setReposted("c1", true)
    source.setReposted("c1", false)

    val put = server.takeRequest()
    val delete = server.takeRequest()
    assertThat(put.method + " " + put.path).isEqualTo("PUT /v1/feed/posts/c1/repost")
    assertThat(delete.method + " " + delete.path).isEqualTo("DELETE /v1/feed/posts/c1/repost")
  }

  @Test
  fun `the feed reposts optimistically and puts it back on failure`() {
    val fake = FakeSource(card)
    val viewModel = FeedViewModel(fake, dispatcher, blockedKeywords = InMemoryFeedBlockedKeywords()).also { it.onTabShown(FeedTab.FOLLOWING) }
    fun current() = viewModel.state.value.tab(FeedTab.FOLLOWING).cards.single()

    viewModel.toggleRepost(current())
    assertThat(current().viewer.reposted).isTrue()
    assertThat(current().stats.reposts).isEqualTo(3)
    assertThat(viewModel.state.value.repostedNotice).isTrue()

    fake.error = IllegalStateException("net")
    viewModel.toggleRepost(current())
    assertThat(current().viewer.reposted).isTrue()
    assertThat(viewModel.state.value.transientError).isEqualTo(FeedState.TransientError.ACTION_FAILED)
    assertThat(fake.calls).containsExactly("c1 true")
  }

  @Test
  fun `the detail page reposts the same way`() {
    val fake = FakeSource(card)
    val viewModel = FeedPostDetailViewModel(card, fake, dispatcher)

    viewModel.toggleRepost()

    assertThat(viewModel.state.value.post.viewer.reposted).isTrue()
    assertThat(viewModel.state.value.notice).isEqualTo(FeedPostDetailState.Notice.REPOSTED)
  }

  @Test
  fun `profile, topic and search pages repost the same way`() {
    val fake = FakeSource(card)

    val profile = FeedProfilePageViewModel("p1", fake, dispatcher)
    profile.toggleRepost(profile.state.value.posts.single())
    assertThat(profile.state.value.posts.single().viewer.reposted).isTrue()
    assertThat(profile.state.value.notice).isEqualTo(FeedProfilePageState.Notice.REPOSTED)

    val topic = FeedTopicViewModel("hiking", fake, dispatcher)
    topic.toggleRepost(topic.state.value.posts.single())
    assertThat(topic.state.value.posts.single().stats.reposts).isEqualTo(3)
    assertThat(topic.state.value.notice).isEqualTo(FeedTopicState.Notice.REPOSTED)

    val search = FeedSearchViewModel(fake, InMemoryFeedSearchHistory(), dispatcher, debounceMillis = 0)
    search.onQueryChange("山")
    search.submit()
    search.toggleRepost(search.state.value.results!!.posts.single())
    assertThat(search.state.value.results!!.posts.single().viewer.reposted).isTrue()

    fake.error = IllegalStateException("net")
    search.toggleRepost(search.state.value.results!!.posts.single())
    assertThat(search.state.value.results!!.posts.single().viewer.reposted).isTrue()
    assertThat(search.state.value.notice).isEqualTo(FeedSearchState.Notice.FAILED)

    assertThat(fake.calls).containsExactly("c1 true", "c1 true", "c1 true")
  }

  @Test
  fun `a quote that went away is dropped and the text kept`() {
    val gone = object : FeedSource by EmptyFeedSource {
      override suspend fun publish(draft: FeedDraft, clientNonce: String): FeedCreatePostResponse = throw FeedApiException(400, "quote_unavailable")
    }
    val viewModel = FeedComposeViewModel(quoteTarget = FeedQuoteTarget.of(card), source = gone, ioDispatcher = dispatcher)
    viewModel.onTextChanged("同感")

    viewModel.publish()

    assertThat(viewModel.state.value.quoteUnavailable).isTrue()
    assertThat(viewModel.state.value.failed).isFalse()
    assertThat(viewModel.state.value.draft.quote).isNull()
    assertThat(viewModel.state.value.draft.text).isEqualTo("同感")
  }

  @Test
  fun `a quote stays a post`() {
    val viewModel = FeedComposeViewModel(quoteTarget = FeedQuoteTarget.of(card), source = FakeSource(card), ioDispatcher = dispatcher)

    viewModel.onKindChanged(FeedDraftKind.ARTICLE)

    assertThat(viewModel.state.value.draft.kind).isEqualTo(FeedDraftKind.POST)
  }

  @Test
  fun `share menu offers repost and undo, and repost rows say who`() {
    val reposted = mutableListOf<String>()
    composeTestRule.setContent {
      SignalTheme {
        androidx.compose.foundation.layout.Column {
          FeedPostCard(card = card.copy(repostedBy = FeedRepostedBy("p9", "林间")), showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {}, onRepostClick = { reposted += "${it.id} ${it.viewer.reposted}" })
        }
      }
    }

    composeTestRule.onNodeWithTag(FeedPostCardTestTags.REPOSTED_BY).assertExists()
    composeTestRule.onNodeWithText("林间 reposted").assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE).performClick()
    composeTestRule.onNodeWithText("Repost").assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE_TO_STORY).assertExists()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.REPOST).performClick()
    assertThat(reposted).containsExactly("c1 false")

    val dir = System.getenv("FEED_SCREENSHOT_DIR")
    if (dir != null) {
      val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
      File(dir).mkdirs()
      File(dir, "feed_repost.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun `cards without a repost callback have no repost item`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = card.copy(viewer = card.viewer.copy(reposted = true)), showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
      }
    }
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.REPOSTED_BY).assertDoesNotExist()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE).performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.REPOST).assertDoesNotExist()
    assertThat(card.repostedBy).isNull()
    assertThat(card.viewer.reposted).isFalse()
  }
}
