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

    /** 从 Intent extra 还原；null / 不认识 = 不报（通知、我的动态、外链点进来的没有对应的 `surface`，和 iOS 一样）。 */
    @JvmStatic
    fun fromApiName(apiName: String?): FeedEventSurface? {
      return entries.firstOrNull { it.apiName == apiName }
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
 * 分享动作的结果。只有 [COMPLETED]（复制链接成功、系统分享面板里选了目标 App）才报 `share`；
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

/**
 * 一个详情页的事件生命周期（I-13），口径对齐 iOS `FeedPostDetailViewController` 的 `openDetailEvent` / `closeDetailEvent` / `shareFinished`：
 *
 * - 整个页面生命里只报一次 `open`（盖上别的页面再回来、退到后台再回前台都不重报、不重新计时）；
 * - 离开（返回 / 关掉，或者退到后台）只收尾一次：≤ 3 秒 `quick_exit`，≥ 30 秒 `dwell`，中间什么都不报；收尾之后这页不再报 open；
 * - 分享：只有完成（复制链接、系统分享面板里选了目标 App）才报 `share`；取消、不能分享（`shareable` 为 false）、不是这页的帖子都不报；
 * - [enabled] 为 false 或 [surface] 为 null：什么都不报。
 *
 * 事件进 [buffer]（和曝光同一套攒批规则：满 50 条要发、限流时留在队列里）；调用方按 [open] / [close] / [shareFinished] 的返回值决定要不要马上 flush。
 * Android 的详情页是单独的 Activity、拿不到信息流的 ViewModel，所以 buffer 跟着详情页走；iOS 只在退到后台时 flush，
 * Android 在退到后台**和**离开详情时都 flush（页面关掉后这份 buffer 就没了，不 flush 等于丢）。
 */
class FeedDetailEventSession(
  private val contentId: String,
  private val surface: FeedEventSurface?,
  private val enabled: Boolean,
  private val tracker: FeedDetailEventTracker = FeedDetailEventTracker(),
  val buffer: FeedEventBuffer = FeedEventBuffer()
) {
  private var opened = false
  private var closed = false

  private val active: Boolean
    get() = enabled && surface != null

  /** 页面出现。第一次报 `open`；返回 true = 攒满了，该马上发。 */
  @Synchronized
  fun open(nowMillis: Long): Boolean {
    val surface = surface ?: return false
    if (!enabled || opened || closed) return false
    opened = true
    return enqueue(tracker.opened(contentId, surface, nowMillis))
  }

  /** 离开（返回 / 关掉 / 退到后台）。只收尾一次；返回 true = 有东西要发（调用方随后 flush）。 */
  @Synchronized
  fun close(nowMillis: Long): Boolean {
    if (!opened || closed) return false
    closed = true
    enqueue(tracker.closed(nowMillis))
    return buffer.size > 0
  }

  /** 一次分享结束。返回 true = 攒满了，该马上发。 */
  @Synchronized
  fun shareFinished(cardId: String, shareable: Boolean, outcome: FeedShareOutcome, nowMillis: Long): Boolean {
    val surface = surface ?: return false
    if (!active || cardId != contentId) return false
    val event = outcome.event(contentId, surface, shareable, java.time.Instant.ofEpochMilli(nowMillis)) ?: return false
    return enqueue(listOf(event))
  }

  @get:Synchronized
  val isOpen: Boolean
    get() = opened && !closed

  private fun enqueue(events: List<FeedEventPayload>): Boolean {
    var full = false
    for (event in events) full = buffer.add(event) || full
    return full
  }
}
