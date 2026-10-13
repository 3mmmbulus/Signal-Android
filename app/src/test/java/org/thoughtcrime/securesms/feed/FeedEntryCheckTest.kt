/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedApiException
import java.io.IOException

class FeedEntryCheckTest {

  private class MemoryState : FeedEntryCheck.State {
    override var visible = false
    override var checkedAt = 0L
  }

  private var now = 1_000_000L
  private var asks = 0
  private var answer: () -> Boolean = { true }
  private var shouldAsk = true
  private val state = MemoryState()

  private val source = object : FeedSource by EmptyFeedSource {
    override suspend fun isVisible(): Boolean {
      asks++
      return answer()
    }
  }

  private val check = FeedEntryCheck(shouldAsk = { shouldAsk }, source = { source }, state = state, clock = { now })

  @Test
  fun `first check records visible and reports the change`() = runBlocking {
    assertThat(check.refreshIfStale()).isTrue()
    assertThat(state.visible).isTrue()
    assertThat(state.checkedAt).isEqualTo(now)
  }

  @Test
  fun `asks again only after 15 minutes`() = runBlocking {
    check.refreshIfStale()
    now += FeedEntryCheck.STALE_AFTER_MILLIS - 1
    assertThat(check.refreshIfStale()).isFalse()
    assertThat(asks).isEqualTo(1)

    now += 1
    answer = { false }
    assertThat(check.refreshIfStale()).isTrue()
    assertThat(state.visible).isFalse()
    assertThat(asks).isEqualTo(2)
  }

  @Test
  fun `feed hidden from the engine counts as not visible`() = runBlocking {
    state.visible = true
    answer = { throw FeedApiException(404, "feed_hidden") }

    assertThat(check.refreshIfStale()).isTrue()
    assertThat(state.visible).isFalse()
  }

  @Test
  fun `network failure keeps the last answer and asks again next time`() = runBlocking {
    state.visible = true
    answer = { throw IOException("offline") }

    assertThat(check.refreshIfStale()).isFalse()
    assertThat(state.visible).isTrue()
    assertThat(state.checkedAt).isEqualTo(0L)
  }

  @Test
  fun `does not ask when the entry cannot show anyway`() = runBlocking {
    shouldAsk = false

    assertThat(check.refreshIfStale()).isFalse()
    assertThat(asks).isEqualTo(0)
  }

  @Test
  fun `clock going backwards asks again`() = runBlocking {
    check.refreshIfStale()
    now -= 60_000

    check.refreshIfStale()
    assertThat(asks).isEqualTo(2)
  }
}
