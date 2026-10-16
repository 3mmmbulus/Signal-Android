/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.chats

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tellomi（交互审计 A-26「点开会话要先等……首帧最多 5 s，期间列表冻住没有反馈」）：点开会话时，聊天页首帧最多等 [MAX_WAIT]
 * 就开始转场。原来最多等 5 s：慢机上点了以后会话列表冻在原处、没有任何反应，最坏要等 5 秒。
 *
 * 照 Telegram 安卓（只读）：`ActionBarLayout` 打开聊天时，如果首屏消息还在加载（`ChatActivity.needDelayOpenAnimation()`
 * 返回 `firstLoading`），先等消息到（`resumeDelayedFragmentAnimation()`），但最多 200 ms
 * （`runOnUIThread(delayedOpenAnimationRunnable, 200)`），到点照样开。到点还没好的，聊天页先进来，消息随后出现。
 *
 * 模拟器实测（Saved Messages，没有壁纸）：点下到开始转场，第一次 445 ms、之后 149–184 ms，都在首帧到了以后才开始，
 * 这个上限碰不到；它只在慢机上起作用。
 */
object TellomiFirstFrameWait {

  val MAX_WAIT: Duration = 200.milliseconds

  /** 等 [contentReady] 变成 true，最多 [maxWait]。等到了返回 true；到点没等到返回 false（照样开始转场）。 */
  suspend fun await(contentReady: StateFlow<Boolean>, maxWait: Duration = MAX_WAIT): Boolean {
    return withTimeoutOrNull(maxWait) { contentReady.first { it } } != null
  }
}
