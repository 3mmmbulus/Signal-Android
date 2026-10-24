/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.util.SqlUtil
import org.signal.core.util.logging.Log
import org.signal.core.util.requireLong
import org.signal.core.util.select
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId

/**
 * Tellomi：已读回执按「消息到达时」的开关判断（tellomi/tellomi#1183，需求 message-status-and-read-receipts §3.4 第 3 条）。
 *
 * 到达时我关着已读回执的消息，永远不发已读 / 已查看 / 已播放回执，即使之后打开开关再读。
 *
 * 不给消息表加列（改表结构每次合上游都要重排迁移号），改成记「开关在什么时候切换过」，
 * 再用消息的本地到达时间（date_received）查当时开没开。拿不准的一律当关着（宁可不发）：
 * - 升级到这一版之前没有记录：开关现在开着 → 都当开着；现在关着 → 等打开时记下一笔，之前到达的都当关着；
 * - 记录只留最近 [MAX_EVENTS] 笔，更早的丢掉后，早于最老一笔的消息当关着；
 * - 设置从别的设备同步过来时，按本机收到同步的时间记。
 */
object TellomiReadReceiptHistory {

  private val TAG = Log.tag(TellomiReadReceiptHistory::class.java)

  private const val PREF_KEY = "tellomi.read_receipts_history"

  @VisibleForTesting
  const val MAX_EVENTS = 64

  data class Event(val atMillis: Long, val enabled: Boolean)

  data class History(val events: List<Event>, val truncated: Boolean) {
    companion object {
      @JvmField
      val EMPTY = History(emptyList(), truncated = false)
    }
  }

  class Filtered<T>(val messageIds: List<MessageId>, val values: List<T>)

  @VisibleForTesting
  var clock: () -> Long = { System.currentTimeMillis() }

  /** 开关写进偏好之后调用；[hadValue] 为 false（首次初始化）或值没变时不记。 */
  @JvmStatic
  @Synchronized
  fun onSettingWritten(context: Context, hadValue: Boolean, previous: Boolean, enabled: Boolean) {
    if (!hadValue || previous == enabled) {
      return
    }

    TextSecurePreferences.setStringPreference(context, PREF_KEY, encode(append(readHistory(context), Event(clock(), enabled))))
  }

  @VisibleForTesting
  fun readHistory(context: Context): History {
    return decode(TextSecurePreferences.getStringPreference(context, PREF_KEY, null))
  }

  @JvmStatic
  fun wasEnabledAt(history: History, currentlyEnabled: Boolean, arrivedAtMillis: Long): Boolean {
    if (history.events.isEmpty()) {
      return currentlyEnabled
    }

    // 按时间取：本机时钟往回拨过时，记下的先后不一定是时间的先后
    val events = history.events.sortedBy { it.atMillis }
    val last = events.lastOrNull { it.atMillis <= arrivedAtMillis }
    if (last != null) {
      return last.enabled
    }

    return if (history.truncated) false else !events.first().enabled
  }

  /**
   * 只留下到达时开着已读回执的消息；[values] 和 [messageIds] 按下标一一对应（回执里的发送时间戳）。
   * 查不到到达时间的消息（已删掉）不发。
   */
  @JvmStatic
  @WorkerThread
  fun <T> filterArrivedWhileEnabled(context: Context, messageIds: List<MessageId>, values: List<T>): Filtered<T> {
    if (messageIds.size != values.size) {
      Log.w(TAG, "Message ID count (${messageIds.size}) does not match value count (${values.size}), sending nothing.")
      return Filtered(emptyList(), emptyList())
    }

    val filtered = filter(readHistory(context), TextSecurePreferences.isReadReceiptsEnabled(context), getDateReceived(messageIds), messageIds, values)

    if (filtered.messageIds.size != messageIds.size) {
      Log.i(TAG, "Skipping receipts for ${messageIds.size - filtered.messageIds.size} of ${messageIds.size} message(s) that arrived while read receipts were off.")
    }

    return filtered
  }

  @VisibleForTesting
  fun <T> filter(history: History, currentlyEnabled: Boolean, dateReceived: Map<Long, Long>, messageIds: List<MessageId>, values: List<T>): Filtered<T> {
    val keptIds = mutableListOf<MessageId>()
    val keptValues = mutableListOf<T>()

    messageIds.forEachIndexed { index, messageId ->
      val arrivedAt = dateReceived[messageId.id]
      if (arrivedAt != null && wasEnabledAt(history, currentlyEnabled, arrivedAt)) {
        keptIds += messageId
        keptValues += values[index]
      }
    }

    return Filtered(keptIds, keptValues)
  }

  @VisibleForTesting
  fun append(history: History, event: Event): History {
    val events = history.events + event
    return if (events.size > MAX_EVENTS) {
      History(events.takeLast(MAX_EVENTS), truncated = true)
    } else {
      History(events, history.truncated)
    }
  }

  /** 形如 `0;1727250000000:0,1727260000000:1`：开头 1 = 丢过更早的记录。 */
  @VisibleForTesting
  fun encode(history: History): String {
    return (if (history.truncated) "1" else "0") + ";" + history.events.joinToString(",") { "${it.atMillis}:${if (it.enabled) 1 else 0}" }
  }

  @VisibleForTesting
  fun decode(value: String?): History {
    if (value.isNullOrEmpty()) {
      return History.EMPTY
    }

    return try {
      val parts = value.split(";", limit = 2)
      val events = if (parts.size < 2 || parts[1].isEmpty()) {
        emptyList()
      } else {
        parts[1].split(",").map { item ->
          val (at, enabled) = item.split(":", limit = 2)
          Event(at.toLong(), enabled == "1")
        }
      }
      History(events, truncated = parts[0] == "1")
    } catch (e: RuntimeException) {
      Log.w(TAG, "Unreadable read receipt history, falling back to the current setting.", e)
      History.EMPTY
    }
  }

  private fun getDateReceived(messageIds: List<MessageId>): Map<Long, Long> {
    val result = mutableMapOf<Long, Long>()

    SqlUtil.buildCollectionQuery(MessageTable.ID, messageIds.map { it.id }).forEach { query ->
      SignalDatabase.readableDatabase
        .select(MessageTable.ID, MessageTable.DATE_RECEIVED)
        .from(MessageTable.TABLE_NAME)
        .where(query.where, query.whereArgs)
        .run()
        .use { cursor ->
          while (cursor.moveToNext()) {
            result[cursor.requireLong(MessageTable.ID)] = cursor.requireLong(MessageTable.DATE_RECEIVED)
          }
        }
    }

    return result
  }
}
