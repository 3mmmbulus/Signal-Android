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
 * 内容引擎接口（`tellomi/content-engine` `internal/engine/api`）的请求形状与错误处理。全部在本机 MockWebServer 上。
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
  fun `following sends cursor and bearer token`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[{"id":"a","reason":"following"}],"cursor":"c2"}"""))

    val page = api.feed("following", cursor = "abc")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/following")
    assertThat(request.requestUrl!!.queryParameter("cursor")).isEqualTo("abc")
    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer t1")
    assertThat(page.items.map { it.id }).containsExactly("a")
    assertThat(page.cursor).isEqualTo("c2")
  }

  @Test
  fun `recommend has no cursor and can turn personalization off`() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))

    api.feed("recommend", cursor = null)
    api.feed("recommend", cursor = null, personalized = false)

    val first = server.takeRequest().requestUrl!!
    assertThat(first.encodedPath).isEqualTo("/v1/feed/recommend")
    assertThat(first.queryParameter("cursor")).isNull()
    assertThat(first.queryParameter("personalized")).isNull()
    assertThat(server.takeRequest().requestUrl!!.queryParameter("personalized")).isEqualTo("false")
  }

  @Test
  fun `unknown tab is refused`() {
    assertFailure { runBlocking { api.feed("hot", cursor = null) } }.isInstanceOf(IllegalArgumentException::class)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `401 refreshes the token and retries exactly once`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setBody("""{"items":[]}"""))

    api.feed("recommend", cursor = null)

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(tokens.refreshes).isEqualTo(1)
    server.takeRequest()
    assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer t2")
  }

  @Test
  fun `second 401 is an error, not a third request`() {
    server.enqueue(MockResponse().setResponseCode(401))
    server.enqueue(MockResponse().setResponseCode(401))

    assertFailure { runBlocking { api.feed("recommend", cursor = null) } }
      .isInstanceOf(FeedApiException::class)
      .prop(FeedApiException::status).isEqualTo(401)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `404 feed_hidden is recognised`() {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"feed_hidden"}"""))

    assertFailure { runBlocking { api.status() } }
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
    assertThat(put.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts/c-1/like")
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun `content id is a path segment, not a path`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.setReaction("../../admin", FeedApi.REACTION_SAVE, on = true)

    assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts/..%2F..%2Fadmin/save")
  }

  @Test
  fun `cards posts ids and reads ok and unavailable alike`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"cards":[{"id":"a","state":"ok","version":2,"card":{"kind":"post","text":"hi"},"stats":{"likes":3,"comments":1,"reposts":0},"me":{"liked":true,"saved":false}},{"id":"b","state":"unavailable"},{"id":"c","state":"something_new"}]}"""
      )
    )

    val response = api.cards(listOf("a", "b", "c"))

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.body.readUtf8()).isEqualTo("""{"ids":["a","b","c"]}""")
    assertThat(response.cards.map { it.isAvailable }).containsExactly(true, false, false)
    val card = response.cards.first().toCard()!!
    assertThat(card.id).isEqualTo("a")
    assertThat(card.version).isEqualTo(2)
    assertThat(card.stats.likes).isEqualTo(3)
    assertThat(card.viewer.liked).isTrue()
    assertThat(response.cards[1].toCard()).isNull()
  }

  @Test
  fun `not interested posts target and undo deletes it`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(MockResponse().setResponseCode(204))

    api.notInterested("c-9", reason = null)
    api.undoNotInterested("c-9")

    val post = server.takeRequest()
    assertThat(post.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested")
    assertThat(post.body.readUtf8()).isEqualTo("""{"content_id":"c-9"}""")
    val delete = server.takeRequest()
    assertThat(delete.method).isEqualTo("DELETE")
    assertThat(delete.requestUrl!!.encodedPath).isEqualTo("/v1/feed/not-interested/c-9")
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

    assertFailure { runBlocking { api.status() } }
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
      FeedCreatePostRequest(kind = "post", text = "hi", visibility = "friends", commentsClosed = false)
    )

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/feed/posts")
    // 引擎 DisallowUnknownFields：只能有它认的字段
    assertThat(request.body.readUtf8()).isEqualTo("""{"kind":"post","text":"hi","visibility":"friends","comments_closed":false}""")
    assertThat(response.state).isEqualTo(FeedCreatePostResponse.STATE_PENDING)
  }

  @Test
  fun `my posts pages with before and reads review state`() = runBlocking {
    server.enqueue(
      MockResponse().setBody(
        """{"posts":[{"id":"p1","kind":"post","state":"rejected","version":1,"text":"x","visibility":"public",""" +
          """"created_at":"2026-10-13T08:00:00Z","rejection_reasons":["lexicon:ad"]}],"next_before":"p1"}"""
      )
    )
    server.enqueue(MockResponse().setBody("""{"posts":[]}"""))

    val first = api.myPosts(before = null)
    val second = api.myPosts(before = "p1")

    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/posts")
    assertThat(server.takeRequest().path).isEqualTo("/v1/feed/me/posts?before=p1")
    assertThat(first.posts.single().state).isEqualTo(FeedMyPost.STATE_REJECTED)
    assertThat(first.posts.single().rejectionReasons).isEqualTo(listOf("lexicon:ad"))
    assertThat(first.nextBefore).isEqualTo("p1")
    assertThat(second.nextBefore).isEqualTo(null)
  }

  @Test
  fun `delete post is a DELETE on the post`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))

    api.deletePost("p 1")

    val request = server.takeRequest()
    assertThat(request.method).isEqualTo("DELETE")
    assertThat(request.path).isEqualTo("/v1/feed/posts/p%201")
  }
}
