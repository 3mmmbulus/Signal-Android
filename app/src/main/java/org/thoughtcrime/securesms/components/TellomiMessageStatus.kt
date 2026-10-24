/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.TextSlide
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi：气泡与聊天列表上的发送状态只有两档勾（tellomi/tellomi#1183，需求 message-status-and-read-receipts §3.1–3.2）。
 *
 * - 一个勾 = 服务器已收下。上游的「已发送」「已送达」都画成一个勾，送达时间只在长按 →「信息」里看；
 * - 两个勾 = 已读，一次性媒体「已查看」、语音「已播放」也算（关着已读回执时 MessageTable 已经把它们藏起来）；
 * - 没有真正附件的消息（纯文字、带引用、带链接预览、长文字）发送中的前 2 秒什么都不画，超过 2 秒才转圈；
 *   有附件的照旧一发就转圈（媒体上还有上传进度）。
 */
object TellomiMessageStatus {

  const val TEXT_SENDING_INDICATOR_DELAY_MS = 2_000L

  enum class Display {
    SENDING,
    SENT,
    READ
  }

  @JvmStatic
  fun display(isPending: Boolean, hasReadReceipt: Boolean, isViewed: Boolean): Display {
    return when {
      isPending -> Display.SENDING
      hasReadReceipt || isViewed -> Display.READ
      else -> Display.SENT
    }
  }

  /**
   * 发送中的转圈还要等多久才露出来；0 = 立即。
   * 从消息的发送时间算起，本机时钟往回拨时最多等 2 秒。
   */
  @JvmStatic
  fun sendingIndicatorDelayMs(isTextOnly: Boolean, sentAtMillis: Long, nowMillis: Long): Long {
    if (!isTextOnly) {
      return 0
    }

    return (TEXT_SENDING_INDICATOR_DELAY_MS - (nowMillis - sentAtMillis)).coerceIn(0, TEXT_SENDING_INDICATOR_DELAY_MS)
  }

  /**
   * 按有没有真正的附件判断，不按显示样式：slideDeck 已经排除了引用缩略图、链接预览图和联系人头像，
   * 剩下的只要全是长文字（[TextSlide]）就算文字。
   */
  @JvmStatic
  fun isTextOnly(record: MessageRecord): Boolean {
    if (record !is MmsMessageRecord) {
      return true
    }

    return record.slideDeck.slides.all { it is TextSlide }
  }

  /**
   * 「信息」页里群成员的逐人状态：我关着已读回执时，已读 / 已查看也按已送达显示（对等规则）。
   * 一对一的消息 MessageTable 读出来时已经藏好，群的逐人状态直接来自 GroupReceiptTable，要在这里藏。
   */
  @JvmStatic
  fun groupStatusForDetails(groupStatus: Int, readReceiptsEnabled: Boolean): Int {
    if (!readReceiptsEnabled && (groupStatus == GroupReceiptTable.STATUS_READ || groupStatus == GroupReceiptTable.STATUS_VIEWED)) {
      return GroupReceiptTable.STATUS_DELIVERED
    }
    return groupStatus
  }

  /** 聊天列表只有最后一条的第一个附件类型（ThreadTable.getContentTypeFor）。 */
  @JvmStatic
  fun isTextOnlySnippet(snippetContentType: String?): Boolean {
    return snippetContentType == null || MediaUtil.isLongTextType(snippetContentType)
  }
}
