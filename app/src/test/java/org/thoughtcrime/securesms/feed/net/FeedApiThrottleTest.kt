/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 内容引擎调用的 429 / `Retry-After` 规则（MockWebServer，时钟和随机数注入）。 */
class FeedApiThrottleTest {

  private lateinit var server: MockWebServer
  private lateinit var api: FeedApi
  private var clockMillis = Instant0.millis
  private var random = 0.0

  private object Instant0 {
    val millis: Long = java.time.Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
  }

  private class Tokens : FeedTokenSource {
    var refreshes = 0
    override suspend fun token(forceRefresh: Boolean): String {
      if (forceRefresh) refreshes++
      return "t"
    }
  }

  private val tokens = Tokens()
  private val configBody = """{"enabled":true}"""

  private suspend fun failure(block: suspend () -> Any?): FeedApiException {
    try {
      block()
    } catch (e: FeedApiException) {
      return e
    }
    throw AssertionError("expected a FeedApiException")
  }

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    api = FeedApi(server.url("/"), OkHttpClient(), tokens, clock = { clockMillis }, cooldown = FeedCooldown({ clockMillis }, { random }))
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun throttled(retryAfter: String? = null, status: Int = 429, body: String = """{"error":"rate_limited"}""") {
    val response = MockResponse().setResponseCode(status).setBody(body)
    if (retryAfter != null) response.setHeader("Retry-After", retryAfter)
    server.enqueue(response)
  }

  @Test
  fun `a 429 with retry-after stops all calls locally until the time is up`() = runBlocking {
    throttled("30")
    assertThat(failure { api.config() }.retryAfterSeconds).isEqualTo(30L)
    assertThat(server.requestCount).isEqualTo(1)

    clockMillis += 10_000
    failure { api.config() }.also {
      assertThat(it.status).isEqualTo(429)
      assertThat(it.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
      assertThat(it.retryAfterSeconds).isEqualTo(20L)
    }
    failure { api.status() } // 任何接口都一样
    assertThat(server.requestCount).isEqualTo(1) // 没有再打网络

    clockMillis += 20_000
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `retry-after as an http date is read against the clock and clamped to an hour`() = runBlocking {
    throttled("Fri, 16 Oct 2026 00:01:30 GMT")
    assertThat(failure { api.config() }.retryAfterSeconds).isEqualTo(90L)
    clockMillis += 91_000
    throttled("Sat, 17 Oct 2026 00:00:00 GMT")
    assertThat(failure { api.config() }.retryAfterSeconds).isEqualTo(3600L)
    clockMillis += 3_599_000
    assertThat(failure { api.config() }.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
  }

  @Test
  fun `retry-after zero waits one second`() = runBlocking {
    throttled("0")
    assertThat(failure { api.config() }.retryAfterSeconds).isEqualTo(1L)
    assertThat(failure { api.config() }.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
    clockMillis += 1_000
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
  }

  @Test
  fun `a 429 without retry-after backs off exponentially with jitter`() = runBlocking {
    random = 0.0
    throttled()
    assertThat(failure { api.config() }.retryAfterSeconds).isNull() // the server said nothing
    clockMillis += 14_999
    assertThat(failure { api.config() }.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
    clockMillis += 1
    throttled() // second one in a row: 30 s
    assertThat(failure { api.config() }.status).isEqualTo(429)
    assertThat(server.requestCount).isEqualTo(2)
    clockMillis += 29_999
    assertThat(failure { api.config() }.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
    clockMillis += 1
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun `a 503 with retry-after is honoured, a 503 without is an ordinary failure`() = runBlocking {
    throttled("45", status = 503, body = "<html>busy</html>")
    assertThat(failure { api.config() }.status).isEqualTo(503)
    assertThat(failure { api.config() }.code).isEqualTo(FeedApiException.CODE_THROTTLED_LOCALLY)
    clockMillis += 45_000

    throttled(status = 503, body = "<html>busy</html>")
    assertThat(failure { api.config() }.status).isEqualTo(503)
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue() // 立刻能再问：没有冷却
  }

  @Test
  fun `errors that have nothing to do with the rate do not cool down`() = runBlocking {
    for (status in listOf(400, 403, 404, 409, 500, 502, 504)) {
      server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "60").setBody("""{"error":"x"}"""))
      assertThat(failure { api.config() }.status).isEqualTo(status)
    }
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
    assertThat(server.requestCount).isEqualTo(8)
  }

  @Test
  fun `too_many_uploads is a concurrency limit, not a rate limit, and does not stop the feed`() = runBlocking {
    throttled("60", body = """{"error":"too_many_uploads"}""")
    failure { api.config() }.also {
      assertThat(it.code).isEqualTo("too_many_uploads")
      assertThat(it.retryAfterSeconds).isEqualTo(60L)
      assertThat(it.isThrottled).isEqualTo(false)
    }
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
  }

  @Test
  fun `a successful call after the cooldown clears the streak`() = runBlocking {
    random = 0.0
    throttled()
    failure { api.config() }
    clockMillis += 15_000
    server.enqueue(MockResponse().setBody(configBody))
    api.config()
    throttled() // streak 1 again, not 2: 15 s
    failure { api.config() }
    clockMillis += 15_000
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
  }

  @Test
  fun `401 still refreshes the token once and is not a throttle`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"credential_expired"}"""))
    server.enqueue(MockResponse().setBody(configBody))
    assertThat(api.config().enabled).isTrue()
    assertThat(tokens.refreshes).isEqualTo(1)
  }

  @Test
  fun `isThrottled is only 429 and a 503 with retry-after`() {
    assertThat(FeedApiException(429, null).isThrottled).isTrue()
    assertThat(FeedApiException(429, "too_many_uploads", 60).isThrottled).isFalse()
    assertThat(FeedApiException(503, null, 30).isThrottled).isTrue()
    assertThat(FeedApiException(503, null).isThrottled).isFalse()
    assertThat(FeedApiException(500, null, 30).isThrottled).isFalse()
    assertThat(FeedApiException(404, "feed_hidden").isThrottled).isFalse()
  }
}
