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

  /**
   * 「信息」页群成员的逐人时间：GroupReceiptTable 每人只存当前状态的一个时间，已读 / 已查看被藏成已送达时
   * 那个时间是对方的已读时间，送达时间已被覆盖，所以不显示时间（返回 0，RecipientViewHolder 不画）。
   * 真正停在已送达的，时间就是送达时间，照常显示。
   */
  @JvmStatic
  fun groupTimestampForDetails(groupStatus: Int, timestamp: Long, readReceiptsEnabled: Boolean): Long {
    return if (groupStatusForDetails(groupStatus, readReceiptsEnabled) != groupStatus) 0L else timestamp
  }

  /**
   * 一对一「信息」页的时间：MessageTable 每条消息只存一个 receipt_timestamp，收到已读后就被已读时间覆盖。
   * 已读 / 已查看被藏成已送达时一律不显示时间（返回 0），绝不显示对方的已读时间（产品决定，不另存送达时间）。
   */
  @JvmStatic
  fun oneToOneReceiptTimestampForDetails(receiptTimestamp: Long, isOutgoingNonStory: Boolean, hadReadOrViewed: Boolean, readReceiptsEnabled: Boolean): Long {
    return if (!readReceiptsEnabled && isOutgoingNonStory && hadReadOrViewed) 0L else receiptTimestamp
  }

  /** 聊天列表只有最后一条的第一个附件类型（ThreadTable.getContentTypeFor）。 */
  @JvmStatic
  fun isTextOnlySnippet(snippetContentType: String?): Boolean {
    return snippetContentType == null || MediaUtil.isLongTextType(snippetContentType)
  }

  /** Tellomi M7 (#1186)：一勾淡入 150 ms、第二个勾长出 220 ms，和电脑端 / iOS 一样。 */
  const val SENT_FADE_IN_MS = 150L
  const val READ_GROW_MS = 220L

  /** 两勾图标（18×12）里第一个勾的右边缘约在 11.5 / 18 处，长出动画从这里裁起。 */
  const val READ_GROW_START_LEVEL = 6_400

  enum class Animation {
    /** 发送中 → 一个勾：淡入 */
    SENT,

    /** 发送中 / 一个勾 → 两个勾：第二个勾从第一个勾处长出 */
    READ
  }

  /**
   * Tellomi M7 (#1186)：同一条消息留在屏幕上时看到的状态变化才动。
   * [sameMessage] 为 false（打开会话、滚动 / 回翻换绑到别的消息、列表行换成另一条消息）一律不动；
   * 第一次画（[previous] 为 null）也不动。
   */
  @JvmStatic
  fun statusAnimation(previous: Display?, next: Display?, sameMessage: Boolean): Animation? {
    if (!sameMessage || previous == null || next == null || previous == next) {
      return null
    }
    return when {
      previous == Display.SENDING && next == Display.SENT -> Animation.SENT
      next == Display.READ -> Animation.READ
      else -> null
    }
  }

  /**
   * Tellomi（产品经理裁定，三端统一）：首发提示看的是「同一条消息在屏上第一次变成已发出」——
   * 从发送中变成一个勾，或者直接变成两个勾（对方秒读、送达和已读一起到）都算。
   * 换绑到别的消息（[sameMessage] 为 false）、第一次画（[previous] 为 null）不算；和系统动画开关无关。
   */
  @JvmStatic
  fun isSeenSent(previous: Display?, next: Display?, sameMessage: Boolean): Boolean {
    return sameMessage && previous == Display.SENDING && (next == Display.SENT || next == Display.READ)
  }

  /**
   * Tellomi M7 (#1186)：本机第一次发出后一次性提示「一个勾：已发出；两个勾：对方已读」。
   * 笔记（Note to Self）、编辑、故事回复上勾的意思不一样，不触发。
   */
  @JvmStatic
  fun shouldShowChecksHint(alreadyShown: Boolean, isNoteToSelf: Boolean, isEdit: Boolean, isStoryReply: Boolean): Boolean {
    return !alreadyShown && !isNoteToSelf && !isEdit && !isStoryReply
  }

  /**
   * Tellomi M7 (#1186)：气泡上已读两勾什么时候用强调色。同 iOS CVComponentFooter：只有在气泡外（贴纸、大表情，没有底色）
   * 且没有壁纸垫底时用强调色；有底色的发出气泡、有壁纸时沿用时间颜色。聊天列表、「信息」页不走这里，固定用强调色。
   */
  @JvmStatic
  fun usesAccentForReadChecks(hasNoBubble: Boolean, hasWallpaper: Boolean): Boolean {
    return hasNoBubble && !hasWallpaper
  }
}
