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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class FeedAuthBackoffTest {

  private class FakeInner(var behaviour: suspend () -> String) : FeedTokenSource {
    val calls = AtomicInteger()
    val forced = AtomicInteger()
    override suspend fun token(forceRefresh: Boolean): String {
      calls.incrementAndGet()
      if (forceRefresh) forced.incrementAndGet()
      return behaviour()
    }
  }

  private fun unavailable(status: Int) = FeedApiException(status, FeedAuthFailure.CODE_UNAVAILABLE)

  private suspend fun failure(block: suspend () -> Unit): FeedApiException? {
    return try {
      block()
      null
    } catch (e: FeedApiException) {
      e
    }
  }

  @Test
  fun `classification`() {
    for (status in listOf(404, 405, 501)) {
      val classified = FeedAuthFailure.classify(unavailable(status))
      assertThat(classified?.code).isEqualTo("feed_auth_not_deployed")
      assertThat(classified?.status).isEqualTo(status)
      assertThat(classified!!.isAuthNotDeployed).isTrue()
    }
    for (status in listOf(401, 403, 429, 500, 502, 503)) {
      assertThat(FeedAuthFailure.classify(unavailable(status))).isNull()
    }
    // the engine's own feed_hidden is a different thing
    assertThat(FeedAuthFailure.classify(FeedApiException(404, "feed_hidden"))).isNull()
    assertThat(FeedApiException(404, "feed_hidden").isAuthNotDeployed).isFalse()
  }

  @Test
  fun `a token passes through and nothing is backed off`() = runBlocking {
    val inner = FakeInner { "tok" }
    val source = NotDeployedBackoffTokenSource(inner)

    assertThat(source.token()).isEqualTo("tok")
    assertThat(source.token(forceRefresh = true)).isEqualTo("tok")
    assertThat(inner.calls.get()).isEqualTo(2)
    assertThat(inner.forced.get()).isEqualTo(1)
  }

  @Test
  fun `not deployed backs off for ten minutes including force refresh`() = runBlocking {
    var now = 1_000_000L
    val inner = FakeInner { throw unavailable(404) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { now })

    val first = failure { source.token() }
    assertThat(first?.code).isEqualTo("feed_auth_not_deployed")
    assertThat(inner.calls.get()).isEqualTo(1)

    now += Duration.ofMinutes(10).toMillis() - 1
    assertThat(failure { source.token() }?.isAuthNotDeployed).isEqualTo(true)
    assertThat(failure { source.token(forceRefresh = true) }?.isAuthNotDeployed).isEqualTo(true)
    assertThat(inner.calls.get()).isEqualTo(1)

    now += 1
    failure { source.token() }
    assertThat(inner.calls.get()).isEqualTo(2)
  }

  @Test
  fun `other failures are not backed off`() = runBlocking {
    val inner = FakeInner { throw unavailable(503) }
    val source = NotDeployedBackoffTokenSource(inner)

    repeat(3) { assertThat(failure { source.token() }?.code).isEqualTo("auth_unavailable") }
    assertThat(inner.calls.get()).isEqualTo(3)

    inner.behaviour = { throw FeedApiException(401, "credential_expired") }
    assertThat(failure { source.token() }?.status).isEqualTo(401)
    inner.behaviour = { throw unavailable(429) }
    assertThat(failure { source.token() }?.status).isEqualTo(429)
    assertThat(inner.calls.get()).isEqualTo(5)
  }

  @Test
  fun `network errors pass through unchanged and do not back off`() = runBlocking {
    val inner = FakeInner { throw IOException("offline") }
    val source = NotDeployedBackoffTokenSource(inner)

    repeat(2) {
      val e = try {
        source.token()
        null
      } catch (e: IOException) {
        e
      }
      assertThat(e?.message).isEqualTo("offline")
    }
    assertThat(inner.calls.get()).isEqualTo(2)
  }

  @Test
  fun `a success ends the backoff and a later not deployed starts it again`() = runBlocking {
    var now = 0L
    val inner = FakeInner { throw unavailable(405) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { now })

    failure { source.token() }
    now += Duration.ofMinutes(10).toMillis()
    inner.behaviour = { "ok" }
    assertThat(source.token()).isEqualTo("ok")

    inner.behaviour = { throw unavailable(501) }
    assertThat(failure { source.token(forceRefresh = true) }?.isAuthNotDeployed).isEqualTo(true)
    val calls = inner.calls.get()
    now += 1
    failure { source.token() }
    assertThat(inner.calls.get()).isEqualTo(calls)
  }

  @Test
  fun `after a success an old not deployed answer is forgotten even if the clock goes backwards`() = runBlocking {
    var now = 0L
    val inner = FakeInner { throw unavailable(404) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { now })

    failure { source.token() }
    now = Duration.ofMinutes(10).toMillis()
    inner.behaviour = { "ok" }
    assertThat(source.token()).isEqualTo("ok")

    now = Duration.ofMinutes(5).toMillis()
    assertThat(source.token()).isEqualTo("ok")
    assertThat(inner.calls.get()).isEqualTo(3)
  }

  @Test
  fun `reset backoff lets the next call through`() = runBlocking {
    val inner = FakeInner { throw unavailable(404) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { 0L })

    failure { source.token() }
    failure { source.token() }
    assertThat(inner.calls.get()).isEqualTo(1)

    source.resetBackoff()
    failure { source.token() }
    assertThat(inner.calls.get()).isEqualTo(2)
  }

  @Test
  fun `a clock that went backwards does not lock the source out`() = runBlocking {
    var now = 10_000_000L
    val inner = FakeInner { throw unavailable(404) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { now })

    failure { source.token() }
    now -= 60_000
    failure { source.token() }

    assertThat(inner.calls.get()).isEqualTo(2)
  }

  @Test
  fun `concurrent callers see the backoff instead of each hitting the network`() = runBlocking {
    val inner = FakeInner { delay(30); throw unavailable(404) }
    val source = NotDeployedBackoffTokenSource(inner, clock = { 0L })

    val results = (1..6).map { async { failure { source.token() } } }.awaitAll()

    assertThat(inner.calls.get()).isEqualTo(1)
    assertThat(results.all { it?.isAuthNotDeployed == true }).isTrue()
  }
}
