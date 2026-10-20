/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.thoughtcrime.securesms.feed.net.FeedCapabilities
import org.thoughtcrime.securesms.feed.net.FeedJson

/**
 * `GET /v1/capabilities` 上次成功的结果存在本机（产品经理 2026-10-20）：
 *
 * - 按「账号 + 引擎地址」分开存（[key]）；退出登录时 [clear]。
 * - 冷启动先用上次成功的结果画底栏，后台再刷新，变了再更新，底栏不闪。
 * - 超过 [MAX_AGE_MILLIS]（24 小时）或者从没成功过 → null（调用方按「拿不到就藏」）。
 * - 只存成功的结果；失败不覆盖、也不延长有效期。时钟往回拨过（记录在未来）当作过期。
 * - 最多留 [MAX_ENTRIES] 份（切区、换号来回用），旧的先丢。读不懂的存档当作没有。
 */
class FeedCapabilityCache(
  private val state: State,
  private val clock: () -> Long = System::currentTimeMillis
) {
  interface State {
    var json: String?
  }

  @Serializable
  data class Entry(val key: String, val features: List<String>, val at: Long)

  companion object {
    const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
    const val MAX_ENTRIES = 4

    private val SERIALIZER = ListSerializer(Entry.serializer())

    @JvmStatic
    fun key(account: String?, engine: String): String? = account?.takeIf { it.isNotBlank() }?.let { "$it $engine" }
  }

  @Synchronized
  fun get(key: String?): FeedCapabilities? {
    if (key == null) return null
    val entry = read().firstOrNull { it.key == key } ?: return null
    val age = clock() - entry.at
    if (age < 0 || age >= MAX_AGE_MILLIS) return null
    return FeedCapabilities(entry.features.toSet())
  }

  @Synchronized
  fun put(key: String?, capabilities: FeedCapabilities) {
    if (key == null) return
    val rest = read().filter { it.key != key }
    val list = (listOf(Entry(key, capabilities.features.sorted(), clock())) + rest).take(MAX_ENTRIES)
    state.json = FeedJson.encodeToString(SERIALIZER, list)
  }

  @Synchronized
  fun clear() {
    state.json = null
  }

  private fun read(): List<Entry> {
    val text = state.json ?: return emptyList()
    return try {
      FeedJson.decodeFromString(SERIALIZER, text)
    } catch (e: IllegalArgumentException) {
      emptyList()
    }
  }
}
