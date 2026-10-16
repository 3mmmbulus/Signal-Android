/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FeedSourceSlotTest {

  private class Source(val name: String)

  private val cleared = mutableListOf<String>()

  private fun create(name: String): () -> Pair<Source, suspend () -> Unit> = {
    Source(name) to { cleared += name }
  }

  @Test
  fun `the same key is created once`() {
    val slot = FeedSourceSlot<Source>()
    val created = AtomicInteger()

    val a = slot.getOrCreate("cn https://feed.tellomi.cn") { created.incrementAndGet(); create("a")() }
    val b = slot.getOrCreate("cn https://feed.tellomi.cn") { created.incrementAndGet(); create("b")() }

    assertThat(a).isEqualTo(b)
    assertThat(created.get()).isEqualTo(1)
  }

  @Test
  fun `another key (region switch) creates a new one`() {
    val slot = FeedSourceSlot<Source>()

    val global = slot.getOrCreate("global https://feed.tellomi.app", create("global"))
    val cn = slot.getOrCreate("cn https://feed.tellomi.cn", create("cn"))

    assertThat(global).isNotEqualTo(cn)
    assertThat(slot.getOrCreate("cn https://feed.tellomi.cn", create("again")).name).isEqualTo("cn")
  }

  @Test
  fun `reset clears the token cache of the current source and the next call builds a fresh one`() = runBlocking {
    val slot = FeedSourceSlot<Source>()
    val first = slot.getOrCreate("k", create("first"))

    slot.reset()

    assertThat(cleared).containsExactly("first")
    val second = slot.getOrCreate("k", create("second"))
    assertThat(second).isNotEqualTo(first)
    assertThat(second.name).isEqualTo("second")
  }

  @Test
  fun `reset with nothing cached does nothing, twice clears once`() = runBlocking {
    val slot = FeedSourceSlot<Source>()

    slot.reset()
    assertThat(cleared).isEmpty()

    slot.getOrCreate("k", create("x"))
    slot.reset()
    slot.reset()
    assertThat(cleared).containsExactly("x")
  }

  @Test
  fun `reset waits for the token clear to finish`() = runBlocking {
    val slot = FeedSourceSlot<Source>()
    var finished = false
    slot.getOrCreate("k") { Source("slow") to { delay(100); finished = true } }

    slot.reset()

    assertThat(finished).isEqualTo(true)
  }

  @Test
  fun `concurrent callers get one source`() {
    val slot = FeedSourceSlot<Source>()
    val created = AtomicInteger()
    val pool = Executors.newFixedThreadPool(8)
    try {
      val futures = (1..8).map {
        pool.submit<Source> { slot.getOrCreate("k") { created.incrementAndGet(); Thread.sleep(20); create("one")() } }
      }
      val results = futures.map { it.get() }

      assertThat(created.get()).isEqualTo(1)
      assertThat(results.toSet().size).isEqualTo(1)
    } finally {
      pool.shutdownNow()
    }
  }
}
