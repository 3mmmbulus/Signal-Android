/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 客户端接口契约（`docs/adr/0071/client-api-2026-10.md`）的请求形状与错误处理。全部在本机 MockWebServer 上。
 */
class FeedApiTest {

  private lateinit var server: MockWebServer
  private lateinit var tokens: CountingTokens
  private lateinit var api: FeedApi

  private class CountingTokens : FeedTokenSource {
    var issued = 0
    var refreshes = 0
    override suspend fun token(forceRefresh: Boolean): String {
      if (forceRefresh) refreshes++
      issued++
      return "t$issued"
    }
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    tokens = CountingTokens()
    api = FeedApi(baseUrl = server.url("/"), client = OkHttpClient(), tokens = tokens)
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `timeline sends tab, cursor, clamped limit and bearer token`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"entries":[],"next_cursor":null}"""))

    api.timeline("following", cursor = "abc", limit = 500)

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/timeline")
    assertThat(request.requestUrl!!.queryParameter("tab")).isEqualTo("following")
    assertThat(request.requestUrl!!.queryParameter("cursor")).isEqualTo("abc")
    assertThat(request.requestUrl!!.queryParameter("limit")).isEqualTo("50")
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer t1")
  }

  @Test
  fun `first page has no cursor parameter`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"entries":[]}"""))

    api.timeline("recommend", cursor = null)

    assertThat(server.takeRequest().requestUrl!!.queryParameter("cursor")).isNull()
  }

  @Test
  fun `401 refreshes the token and retries exactly once`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setBody("""{"entries":[]}"""))

    api.timeline("recommend", cursor = null)

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(tokens.refreshes).isEqualTo(1)
    server.takeRequest()
    assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer t2")
  }

  @Test
  fun `second 401 is an error, not a third request`() {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(401))

    assertFailure { runBlocking { api.timeline("recommend", cursor = null) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::status).isEqualTo(401)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `403 feed_disabled is recognised`() {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"feed_disabled","message":"x"}"""))

    assertFailure { runBlocking { api.config() } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::isFeedDisabled).isTrue()
  }

  @Test
  fun `429 carries Retry-After`() {
    server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))

    assertFailure { runBlocking { api.events(emptyList()) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::retryAfterSeconds).isEqualTo(30L)
  }

  @Test
  fun `like is PUT and unlike is DELETE on the reaction path`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    api.setReaction("c-1", FeedApi.REACTION_LIKE, on = true)
    api.setReaction("c-1", FeedApi.REACTION_LIKE, on = false)

    val put = server.takeRequest()
    assertThat(put.method).isEqualTo("PUT")
    assertThat(put.requestUrl!!.encodedPath).isEqualTo("/v1/feed/content/c-1/reactions/like")
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun `content id is a path segment, not a path`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.setReaction("../../admin", FeedApi.REACTION_SAVE, on = true)

    assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/v1/feed/content/..%2F..%2Fadmin/reactions/save")
  }

  @Test
  fun `cards posts ids and reads ok and unavailable alike`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","card":{"id":"a","kind":"post","text":"hi"}},{"id":"b","state":"unavailable"},{"id":"c","state":"something_new"}]}"""
      )
    )

    val response = api.cards(listOf("a", "b", "c"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.body.readUtf8()).isEqualTo("""{"ids":["a","b","c"]}""")
    assertThat(response.cards.map { it.isAvailable }).containsExactly(true, false, false)
  }

  @Test
  fun `not interested posts target and undo deletes it`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    api.notInterested("c-9", reason = null)
    api.undoNotInterested("c-9")

    val post = server.takeRequest()
    assertThat(post.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested")
    assertThat(post.body.readUtf8()).isEqualTo("""{"target_type":"content","target_id":"c-9"}""")
    val delete = server.takeRequest()
    assertThat(delete.method).isEqualTo("DELETE")
    assertThat(delete.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested/content/c-9")
  }

  @Test
  fun `too many card ids is refused before sending`() {
    assertFailure { runBlocking { api.cards((0..FeedApi.MAX_CARD_IDS).map { it.toString() }) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `unknown reaction is refused`() {
    assertFailure { runBlocking { api.setReaction("c", "dislike", on = true) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `error without a json body still reports the status`() {
    server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))

    assertFailure { runBlocking { api.config() } }
      .isInstanceOf(FeedApiException::class)
      .given { e ->
        assertThat(e.status).isEqualTo(503)
        assertThat(e.code).isNull()
        assertThat(e.isFeedDisabled).isFalse()
      }
  }

  @Test
  fun `create post returns the review state`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"p1","state":"pending","version":1}"""))

    val response = api.createPost(
      FeedCreatePostRequest(
        kind = "post",
        text = "hi",
        mediaKeys = emptyList(),
        topics = emptyList(),
        visibility = "friends",
        commentsClosed = false,
        replyModeration = false,
        clientNonce = "n"
      )
    )

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts")
    assertThat(response.state).isEqualTo(FeedCreatePostResponse.STATE_PENDING)
  }
}
