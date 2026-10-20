/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import org.thoughtcrime.securesms.feed.net.FeedCapabilities

class FeedCapabilityCacheTest {

  private var now = 1_000_000L
  private val state = object : FeedCapabilityCache.State {
    override var json: String? = null
  }
  private val cache = FeedCapabilityCache(state) { now }
  private val caps = FeedCapabilities(setOf(FeedCapabilities.FEED, FeedCapabilities.REPORTS, FeedCapabilities.POSTING))
  private val key = FeedCapabilityCache.key("aci-1", "https://feed.example")

  @Test
  fun `never succeeded is nothing`() {
    assertThat(cache.get(key)).isNull()
  }

  @Test
  fun `last success is used within 24 hours`() {
    cache.put(key, caps)
    now += FeedCapabilityCache.MAX_AGE_MILLIS - 1
    assertThat(cache.get(key)).isEqualTo(caps)
  }

  @Test
  fun `older than 24 hours is nothing`() {
    cache.put(key, caps)
    now += FeedCapabilityCache.MAX_AGE_MILLIS
    assertThat(cache.get(key)).isNull()
  }

  @Test
  fun `clock moved back is nothing`() {
    cache.put(key, caps)
    now -= 1
    assertThat(cache.get(key)).isNull()
  }

  @Test
  fun `separate per account and engine`() {
    cache.put(key, caps)
    assertThat(cache.get(FeedCapabilityCache.key("aci-2", "https://feed.example"))).isNull()
    assertThat(cache.get(FeedCapabilityCache.key("aci-1", "https://other.example"))).isNull()
    val other = FeedCapabilities(setOf(FeedCapabilities.FEED))
    cache.put(FeedCapabilityCache.key("aci-2", "https://feed.example"), other)
    assertThat(cache.get(key)).isEqualTo(caps)
    assertThat(cache.get(FeedCapabilityCache.key("aci-2", "https://feed.example"))).isEqualTo(other)
  }

  @Test
  fun `no account means no key`() {
    assertThat(FeedCapabilityCache.key(null, "https://feed.example")).isNull()
    cache.put(null, caps)
    assertThat(state.json).isNull()
  }

  @Test
  fun `logout clears everything`() {
    cache.put(key, caps)
    cache.clear()
    assertThat(cache.get(key)).isNull()
  }

  @Test
  fun `unreadable store is nothing`() {
    state.json = "not json"
    assertThat(cache.get(key)).isNull()
    cache.put(key, caps)
    assertThat(cache.get(key)).isEqualTo(caps)
  }

  @Test
  fun `keeps only the newest few`() {
    cache.put(key, caps)
    repeat(FeedCapabilityCache.MAX_ENTRIES) { cache.put(FeedCapabilityCache.key("aci-x$it", "e"), caps) }
    assertThat(cache.get(key)).isNull()
  }

  @Test
  fun `cold start uses the cached result, failed refresh keeps it, nothing hides`() {
    // 冷启动：这个进程还没成功过，用本机存的 → 底栏不闪
    assertThat(FeedCapabilityStore.resolve(sampleData = false, inMemory = null, cached = caps).posting).isEqualTo(true)
    // 刷新成功：用新的
    val newer = FeedCapabilities(setOf(FeedCapabilities.FEED, FeedCapabilities.REPORTS))
    assertThat(FeedCapabilityStore.resolve(sampleData = false, inMemory = newer, cached = caps).posting).isEqualTo(false)
    // 从没成功过 / 存的过期了：全藏
    assertThat(FeedCapabilityStore.resolve(sampleData = false, inMemory = null, cached = null)).isEqualTo(FeedEntries.NONE)
    // 样例数据（只限内部包）：全开
    assertThat(FeedCapabilityStore.resolve(sampleData = true, inMemory = null, cached = null)).isEqualTo(FeedEntries.ALL)
  }
}
