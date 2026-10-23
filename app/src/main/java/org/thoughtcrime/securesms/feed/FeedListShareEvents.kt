/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedEventPayload
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.time.Instant

/**
 * 列表卡片上直接分享也报 `share`（产品经理 2026-10-23 定），口径和详情页一样：
 * 复制链接、系统分享面板里选了目标 App（[FeedShareChooser]）才算 [FeedShareOutcome.COMPLETED]；取消、不能分享的卡片不报。
 *
 * `surface` 用卡片所在的列表：推荐 / 关注 / 热门栏用栏名，搜索页和话题页用 `search`（和从话题页点进详情一样），作者主页用 `profile`。
 * 引擎只认这五个值（`interactions.go` 的 `surfaces`，别的值整批 400 `invalid_surface`），**没有**「列表 / 详情」之分，
 * 所以同一个 surface 下列表分享和详情分享在引擎那边分不开；要分开得引擎加字段或新值，见汇报。
 *
 * 开关和详情事件同一条：内部开关「Feed: detail events」（默认关）**并且**引擎部署了 `events`（[feedDetailEventsEnabled]）。
 */
object FeedListShareEvents {
  private val TAG = Log.tag(FeedListShareEvents::class)
  private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val buffer = FeedEventBuffer()

  /** 纯逻辑：报不报、报什么。可单测。 */
  fun event(card: FeedCard, surface: FeedEventSurface?, outcome: FeedShareOutcome, enabled: Boolean, at: Instant): FeedEventPayload? {
    if (!enabled || surface == null) return null
    return outcome.event(card.id, surface, card.shareable, at)
  }

  /** 给 [LocalFeedShareListener] 用的回调：分享结束就攒进来并马上发（列表没有离开的时机可以兜底）。 */
  fun listener(surface: FeedEventSurface?): (FeedCard, FeedShareOutcome) -> Unit = { card, outcome -> report(card, surface, outcome) }

  fun report(card: FeedCard, surface: FeedEventSurface?, outcome: FeedShareOutcome) {
    val enabled = feedDetailEventsEnabled(SignalStore.internal.feedDetailEvents, FeedCapabilityStore.entries())
    val event = event(card, surface, outcome, enabled, Instant.now()) ?: return
    buffer.add(event)
    if (buffer.isThrottled()) return
    val source = FeedSources.current()
    SCOPE.launch {
      buffer.flush(
        send = { batch -> source.reportEvents(batch) },
        onDropped = { batch, e -> Log.w(TAG, "list share events dropped: ${batch.size}", e) }
      )
    }
  }
}
