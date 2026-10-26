/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences

/**
 * Tellomi（审计 1.3 / F19 的一部分）：群里长按自己发的消息，「信息」那一项直接写已读人数，
 * 例如「已读 3/5」，点了还是进「信息」页看名单。对齐 iOS #377（`TellomiReadByMenuTitle`）。
 * 私聊、收到的消息、还没发出去 / 没有回执行的消息仍显示「信息」。
 * 按 M7：自己关了已读回执时不显示「已读 N/M」，退回「信息」。
 */
object TellomiReadByMenuTitle {

  private val TAG = Log.tag(TellomiReadByMenuTitle::class.java)

  data class Counts(val read: Int, val total: Int)

  /**
   * 只数真正的收件人：跳过（skipped）的不算；已读和已看（viewed）都算已读。
   * [statuses] 是 [GroupReceiptTable] 的 STATUS_* 常量。
   */
  @JvmStatic
  fun counts(statuses: Iterable<Int>): Counts {
    var read = 0
    var total = 0
    for (status in statuses) {
      if (status == GroupReceiptTable.STATUS_SKIPPED) continue
      total++
      if (status == GroupReceiptTable.STATUS_READ || status == GroupReceiptTable.STATUS_VIEWED) {
        read++
      }
    }
    return Counts(read = read, total = total)
  }

  /**
   * 没有收件人时返回 null，调用方退回「信息」。
   * 按 M7：我自己关了已读回执时不显示已读状态，也返回 null。
   */
  @JvmStatic
  @JvmOverloads
  fun title(format: String, counts: Counts, readReceiptsEnabled: Boolean = true): String? {
    if (!readReceiptsEnabled) return null
    if (counts.total <= 0) return null
    return String.format(format, counts.read, counts.total)
  }

  /**
   * 群里自己发出的消息：查本地群回执，拼「已读 N/M」；否则 null。
   * 回执表空 / 查库失败也返回 null（菜单照旧显示「信息」）。不改协议，只用本地回执。
   */
  @JvmStatic
  fun titleFor(context: Context, conversationRecipient: Recipient, messageRecord: MessageRecord): String? {
    if (!conversationRecipient.isGroup || !messageRecord.isOutgoing) return null

    val readReceiptsEnabled = TextSecurePreferences.isReadReceiptsEnabled(context)
    if (!readReceiptsEnabled) return null

    val statuses = groupReceiptStatuses(messageRecord.id) ?: return null
    if (statuses.isEmpty()) return null

    return title(
      format = context.getString(R.string.conversation_selection__menu_tellomi_read_by_format),
      counts = counts(statuses),
      readReceiptsEnabled = true
    )
  }

  /** 菜单项最终文案：「已读 N/M」或默认「信息」。 */
  @JvmStatic
  fun menuTitle(context: Context, conversationRecipient: Recipient, messageRecord: MessageRecord): String {
    return titleFor(context, conversationRecipient, messageRecord)
      ?: context.getString(R.string.conversation_selection__menu_message_details)
  }

  @VisibleForTesting
  internal fun groupReceiptStatuses(messageId: Long): List<Int>? {
    return try {
      SignalDatabase.groupReceipts.getGroupReceiptInfo(messageId).map { it.status }
    } catch (e: RuntimeException) {
      Log.w(TAG, "Failed to read group receipts for menu title", e)
      null
    }
  }
}
