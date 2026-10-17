/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.friends

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedServerErrors

/** 好友同步：限流（429 / 带 Retry-After 的 503）和「接口不可用」（没有标记的 404）。 */
class FeedFriendSyncThrottleTest {

  private class MemoryState : FeedFriendSync.State {
    override var checkedAt = 0L
    val byEngine = mutableMapOf<String, Set<String>>()
    override fun reported(engineKey: String): Set<String> = byEngine[engineKey].orEmpty()
    override fun save(engineKey: String, acis: Set<String>) {
      byEngine[engineKey] = acis
    }
  }

  private val minute = 60_000L
  private var now = 10_000_000L
  private var friends = setOf("a", "b", "c")
  private val state = MemoryState()
  private val asked = mutableListOf<String>()
  private val delivered = mutableListOf<String>()
  private var signFailure: ((String) -> Exception?) = { null }
  private var noAccount = setOf<String>()
  private var engineFailure: Exception? = null

  private val sync = FeedFriendSync(
    enabled = { true },
    engineKey = { "cn https://feed.example" },
    friends = { friends },
    signer = { aci, add ->
      asked += aci
      signFailure(aci)?.let { throw it }
      if (add && aci in noAccount) null else "jwt:$aci"
    },
    engine = {
      engineFailure?.let { e -> throw e }
      delivered += it
    },
    state = state,
    clock = { now }
  )

  private fun reported() = state.reported("cn https://feed.example")

  @Test
  fun `a 429 stops the run, keeps what was done, and waits the usual interval`() = runBlocking {
    signFailure = { if (it == "b") FeedApiException(429, "assertion_unavailable", 20) else null }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.THROTTLED)
    assertThat(asked).containsExactly("a", "b") // c is not asked after the 429
    assertThat(reported()).isEqualTo(setOf("a")) // b is NOT marked
    assertThat(delivered).containsExactly("jwt:a")

    now += 14 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)
    now += 1 * minute
    signFailure = { null }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
    assertThat(reported()).isEqualTo(setOf("a", "b", "c"))
  }

  @Test
  fun `a retry-after longer than the interval is waited in full`() = runBlocking {
    signFailure = { FeedApiException(429, "assertion_unavailable", 3600) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.THROTTLED)
    now += 16 * minute // the normal interval is over, the server's wait is not
    signFailure = { null }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)
    now += 43 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)
    now += 1 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
  }

  @Test
  fun `a retry-after above an hour is waited as an hour`() = runBlocking {
    signFailure = { FeedApiException(503, "assertion_unavailable", 86_400) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.THROTTLED)
    signFailure = { null }
    now += 59 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)
    now += 1 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
  }

  @Test
  fun `a throttle from the engine side counts the same`() = runBlocking {
    engineFailure = FeedApiException(429, FeedApiException.CODE_THROTTLED_LOCALLY, 120)
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.THROTTLED)
    assertThat(reported()).isEmpty()
  }

  @Test
  fun `a 503 without retry-after and other failures are an ordinary failure`() = runBlocking {
    signFailure = { FeedApiException(503, "assertion_unavailable") }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.FAILED)
    now += 20 * minute
    signFailure = { FeedApiException(500, "assertion_unavailable", null) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.FAILED)
    assertThat(reported()).isEmpty()
  }

  @Test
  fun `a 404 without the marker is unavailable, nobody is marked reported and nobody else is asked`() = runBlocking {
    signFailure = { FeedApiException(404, FeedServerErrors.CODE_FRIEND_ROUTE_UNAVAILABLE) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.UNAVAILABLE)
    assertThat(asked).containsExactly("a")
    assertThat(reported()).isEmpty()
    assertThat(delivered).isEmpty()

    // 下一个间隔还是会问（路由可能已经部署）：这次路由在了，第一个人没有账号（带标记）→ 记为已处理，其他人照常
    now += 15 * minute
    asked.clear()
    signFailure = { null }
    noAccount = setOf("a")
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
    assertThat(asked).containsExactly("a", "b", "c")
    assertThat(reported()).isEqualTo(setOf("a", "b", "c"))
    assertThat(delivered).containsExactly("jwt:b", "jwt:c") // a had no account: nothing was sent for it
  }

  @Test
  fun `removals are unavailable too when the route is missing`() = runBlocking {
    state.save("cn https://feed.example", setOf("x", "y"))
    friends = setOf("x", "y")
    friends = setOf("y")
    signFailure = { FeedApiException(404, FeedServerErrors.CODE_FRIEND_ROUTE_UNAVAILABLE) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.UNAVAILABLE)
    assertThat(reported()).isEqualTo(setOf("x", "y")) // x is still reported: the removal did not happen
  }

  @Test
  fun `a clock that went backwards does not keep a long throttle block`() = runBlocking {
    signFailure = { FeedApiException(429, "assertion_unavailable", 3600) }
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.THROTTLED)
    signFailure = { null }
    now -= 120 * minute
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
  }
}
