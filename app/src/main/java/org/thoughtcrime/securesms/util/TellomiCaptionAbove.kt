/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.TellomiMessageExtras
import org.whispersystems.signalservice.internal.push.DataMessage

/**
 * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.7）：说明文字画在媒体上方；ADR-0075：整条消息的说明（输入框那一条）。
 *
 * 线上是 `DataMessage.tellomi.captionAboveMedia`（只在为真时写）和 `messageCaption`（只在有时写）；本机记在消息的
 * `message_extras` 里（[MessageExtras.tellomi]，在上游的 oneof 外面，不加列、不加迁移）。收到什么记什么，画的时候再看条件：
 * 有正文、至少一个按媒体显示的图片 / 视频（按文件发送的不算）。逐张说明记在各附件上游的 `caption` 上。
 */
object TellomiCaptionAbove {

  /** 收到 / 同步来的消息要记的扩展；没有这个标记就是 null（和上游一样不写这一列）。 */
  @JvmStatic
  fun extrasFrom(message: DataMessage): MessageExtras? {
    return extras(
      captionAboveMedia = message.tellomi?.captionAboveMedia == true,
      messageCaption = message.tellomi?.messageCaption
    )
  }

  @JvmStatic
  @JvmOverloads
  fun extras(captionAboveMedia: Boolean, messageCaption: String? = null): MessageExtras? {
    val caption = messageCaption?.takeIf { it.isNotEmpty() }
    return if (captionAboveMedia || caption != null) {
      MessageExtras(tellomi = TellomiMessageExtras(captionAboveMedia = captionAboveMedia, messageCaption = caption ?: ""))
    } else {
      null
    }
  }

  @JvmStatic
  fun isCaptionAboveMedia(extras: MessageExtras?): Boolean = extras?.tellomi?.captionAboveMedia == true

  /** ADR-0075：整条消息的说明；没有就是 null。 */
  @JvmStatic
  fun messageCaption(extras: MessageExtras?): String? = extras?.tellomi?.messageCaption?.takeIf { it.isNotEmpty() }

  /**
   * ADR-0075 §4.4（owner 2026-10-14 照 Telegram）：气泡里画哪段说明。一条消息里只有一个说明（整条的，或某一张的）就画它；
   * 两个及以上只画整条消息的说明，没有就不画（返回 ""）；逐张的在查看器里看（Telegram `GroupedMessages.calculate()`
   * 遇到第二个说明就把相册的说明清掉）。没有逐张说明、也没有整条说明的消息返回 null：照旧画 body（兼容文本）。
   */
  @JvmStatic
  fun bubbleCaption(record: MessageRecord): String? {
    val mediaCaptions = (record as? MmsMessageRecord)?.slideDeck?.slides.orEmpty().map { it.caption.orElse(null) }
    return bubbleCaption(messageCaption(record.messageExtras), mediaCaptions)
  }

  @JvmStatic
  fun bubbleCaption(messageCaption: String?, mediaCaptions: List<String?>): String? {
    val captions = listOfNotNull(messageCaption?.takeIf { it.isNotEmpty() }) + mediaCaptions.filterNot { it.isNullOrEmpty() }.filterNotNull()
    return when {
      captions.isEmpty() -> null
      captions.size == 1 -> captions[0]
      else -> messageCaption?.takeIf { it.isNotEmpty() } ?: ""
    }
  }

  /** 查看器里没有自己说明的那一张显示什么：有逐张说明的消息只给整条消息的说明（没有就是 ""）；别的消息返回 null，照旧用正文。 */
  @JvmStatic
  fun viewerFallback(record: MessageRecord): String? {
    return if (bubbleCaption(record) == null) null else messageCaption(record.messageExtras) ?: ""
  }

  /** 重发 / 发送任务从库里读回消息时只带这一部分，别的扩展（管理员删除状态等）不跟着进 [org.thoughtcrime.securesms.mms.OutgoingMessage]。 */
  @JvmStatic
  fun onlyTellomi(extras: MessageExtras?): MessageExtras? = extras?.tellomi?.let { MessageExtras(tellomi = it) }
}
