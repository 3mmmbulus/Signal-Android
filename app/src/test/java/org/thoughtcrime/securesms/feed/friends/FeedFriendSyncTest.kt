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
import java.io.IOException

class FeedFriendSyncTest {

  private class MemoryState : FeedFriendSync.State {
    override var checkedAt = 0L
    val byEngine = mutableMapOf<String, Set<String>>()
    override fun reported(engineKey: String): Set<String> = byEngine[engineKey].orEmpty()
    override fun save(engineKey: String, acis: Set<String>) {
      byEngine[engineKey] = acis
    }
  }

  private var now = 10_000_000L
  private var enabled = true
  private var engineKey = "cn https://feed.example"
  private var friends = setOf<String>()
  private val state = MemoryState()
  private val signed = mutableListOf<String>()
  private val delivered = mutableListOf<String>()
  private var gone = setOf<String>()
  private var failOn: String? = null

  private val sync = FeedFriendSync(
    enabled = { enabled },
    engineKey = { engineKey },
    friends = { friends },
    signer = { aci, add ->
      if (aci == failOn) throw IOException("offline")
      signed += "${if (add) "+" else "-"}$aci"
      if (add && aci in gone) null else "jwt:${if (add) "add" else "remove"}:$aci"
    },
    engine = { delivered += it },
    state = state,
    clock = { now }
  )

  @Test
  fun `reports new friends then only the difference`() = runBlocking {
    friends = setOf("b", "a")
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
    assertThat(delivered).containsExactly("jwt:add:a", "jwt:add:b")
    assertThat(state.reported(engineKey)).isEqualTo(setOf("a", "b"))

    // 15 分钟内不再对
    friends = setOf("a", "c")
    now += 60_000
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)

    now += FeedFriendSync.INTERVAL_MILLIS
    delivered.clear()
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
    // 撤回在前
    assertThat(delivered).containsExactly("jwt:remove:b", "jwt:add:c")
    assertThat(state.reported(engineKey)).isEqualTo(setOf("a", "c"))
  }

  @Test
  fun `unchanged list makes no calls`() = runBlocking {
    friends = setOf("a")
    sync.syncIfStale()
    signed.clear()
    now += FeedFriendSync.INTERVAL_MILLIS
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.UNCHANGED)
    assertThat(signed).isEmpty()
  }

  @Test
  fun `disabled does nothing`() = runBlocking {
    enabled = false
    friends = setOf("a")
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.SKIPPED)
    assertThat(signed).isEmpty()
    assertThat(state.checkedAt).isEqualTo(0L)
  }

  @Test
  fun `peer without an account is recorded without reaching the engine`() = runBlocking {
    friends = setOf("a", "gone")
    gone = setOf("gone")
    sync.syncIfStale()
    assertThat(delivered).containsExactly("jwt:add:a")
    assertThat(state.reported(engineKey)).isEqualTo(setOf("a", "gone"))
  }

  @Test
  fun `failure keeps finished steps and stops`() = runBlocking {
    friends = setOf("a", "b", "c")
    failOn = "b"
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.FAILED)
    assertThat(state.reported(engineKey)).isEqualTo(setOf("a"))

    failOn = null
    now += FeedFriendSync.INTERVAL_MILLIS
    delivered.clear()
    sync.syncIfStale()
    assertThat(delivered).containsExactly("jwt:add:b", "jwt:add:c")
  }

  @Test
  fun `large lists are split across runs`() = runBlocking {
    friends = (1..(FeedFriendSync.MAX_CHANGES_PER_RUN + 5)).map { "p%03d".format(it) }.toSet()
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.MORE_LEFT)
    assertThat(delivered.size).isEqualTo(FeedFriendSync.MAX_CHANGES_PER_RUN)
    // 剩下的下次回到前台就做，不等间隔
    now += 1_000
    assertThat(sync.syncIfStale()).isEqualTo(FeedFriendSync.Outcome.DONE)
    assertThat(delivered.size).isEqualTo(FeedFriendSync.MAX_CHANGES_PER_RUN + 5)
  }

  @Test
  fun `switching engine reports everyone again`() = runBlocking {
    friends = setOf("a")
    sync.syncIfStale()
    engineKey = "global https://feed.example.org"
    now += FeedFriendSync.INTERVAL_MILLIS
    delivered.clear()
    sync.syncIfStale()
    assertThat(delivered).containsExactly("jwt:add:a")
  }
}
