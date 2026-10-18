/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.MentionUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.adjustBodyRanges
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.PinnedMessage
import org.thoughtcrime.securesms.database.withAttachments
import org.thoughtcrime.securesms.maps.TellomiLocationBubble
import java.text.BreakIterator

/**
 * Tellomi（ADR-0081 §4.4，pinned-messages.md 3.2）：置顶系统消息的那一句——「××置顶了«前 15 个字…»」、「你置顶了一张图片」，
 * 被置顶的那条已经删了就是「××置顶了一条消息」。会话里的系统消息、聊天列表的预览、通知都用这一句。
 *
 * 照 Telegram 两端（只读机制，没搬代码）：iOS `TelegramStringFormatting/Sources/ServiceMessageStrings.swift` 置顶那一段
 * （一行、换行变空格，多于 15 个字截成 15 个加省略号；`Notification_PinnedTextMessage` / `…PhotoMessage` 等按内容各一种说法）；
 * 安卓 `MessageObject.java` 置顶那一段（`ActionPinnedText` 截 20 个字，`ActionPinnedPhoto` 等）。两端截的长度不同，取 15（spec 3.2「15–20」）。
 * 带说明的图片、视频按媒体说（两端相同）。
 */
object TellomiPinNotice {

  /** 引号里最多几个字（按字形算，不拆开表情）。 */
  const val PREVIEW_LENGTH = 15

  /** 这条置顶系统消息的那一句。要读被置顶的那条，放后台线程。 */
  @WorkerThread
  @JvmStatic
  fun describe(context: Context, pinUpdate: MessageRecord): String {
    val subject = subject(context, pinUpdate.messageExtras?.pinnedMessage)
    return if (pinUpdate.fromRecipient.isSelf) {
      context.getString(R.string.TellomiPinNotice__you_pinned_s, subject)
    } else {
      context.getString(R.string.TellomiPinNotice__s_pinned_s, pinUpdate.fromRecipient.getDisplayName(context), subject)
    }
  }

  /**
   * 别人的「为双方」置顶要不要推送：只有群里、而且没选「置顶但不通知」（`pinSilent`）时推；未读、聊天列表照样算。
   * 单聊不推：Telegram iOS 单聊置顶会推，安卓单聊是静默的（`ChatActivity` 单聊 `checks = {false, false}`，`notify = false`），
   * 只有一端有，按 owner 10-14 的规则不做；「置顶消息」通知开关两端都没有，也不加。自己的、「仅为我」的置顶都没有通知。
   */
  @JvmStatic
  fun notifies(extras: MessageExtras?, isGroup: Boolean): Boolean {
    return isGroup && extras?.pinnedMessage?.tellomiSilent != true
  }

  /** 「«…»」或「一张图片」之类；找不到被置顶的那条就是「一条消息」。 */
  @WorkerThread
  private fun subject(context: Context, pinned: PinnedMessage?): String {
    val target = pinned?.pinnedMessageId
      ?.takeIf { it > 0 }
      ?.let { SignalDatabase.messages.getMessageRecordOrNull(it) }
      ?.let { record ->
        // The pin update keeps pointing at the revision that was pinned; show what the message says now.
        val latest = (record as? MmsMessageRecord)?.latestRevisionId?.let { SignalDatabase.messages.getMessageRecordOrNull(it.id) }
        latest ?: record
      }
      ?.withAttachments()

    return target?.let { subjectOf(context, it) } ?: context.getString(R.string.TellomiPinNotice__a_message)
  }

  @WorkerThread
  private fun subjectOf(context: Context, record: MessageRecord): String? {
    if (record.isRemoteDelete) {
      return null
    }

    if (record is MmsMessageRecord) {
      val label = when {
        record.sharedContacts.isNotEmpty() -> R.string.TellomiPinNotice__a_contact
        TellomiLocationBubble.recognize(record) != null -> R.string.TellomiPinNotice__a_location
        SignalDatabase.polls.getPollId(record.id) != null -> R.string.TellomiPinNotice__a_poll
        record.slideDeck.stickerSlide != null -> R.string.TellomiPinNotice__a_sticker
        else -> {
          val slide = record.slideDeck.slides.firstOrNull { it.hasImage() || it.hasVideo() || it.hasAudio() || it.hasDocument() }
          when {
            slide == null -> null
            slide.isVideoGif || MediaUtil.isGif(slide.contentType) -> R.string.TellomiPinNotice__a_gif
            slide.hasVideo() -> R.string.TellomiPinNotice__a_video
            slide.hasImage() -> R.string.TellomiPinNotice__a_photo
            slide.hasAudio() && slide.asAttachment().voiceNote -> R.string.TellomiPinNotice__a_voice_message
            slide.hasAudio() -> R.string.TellomiPinNotice__an_audio_file
            else -> R.string.TellomiPinNotice__a_file
          }
        }
      }
      if (label != null) {
        return context.getString(label)
      }
    }

    val text = bodyWithSpoilersHidden(context, record)
    return if (text.isBlank()) null else context.getString(R.string.TellomiPinNotice__quote, clip(text))
  }

  /** 正文，@ 换成名字，剧透遮成「■■■■」（和通知一样）。 */
  private fun bodyWithSpoilersHidden(context: Context, record: MessageRecord): String {
    val updated = MentionUtil.updateBodyWithDisplayNames(context, record)
    var text: CharSequence = updated.body ?: ""

    val spoilers = record.messageRanges
      .adjustBodyRanges(updated.bodyAdjustments)
      ?.ranges
      ?.filter { it.style == BodyRangeList.BodyRange.Style.SPOILER }
      ?.sortedByDescending { it.start }
      .orEmpty()

    for (spoiler in spoilers) {
      val start = spoiler.start.coerceIn(0, text.length)
      val end = (spoiler.start.toLong() + spoiler.length).coerceIn(start.toLong(), text.length.toLong()).toInt()
      text = text.replaceRange(start, end, "■■■■")
    }
    return text.toString()
  }

  /** 一行（换行变空格），多于 [PREVIEW_LENGTH] 个字截断加「…」。 */
  @JvmStatic
  fun clip(text: String): String {
    val oneLine = text.replace(Regex("\\s*\\n\\s*"), " ").trim()
    val breaks = BreakIterator.getCharacterInstance().apply { setText(oneLine) }
    var end = 0
    repeat(PREVIEW_LENGTH) {
      val next = breaks.next()
      if (next == BreakIterator.DONE) {
        return oneLine
      }
      end = next
    }
    return if (breaks.next() == BreakIterator.DONE) oneLine else oneLine.substring(0, end) + "…"
  }
}
