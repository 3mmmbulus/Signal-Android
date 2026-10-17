/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.net.FeedApi
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedCooldown
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import org.thoughtcrime.securesms.feed.net.FeedRetryAfter
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
 * 事件攒批（契约 6.1）：攒满 [batchSize] 条立刻发，否则由调用方每 30 秒和退到后台时 [flush]。
 * 一批最多 [FeedApi.MAX_EVENTS] 条；普通的发送失败丢掉那一批，不重试、不落盘。
 *
 * 被限流（[FeedApiException.isThrottled]：429，或带 `Retry-After` 的 503）是例外：**停止发后面的批次，没发出去的事件留在队列里不丢**，
 * 按 `Retry-After`（[FeedApiException.retryAfterSeconds]，已夹在 1…3600 秒）或 [defaultThrottleMillis] 等够了才再发；等的时候 [flush] 什么都不做。
 * 队列最多留 [maxQueued] 条（超了丢最旧的：引擎本来就丢 24 小时前的事件，也别让一小时的冷却把内存撑大）。
 */
class FeedEventBuffer(
  private val batchSize: Int = BATCH_SIZE,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxQueued: Int = MAX_QUEUED,
  private val defaultThrottleMillis: Long = FeedCooldown.BASE_MILLIS
) {
  companion object {
    const val BATCH_SIZE = 50
    const val FLUSH_INTERVAL_MILLIS = 30_000L
    const val MAX_QUEUED = 1000
    const val TYPE_IMPRESSION = "impression"
  }

  private val pending = mutableListOf<FeedEventPayload>()
  private var resumeAtMillis = 0L
  private var throttledAtMillis = 0L

  @Synchronized
  fun add(event: FeedEventPayload): Boolean {
    pending += event
    trim()
    return pending.size >= batchSize && !isThrottled()
  }

  private fun trim() {
    if (pending.size > maxQueued) pending.subList(0, pending.size - maxQueued).clear()
  }

  /** 还在限流冷却里（[flush] 现在不会发）。时钟往回拨过算已经结束。 */
  @Synchronized
  fun isThrottled(): Boolean {
    val now = clock()
    return now >= throttledAtMillis && now < resumeAtMillis
  }

  /** 没发出去的批次放回队列最前面（保持原来的顺序）。 */
  @Synchronized
  private fun requeue(batches: List<List<FeedEventPayload>>) {
    pending.addAll(0, batches.flatten())
    trim()
  }

  @Synchronized
  private fun throttle(retryAfterSeconds: Long?) {
    val now = clock()
    val wait = retryAfterSeconds?.let { FeedRetryAfter.clamp(it) * 1000 } ?: defaultThrottleMillis
    if (isThrottled()) {
      resumeAtMillis = maxOf(resumeAtMillis, now + wait) // 已经在冷却：取更晚的结束时间，不缩短
    } else {
      throttledAtMillis = now
      resumeAtMillis = now + wait
    }
  }

  /**
   * 把攒着的事件按每批最多 50 条发出去；[send] 一批一批调。
   * - 冷却中：什么都不做；
   * - 被限流：这一批和后面的批次放回队列，记下什么时候能再发，停；
   * - 别的失败（包括协程取消以外的任何异常）：[onDropped] 之后丢掉这一批，接着发下一批；
   * - 协程被取消：没发出去的放回队列再抛出取消。
   */
  suspend fun flush(send: suspend (List<FeedEventPayload>) -> Unit, onDropped: (batch: List<FeedEventPayload>, error: Exception) -> Unit = { _, _ -> }) {
    if (isThrottled()) return
    val batches = drain()
    for ((index, batch) in batches.withIndex()) {
      try {
        send(batch)
      } catch (e: kotlinx.coroutines.CancellationException) {
        requeue(batches.drop(index))
        throw e
      } catch (e: FeedApiException) {
        if (e.isThrottled) {
          throttle(e.retryAfterSeconds)
          requeue(batches.drop(index))
          return
        }
        onDropped(batch, e)
      } catch (e: Exception) {
        onDropped(batch, e)
      }
    }
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
