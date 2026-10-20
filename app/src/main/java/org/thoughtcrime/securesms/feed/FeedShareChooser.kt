/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统分享面板（`Intent.createChooser` 带 `IntentSender`）的选择结果（I-13 `share`，产品经理 2026-10-20 定）：
 * 用户在面板里**选了目标 App** 就算分享完成；两端口径统一为「选了分享目标」，不追求「真的发出去」。
 * 没选就关掉面板时系统不回调，什么都不报。
 *
 * 面板是别的进程画的，回调走 [FeedShareChosenReceiver]（不导出）；这里按一次性 token 把结果交回发起分享的那一页。
 * 只留最近 [MAX_PENDING] 个没回来的（取消的那些永远不会回来，不能一直攒着）；每个 token 只交付一次。
 * 进程被杀了再回来的结果拿不到 token，丢掉（不报，不会误报）。
 */
object FeedShareChooser {
  const val ACTION = "org.thoughtcrime.securesms.feed.SHARE_CHOSEN"
  const val EXTRA_TOKEN = "feed_share_token"
  const val MAX_PENDING = 8

  private val pending = LinkedHashMap<String, (FeedShareOutcome) -> Unit>()
  private var counter = 0L

  /** 登记一次分享，返回放进回调 Intent 的 token。 */
  @JvmStatic
  @Synchronized
  fun register(onFinished: (FeedShareOutcome) -> Unit): String {
    counter += 1
    val token = "share-$counter-${System.nanoTime()}"
    pending[token] = onFinished
    while (pending.size > MAX_PENDING) {
      pending.remove(pending.keys.first())
    }
    return token
  }

  /** 系统回调到了。[chosen] = 带着 `EXTRA_CHOSEN_COMPONENT`（选了目标 App）。返回 true = 交给了某一页。 */
  @JvmStatic
  fun deliver(token: String?, chosen: Boolean): Boolean {
    if (token == null) return false
    val callback = synchronized(this) { pending.remove(token) } ?: return false
    if (chosen) callback(FeedShareOutcome.COMPLETED)
    return true
  }

  @JvmStatic
  @Synchronized
  fun pendingCount(): Int = pending.size

  /** 测试用。 */
  @JvmStatic
  @Synchronized
  fun reset() {
    pending.clear()
  }
}

/** 系统分享面板选了目标之后的回调（`exported=false`，只有我们自己的 PendingIntent 能打进来）。 */
class FeedShareChosenReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != FeedShareChooser.ACTION) return
    @Suppress("DEPRECATION")
    val chosen = intent.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) != null
    FeedShareChooser.deliver(intent.getStringExtra(FeedShareChooser.EXTRA_TOKEN), chosen)
  }
}
