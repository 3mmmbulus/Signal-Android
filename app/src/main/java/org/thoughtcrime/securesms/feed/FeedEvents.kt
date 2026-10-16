/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import java.time.Instant

/**
 * 曝光判定（`docs/adr/0071/client-api-2026-10.md` 6.1）：卡片露出 ≥ 50% 且连续停留 ≥ 1 秒算一次，
 * 同一张卡片同一次会话只算一次。纯逻辑，界面每次布局变化时把「现在露出过半的卡片」交进来。
 */
class FeedImpressionTracker(
  private val minVisibleMillis: Long = MIN_VISIBLE_MILLIS
) {
  companion object {
    const val MIN_VISIBLE_MILLIS = 1_000L
    const val MIN_VISIBLE_FRACTION = 0.5f
  }

  private val visibleSince = mutableMapOf<String, Long>()
  private val counted = mutableSetOf<String>()

  /** [visibleIds] 是现在露出过半的卡片；返回这次刚满 1 秒、该记一次曝光的。 */
  fun update(visibleIds: Set<String>, nowMillis: Long): List<String> {
    visibleSince.keys.retainAll(visibleIds)
    val qualified = mutableListOf<String>()
    for (id in visibleIds) {
      if (id in counted) continue
      val since = visibleSince.getOrPut(id) { nowMillis }
      if (nowMillis - since >= minVisibleMillis) {
        counted += id
        visibleSince -= id
        qualified += id
      }
    }
    return qualified
  }

  /** 最早什么时候需要再看一次（有卡片在等满 1 秒）；没有就是 null。 */
  fun nextCheckAt(): Long? = visibleSince.values.minOrNull()?.plus(minVisibleMillis)
}

/** 一个列表项在视口里露出的比例。 */
fun visibleFraction(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
  if (itemSize <= 0) return 0f
  val top = maxOf(itemOffset, viewportStart)
  val bottom = minOf(itemOffset + itemSize, viewportEnd)
  return ((bottom - top).coerceAtLeast(0)).toFloat() / itemSize
}

/**
 * 事件攒批（契约 6.1）：攒满 [batchSize] 条立刻发，否则由调用方每 30 秒和退到后台时 [drain]。
 * 一批最多 [FeedApi.MAX_EVENTS] 条；发失败的丢掉，不重试、不落盘。
 */
class FeedEventBuffer(private val batchSize: Int = BATCH_SIZE) {
  companion object {
    const val BATCH_SIZE = 50
    const val FLUSH_INTERVAL_MILLIS = 30_000L
    const val TYPE_IMPRESSION = "impression"
  }

  private val pending = mutableListOf<FeedEventPayload>()

  @Synchronized
  fun add(event: FeedEventPayload): Boolean {
    pending += event
    return pending.size >= batchSize
  }

  /** 取走全部待发的，按每批最多 50 条切好。 */
  @Synchronized
  fun drain(): List<List<FeedEventPayload>> {
    if (pending.isEmpty()) return emptyList()
    val batches = pending.chunked(FeedApi.MAX_EVENTS).map { it.toList() }
    pending.clear()
    return batches
  }

  @get:Synchronized
  val size: Int
    get() = pending.size
}

fun impressionEvent(contentId: String, tab: FeedTab, at: Instant): FeedEventPayload {
  return FeedEventPayload(contentId = contentId, type = FeedEventBuffer.TYPE_IMPRESSION, surface = tab.apiName, occurredAt = at.toString())
}
