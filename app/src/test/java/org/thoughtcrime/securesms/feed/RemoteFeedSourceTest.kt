/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.net.Uri
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.feed.compose.FeedAudienceMember
import org.thoughtcrime.securesms.feed.compose.FeedDraft
import org.thoughtcrime.securesms.feed.compose.FeedVisibility
import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedTokenSource

/**
 * 列表只给 id，卡片再走 Card API；不可用的跳过，推荐没有 cursor。
 */
class RemoteFeedSourceTest {

  private lateinit var server: MockWebServer
  private lateinit var source: RemoteFeedSource

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    val tokens = object : FeedTokenSource {
      override suspend fun token(forceRefresh: Boolean) = "t"
    }
    source = RemoteFeedSource(FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens), selfProfileId = { "me" })
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `following page is hydrated in order and unavailable cards are dropped`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","reason":"following"},{"id":"b"},{"id":"c"}],"cursor":"next"}"""))
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post","text":"A"}},{"id":"b","state":"unavailable"},{"id":"c","state":"ok","card":{"kind":"live_room"}}]}"""
      )
    )

    val page = source.timeline(FeedTab.FOLLOWING, cursor = null)

    assertThat(page.entries.map { it.id }).containsExactly("a", "c")
    assertThat(page.entries.first().reason).isEqualTo("following")
    assertThat(page.nextCursor).isEqualTo("next")
    server.takeRequest()
    assertThat(server.takeRequest().body.readUtf8()).isEqualTo("""{"ids":["a","b","c"]}""")
  }

  @Test
  fun `my own cards are marked as mine`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a"},{"id":"b"}]}"""))
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"kind":"post","author":{"id":"me","display_name":"我"}}},{"id":"b","state":"ok","card":{"kind":"post","author":{"id":"p2","display_name":"山风"}}}]}"""
      )
    )

    val page = source.timeline(FeedTab.FOLLOWING, cursor = null)

    assertThat(page.entries.map { it.card.viewer.isAuthor }).containsExactly(true, false)
  }

  @Test
  fun `recommend keeps going until a page comes back empty`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a"}]}"""))
    server.enqueue(MockResponse().setBody("""{"cards":[{"id":"a","state":"ok","card":{"kind":"post"}}]}"""))
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))

    val first = source.timeline(FeedTab.RECOMMEND, cursor = null)
    val second = source.timeline(FeedTab.RECOMMEND, cursor = first.nextCursor)

    assertThat(first.nextCursor == null).isFalse()
    assertThat(second.nextCursor).isNull()
    server.takeRequest()
    server.takeRequest()
    assertThat(server.takeRequest().requestUrl!!.queryParameter("cursor")).isNull()
  }

  @Test
  fun `config comes from the engine`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"enabled":true,"region":"cn","tabs":["recommend","following"],"recommend_mode":"editorial_chronological",""" +
          """"personalization_toggle_visible":false,"hot_enabled":false,"ip_region_visible":true,"media_base_url":"https://m.example/",""" +
          """"card_ttl_seconds":600,"limits":{"post_text_chars":500,"post_images":10,"article_chars":10000,"edit_window_seconds":900},"minor_mode":true}"""
      )
    )

    val config = source.config()

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/config")
    assertThat(config.enabled).isTrue()
    assertThat(config.ipRegionVisible).isTrue()
    assertThat(config.minorMode).isTrue()
    assertThat(config.mediaBaseUrl).isEqualTo("https://m.example/")
    assertThat(FeedTab.fromApiNames(config.tabs)).isEqualTo(FeedTab.entries.toList())
  }

  @Test
  fun `hidden config turns the feed off`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"enabled":false,"region":"global","tabs":["recommend","following"]}"""))

    assertThat(source.config().enabled).isFalse()
  }

  @Test
  fun `publish uploads images, sends their keys with the nonce, and a retry does not upload again`() = runBlocking {
    val api = FeedApi(
      baseUrl = server.url("/"),
      client = OkHttpClient(),
      tokens = object : FeedTokenSource {
        override suspend fun token(forceRefresh: Boolean) = "t"
      }
    )
    val withImages = RemoteFeedSource(api, readImage = { byteArrayOf(1, 2, 3) })
    val first = mockk<Uri>()
    val second = mockk<Uri>()
    val draft = FeedDraft(text = "hi", images = listOf(first, second))

    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"u1","key":"m/a"}"""))
    server.enqueue(MockResponse().setResponseCode(204).setHeader("Upload-Offset", "3"))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"u2","key":"m/b"}"""))
    server.enqueue(MockResponse().setResponseCode(204).setHeader("Upload-Offset", "3"))
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p1","state":"pending","version":1}"""))

    assertFailure { runBlocking { withImages.publish(draft, clientNonce = "n1") } }
    val response = withImages.publish(draft, clientNonce = "n1")

    assertThat(response.id).isEqualTo("p1")
    val requests = List(6) { server.takeRequest() }
    assertThat(requests.map { it.method }).containsExactly("POST", "PATCH", "POST", "PATCH", "POST", "POST")
    val body = requests.last().body.readUtf8()
    assertThat(body.contains(""""client_nonce":"n1"""")).isTrue()
    assertThat(body.contains(""""media_keys":["m/a","m/b"]""")).isTrue()
  }

  @Test
  fun `include posts carry a freshly signed audience token`() = runBlocking {
    val api = FeedApi(
      baseUrl = server.url("/"),
      client = OkHttpClient(),
      tokens = object : FeedTokenSource {
        override suspend fun token(forceRefresh: Boolean) = "t"
      }
    )
    val signed = mutableListOf<Pair<String, List<String>>>()
    val withAudience = RemoteFeedSource(api, signAudience = { kind, acis -> signed += kind to acis; "jwt-${signed.size}" })
    val draft = FeedDraft(
      text = "hi",
      visibility = FeedVisibility.INCLUDE,
      audience = listOf(FeedAudienceMember(1, "a"), FeedAudienceMember(2, "b"), FeedAudienceMember(3, "a"))
    )
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p1","state":"pending","version":1}"""))
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p2","state":"pending","version":1}"""))

    withAudience.publish(draft, clientNonce = "n1")
    withAudience.publish(draft.copy(visibility = FeedVisibility.FRIENDS), clientNonce = "n2")

    assertThat(signed).containsExactly("include" to listOf("a", "b"))
    assertThat(server.takeRequest().body.readUtf8().contains(""""audience":{"audience_token":"jwt-1"}""")).isTrue()
    assertThat(server.takeRequest().body.readUtf8().contains("audience")).isFalse()
  }
}
