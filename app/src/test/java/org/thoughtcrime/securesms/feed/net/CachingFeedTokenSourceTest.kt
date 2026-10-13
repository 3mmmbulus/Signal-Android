/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant

class CachingFeedTokenSourceTest {

  private var now = Instant.parse("2026-10-13T12:00:00Z")
  private var fetches = 0
  private var expiresAt = "2026-10-13T13:00:00Z"

  private val source = CachingFeedTokenSource(clock = { now }) {
    fetches++
    delay(10)
    FeedAuthResponse(token = "t$fetches", expiresAt = expiresAt, region = "cn")
  }

  @Test
  fun `token is cached until five minutes before it expires`() = runBlocking {
    assertThat(source.token()).isEqualTo("t1")

    now = Instant.parse("2026-10-13T12:54:59Z")
    assertThat(source.token()).isEqualTo("t1")

    now = Instant.parse("2026-10-13T12:55:00Z")
    assertThat(source.token()).isEqualTo("t2")
  }

  @Test
  fun `force refresh always fetches a new one`() = runBlocking {
    assertThat(source.token()).isEqualTo("t1")
    assertThat(source.token(forceRefresh = true)).isEqualTo("t2")
    assertThat(source.token()).isEqualTo("t2")
  }

  @Test
  fun `concurrent callers share one fetch`() = runBlocking {
    val tokens = List(5) { async { source.token() } }.awaitAll()

    assertThat(tokens.toSet()).isEqualTo(setOf("t1"))
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `unreadable expiry is used once and not cached`() = runBlocking {
    expiresAt = "soon"

    assertThat(source.token()).isEqualTo("t1")
    assertThat(source.token()).isEqualTo("t2")
  }

  @Test
  fun `clear drops the cached token`() = runBlocking {
    source.token()
    source.clear()

    assertThat(source.token()).isEqualTo("t2")
  }

  @Test
  fun `auth response parses the server shape`() {
    val response = FeedJson.decodeFromString(
      FeedAuthResponse.serializer(),
      """{"token":"abc","expires_at":"2026-10-13T12:00:00Z","region":"cn","extra":1}"""
    )

    assertThat(response).isEqualTo(FeedAuthResponse(token = "abc", expiresAt = "2026-10-13T12:00:00Z", region = "cn"))
  }
}
