/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import java.time.Instant

/**
 * 引擎认的 `surface`（`api/interactions.go` 的 `surfaces`）：`recommend`、`following`、`hot`、`search`、`profile`。
 * 推荐 / 关注两栏用栏的路由名；从搜索、主页点进详情时用 [SEARCH] / [PROFILE]。
 */
enum class FeedEventSurface(val apiName: String) {
  RECOMMEND("recommend"),
  FOLLOWING("following"),
  HOT("hot"),
  SEARCH("search"),
  PROFILE("profile");

  companion object {
    @JvmStatic
    fun of(tab: FeedTab): FeedEventSurface {
      return entries.firstOrNull { it.apiName == tab.apiName } ?: RECOMMEND
    }
  }
}

/**
 * 详情页的几种事件（`open` / `dwell` / `quick_exit` / `share`），和 `impressionEvent` 一样只负责造 [FeedEventPayload]。
 *
 * 语义按引擎 `docs/api.md` 和 `interactions.go`（2026-10-16 的决定，见 `docs/android-feed-layer/README.md`）：
 * open 兴趣 +1；dwell 只有 ≥ 30 秒才加权（+2）；quick_exit −0.5；share +5（热度 +3）。
 */
object FeedDetailEvents {
  const val TYPE_OPEN = "open"
  const val TYPE_DWELL = "dwell"
  const val TYPE_QUICK_EXIT = "quick_exit"
  const val TYPE_SHARE = "share"

  /** 引擎对 `value` 的范围：0 到 86400（含），超出整批 400 `invalid_event`，所以这里夹住。 */
  const val MAX_VALUE_SECONDS = 86_400.0

  /** 打开详情（点进一条帖子 / 文章）。`value` 不带。 */
  fun open(contentId: String, surface: FeedEventSurface, at: Instant): FeedEventPayload {
    return FeedEventPayload(contentId = contentId, type = TYPE_OPEN, surface = surface.apiName, occurredAt = at.toString())
  }

  /** 离开详情时的停留秒数。引擎只把 ≥ 30 秒的算一次深度阅读；调用方（[FeedDetailEventTracker]）不会为更短的停留造它。 */
  fun dwell(contentId: String, surface: FeedEventSurface, seconds: Double, at: Instant): FeedEventPayload {
    return FeedEventPayload(contentId = contentId, type = TYPE_DWELL, surface = surface.apiName, value = clamp(seconds), occurredAt = at.toString())
  }

  /** 打开后 3 秒内退出。`value` 放实际停留秒数（引擎不判定阈值，只要 `type` 是 `quick_exit` 就扣）。 */
  fun quickExit(contentId: String, surface: FeedEventSurface, seconds: Double, at: Instant): FeedEventPayload {
    return FeedEventPayload(contentId = contentId, type = TYPE_QUICK_EXIT, surface = surface.apiName, value = clamp(seconds), occurredAt = at.toString())
  }

  /** 分享完成。只在用户真的完成分享之后才造，见 [FeedShareOutcome]。 */
  fun share(contentId: String, surface: FeedEventSurface, at: Instant): FeedEventPayload {
    return FeedEventPayload(contentId = contentId, type = TYPE_SHARE, surface = surface.apiName, occurredAt = at.toString())
  }

  private fun clamp(seconds: Double): Double {
    if (!seconds.isFinite()) return 0.0
    return seconds.coerceIn(0.0, MAX_VALUE_SECONDS)
  }
}

/**
 * 分享动作的结果。只有 [COMPLETED]（复制链接成功、系统分享面板返回「已完成」）才报 `share`；
 * [CANCELLED]（点开面板又关掉、没选目标）什么都不报。引擎不核实这件事，所以这是客户端的责任。
 *
 * [shareable] 是卡片外层的 `shareable`（公开动态、作者不是私密账号）：不能分享的卡片没有分享入口，也就不该有 `share` 事件，
 * 引擎同样不检查这一点，所以在这里拦住。转发（`PUT …/repost`）有自己的接口和同样的兴趣权重，**不要**再补报一次 `share`。
 */
enum class FeedShareOutcome {
  COMPLETED,
  CANCELLED;

  fun event(contentId: String, surface: FeedEventSurface, shareable: Boolean, at: Instant): FeedEventPayload? {
    if (!shareable) return null
    return when (this) {
      COMPLETED -> FeedDetailEvents.share(contentId, surface, at)
      CANCELLED -> null
    }
  }
}

/**
 * 一次「打开详情 → 离开详情」要报的事件（纯逻辑，界面在出现 / 消失时调；和 [FeedImpressionTracker] 一样可单测）。
 *
 * - 打开：报 `open`。
 * - 离开：停留 ≤ [quickExitMillis]（3 秒，含 3 秒整）→ 报 `quick_exit`，不报 `dwell`；
 *   停留 ≥ [minDwellMillis]（30 秒）→ 报 `dwell`（`value` = 秒）；3 到 30 秒之间只有 `open`，不再补事件。
 * - 同时只有一次详情；还没离开就又打开了一个，先把上一个按「现在离开」收尾。
 * - 退到后台算离开（调用方在进入后台时调 [closed]）；回到前台不重新计时、也不重发 `open`。
 * - 时钟往回拨过：当作 0 秒。
 *
 * 阈值是产品侧的判断，可以调。引擎**没有**对 `quick_exit` 的 3 秒做校验，这个阈值完全由客户端把关；契约原文的 2 秒已作废。
 * 事件交给 `FeedEventBuffer.add`，每批最多 50 条的切分在那边。
 */
class FeedDetailEventTracker(
  private val reportsQuickExit: Boolean = true,
  private val quickExitMillis: Long = QUICK_EXIT_MILLIS,
  private val minDwellMillis: Long = MIN_DWELL_MILLIS
) {
  companion object {
    const val QUICK_EXIT_MILLIS = 3_000L
    const val MIN_DWELL_MILLIS = 30_000L
  }

  private class Open(val contentId: String, val surface: FeedEventSurface, val openedAtMillis: Long)

  private var current: Open? = null

  @get:Synchronized
  val isOpen: Boolean
    get() = current != null

  /** 打开了一条。返回要报的事件（前一个还没收尾的话，先带上它的收尾事件）。[nowMillis] 是墙上时间（`System.currentTimeMillis`）。 */
  @Synchronized
  fun opened(contentId: String, surface: FeedEventSurface, nowMillis: Long): List<FeedEventPayload> {
    val events = closed(nowMillis).toMutableList()
    current = Open(contentId, surface, nowMillis)
    events += FeedDetailEvents.open(contentId, surface, Instant.ofEpochMilli(nowMillis))
    return events
  }

  /** 离开了（返回、切标签、退到后台）。没有打开的详情就什么也不报。 */
  @Synchronized
  fun closed(nowMillis: Long): List<FeedEventPayload> {
    val open = current ?: return emptyList()
    current = null
    val elapsed = (nowMillis - open.openedAtMillis).coerceAtLeast(0L)
    val at = Instant.ofEpochMilli(nowMillis)
    if (reportsQuickExit && elapsed <= quickExitMillis) {
      return listOf(FeedDetailEvents.quickExit(open.contentId, open.surface, elapsed / 1000.0, at))
    }
    if (elapsed >= minDwellMillis) {
      return listOf(FeedDetailEvents.dwell(open.contentId, open.surface, elapsed / 1000.0, at))
    }
    return emptyList()
  }
}
