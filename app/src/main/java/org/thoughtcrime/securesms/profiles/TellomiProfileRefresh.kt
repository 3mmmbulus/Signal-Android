/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import kotlin.time.Duration.Companion.hours

/**
 * Tellomi（tellomi/tellomi#1366）：对方改了头像 / 名字，这边要及时一点看到。
 *
 * 服务端改资料时不通知任何人（上游设计），这边只能自己重拉。上游只在三种时候拉：打开会话（5 分钟去抖）、回前台的例行刷新
 * （12 小时一轮、只挑 1 天没拉过的人）、消息里的 profile key 变了——换头像 / 改名不换 key。这里补两条，都不动协议：
 * - 收到 1:1 对方的消息，距上次为这个人发起重拉超过 [ON_MESSAGE_INTERVAL] 就顺手拉一次（真正发不发请求还要过
 *   [RetrieveProfileJob] 自己的 5 分钟去抖）。节流记在内存里，重启清零——重启后每个人的第一条消息会拉一次。
 * - 例行刷新调勤：[ROUTINE_INTERVAL] 一轮、挑 [ROUTINE_STALE_AFTER] 没拉过的人（每轮最多 50 个照旧）。
 * 服务端资料接口的限流余量足够（#1366 正文）。
 */
object TellomiProfileRefresh {

  /** 收到 1:1 消息时，同一个人多久顺手重拉一次。 */
  val ON_MESSAGE_INTERVAL = 1.hours

  /** 例行刷新两轮之间至少隔多久（上游 12 小时）。 */
  val ROUTINE_INTERVAL = 2.hours

  /** 例行刷新挑多久没拉过的人（上游 1 天）。 */
  val ROUTINE_STALE_AFTER = 6.hours

  private const val MAX_TRACKED = 500

  private val lastRequestedAt = object : LinkedHashMap<RecipientId, Long>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RecipientId, Long>): Boolean = size > MAX_TRACKED
  }

  /**
   * 收到消息（[org.thoughtcrime.securesms.messages.DataMessageProcessor]，在处理消息的事务里）：1:1、不是自己，到了间隔就在事务
   * 提交后排一个 [RetrieveProfileJob]。群消息不管——群成员靠打开会话和例行刷新。
   */
  @JvmStatic
  @WorkerThread
  fun onIncomingMessage(sender: Recipient, isGroupMessage: Boolean) {
    if (isGroupMessage || sender.isSelf || sender.isGroup) {
      return
    }
    if (shouldRefreshOnMessage(sender.id, System.currentTimeMillis())) {
      SignalDatabase.runPostSuccessfulTransaction {
        RetrieveProfileJob.enqueue(sender.id, skipDebounce = false)
      }
    }
  }

  /** 到了间隔就记下这一次并返回 true。时钟往回拨过（now 比上次还早）也算到了，免得一直等到「上次」那个未来时间。 */
  @VisibleForTesting
  @Synchronized
  fun shouldRefreshOnMessage(recipientId: RecipientId, nowMillis: Long): Boolean {
    val last = lastRequestedAt[recipientId]
    if (last != null && nowMillis >= last && nowMillis - last < ON_MESSAGE_INTERVAL.inWholeMilliseconds) {
      return false
    }
    lastRequestedAt[recipientId] = nowMillis
    return true
  }

  @VisibleForTesting
  @Synchronized
  fun clearForTesting() {
    lastRequestedAt.clear()
  }
}
