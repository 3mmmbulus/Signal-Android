/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.feed.compose.FeedComposeActivity
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedQuoteTarget
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedAuthor
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedLimits
import org.thoughtcrime.securesms.feed.net.FeedQuotedCard
import org.thoughtcrime.securesms.feed.net.FeedTokenSource
import java.io.File

/**
 * 「引用」（第三节第 26 条）：分享菜单里进发布页，发出去带 `quote_of`；卡片里画被引用的那条，看不到了是灰字（第 41 条）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedQuoteTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private lateinit var server: MockWebServer
  private lateinit var source: RemoteFeedSource

  private val original = FeedCard(id = "q1", kind = FeedCard.KIND_POST, text = "山里起雾了", author = FeedAuthor(profileId = "p1", displayName = "山风"), shareable = true)

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    val tokens = object : FeedTokenSource {
      override suspend fun token(forceRefresh: Boolean) = "t"
    }
    source = RemoteFeedSource(FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `quoted posts are fetched once more and a gone one is kept as unavailable`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""))
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post","text":"A","quote_of":"q1"}},""" +
          """{"id":"b","state":"ok","card":{"kind":"post","text":"B","quote_of":"gone"}},""" +
          """{"id":"c","state":"ok","card":{"kind":"post","text":"C","quote_of":"a"}}]}"""
      )
    )
    server.enqueue(
      MockResponse().setBody("""{"cards":[{"id":"q1","state":"ok","card":{"kind":"post","text":"原文","author":{"id":"p1","display_name":"山风"}}},{"id":"gone","state":"unavailable"}]}""")
    )

    val cards = source.timeline(FeedTab.FOLLOWING, cursor = null).entries.map { it.card }

    assertThat(cards.map { it.quoted?.id }).containsExactly("q1", "gone", "a")
    assertThat(cards[0].quoted?.card?.text).isEqualTo("原文")
    assertThat(cards[1].quoted).isNotNull()
    assertThat(cards[1].quoted?.card).isNull()
    // 同一页里已有的 a 不重取
    assertThat(cards[2].quoted?.card?.text).isEqualTo("A")
    server.takeRequest()
    server.takeRequest()
    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"ids":["q1","gone"]}""")
  }

  @Test
  fun `the engine's inline quoted block is used without another fetch`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""))
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post","text":"A","quote_of":"q1"},""" +
          """"quoted":{"id":"q1","state":"ok","card":{"kind":"post","text":"原文"},"stats":{"likes":3}}},""" +
          """{"id":"b","state":"ok","card":{"kind":"post","text":"B","quote_of":"q2"},"quoted":{"id":"q2","state":"deleted"}},""" +
          """{"id":"c","state":"ok","card":{"kind":"post","text":"C","quote_of":"q3"},"quoted":{"id":"q3","state":"unavailable"}}]}"""
      )
    )

    val cards = source.timeline(FeedTab.FOLLOWING, cursor = null).entries.map { it.card }

    assertThat(cards[0].quoted?.card?.id).isEqualTo("q1")
    assertThat(cards[0].quoted?.card?.stats?.likes).isEqualTo(3)
    assertThat(cards[1].quoted).isEqualTo(FeedQuotedCard("q2", null, deleted = true))
    assertThat(cards[2].quoted).isEqualTo(FeedQuotedCard("q3", null, deleted = false))
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `a page still loads when the quoted posts cannot be fetched`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a"}]}"""))
    server.enqueue(MockResponse().setBody("""{"cards":[{"id":"a","state":"ok","card":{"kind":"post","text":"A","quote_of":"q1"}}]}"""))
    server.enqueue(MockResponse().setResponseCode(500))

    val cards = source.timeline(FeedTab.FOLLOWING, cursor = null).entries.map { it.card }

    assertThat(cards.map { it.id }).containsExactly("a")
    assertThat(cards[0].quoted).isNull()
  }

  @Test
  fun `a quote sends quote_of and still needs some text`() {
    val quote = FeedQuoteTarget.of(original)!!
    val limits = FeedLimits()

    assertThat(FeedDraft(quote = quote).problem(limits, minorMode = false)).isEqualTo(FeedDraft.Problem.EMPTY)
    assertThat(FeedDraft(text = "同感", quote = quote).toRequest().quoteOf).isEqualTo("q1")
    assertThat(FeedDraft(text = "同感").toRequest().quoteOf).isNull()
  }

  @Test
  fun `only shareable posts can be quoted`() {
    assertThat(FeedQuoteTarget.of(original.copy(shareable = false))).isNull()
    assertThat(FeedQuoteTarget.of(original.copy(kind = FeedCard.KIND_COMMENT))).isNull()
    assertThat(FeedQuoteTarget.of(original)).isEqualTo(FeedQuoteTarget(id = "q1", authorName = "山风", snippet = "山里起雾了"))
  }

  @Test
  fun `share menu quote opens the compose page with the post`() {
    composeTestRule.setContent {
      SignalTheme {
        FeedPostCard(card = original, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
      }
    }
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.SHARE).performClick()
    composeTestRule.onNodeWithTag(FeedPostCardTestTags.QUOTE).performClick()

    val started = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
    assertThat(started.component?.className).isEqualTo(FeedComposeActivity::class.java.name)
    assertThat(IntentCompat.getParcelableExtra(started, "feed_quote_target", FeedQuoteTarget::class.java)?.id).isEqualTo("q1")
  }

  @Test
  fun `cards draw the quoted post, or a grey line when it is gone`() {
    val quoting = FeedCard(id = "a", kind = FeedCard.KIND_POST, text = "同感，今早也是", author = FeedAuthor(profileId = "p2", displayName = "林间"), quoteOf = "q1", quoted = FeedQuotedCard("q1", original))
    val orphan = quoting.copy(id = "b", quoted = FeedQuotedCard("q1", null))
    val deleted = quoting.copy(id = "c", quoted = FeedQuotedCard("q1", null, deleted = true))
    composeTestRule.setContent {
      SignalTheme {
        androidx.compose.foundation.layout.Column {
          FeedPostCard(card = quoting, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
          FeedPostCard(card = orphan, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
          FeedPostCard(card = deleted, showIpRegion = false, onLikeClick = {}, onNotInterestedClick = {})
        }
      }
    }

    composeTestRule.onNodeWithTag(FeedQuotedBoxTestTags.QUOTED).assertExists()
    composeTestRule.onAllNodesWithTag(FeedQuotedBoxTestTags.QUOTED_GONE).assertCountEquals(2)
    composeTestRule.onNodeWithText("The original post was deleted by its author").assertExists()

    val dir = System.getenv("FEED_SCREENSHOT_DIR") ?: return
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    File(dir).mkdirs()
    File(dir, "feed_quote.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
