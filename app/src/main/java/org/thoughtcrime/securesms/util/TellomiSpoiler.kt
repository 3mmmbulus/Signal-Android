/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.signal.core.models.database.AttachmentId
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import java.util.concurrent.ConcurrentHashMap

/**
 * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.1，spec R-1）：收到的被罩住的图片 / 视频。
 *
 * 罩着 = 发送方在附件上标了 spoiler（`TransformProperties.tellomiSpoiler`），而这台设备还没揭示过。揭示状态在这台设备上记住
 * （D10，旧 ADR-0050 owner 定过；Telegram 离开再回来会重新罩住），不上线路、不进备份。
 * 揭示先记在内存里马上生效（数据库刷新、会话重绑之前不会又罩回去），再写进库里。
 */
object TellomiSpoiler {

  private val revealedThisSession: MutableSet<AttachmentId> = ConcurrentHashMap.newKeySet()

  @JvmStatic
  fun isCovered(attachment: Attachment?): Boolean {
    if (attachment?.transformProperties?.isTellomiSpoilerCovered != true) {
      return false
    }
    return !(attachment is DatabaseAttachment && attachment.attachmentId in revealedThisSession)
  }

  @JvmStatic
  fun isCovered(slide: Slide?): Boolean = slide != null && isCovered(slide.asAttachment())

  /** 一条消息里只要有一个还罩着的（通知、会话列表这类只露一张缩略图的地方就不露）。 */
  @JvmStatic
  fun hasCovered(slideDeck: SlideDeck?): Boolean = slideDeck?.slides?.any { isCovered(it) } == true

  /** 揭示 [attachment]：从现在起这台设备上都不再罩着。 */
  @JvmStatic
  fun reveal(attachment: Attachment) {
    val attachmentId = (attachment as? DatabaseAttachment)?.attachmentId ?: return
    if (revealedThisSession.add(attachmentId)) {
      SignalExecutors.BOUNDED_IO.execute { SignalDatabase.attachments.setTellomiSpoilerRevealed(attachmentId) }
    }
  }
}
