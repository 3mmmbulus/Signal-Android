/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.net.Uri
import org.signal.core.models.media.Media
import org.thoughtcrime.securesms.sharing.MultiShareArgs

/**
 * Tellomi（审计 5.4，对齐 iOS #382 + 界面 516b3403ee）：转发时「隐藏发送人 / 隐藏说明文字」。
 *
 * - 发送人：Signal 协议转发出去的是一条新消息，本来就不带原发送人，所以「隐藏发送人」恒成立，
 *   不需要开关，也不改协议。
 * - 说明文字：只对带附件的消息生效（图片、视频、文件下面的文字，含 [MultiShareArgs.draftText]
 *   与每张 [Media.caption]）。纯文字消息的文字就是内容本身，不能隐藏。
 *   只有空白的说明文字当作没有说明文字。
 * - 转发时用户另外写的附言不受影响，照常单独发出。
 */
object TellomiForwardCaptionPolicy {

  /** 原发送人永远不会随转发发出。 */
  const val SENDER_ALWAYS_HIDDEN = true

  /** 这条消息有没有附件（决定说明文字算不算「说明」而不是正文）。 */
  fun hasAttachments(args: MultiShareArgs): Boolean {
    if (args.media.isNotEmpty()) return true
    if (args.stickerLocator != null) return true
    if (args.sharedContacts.isNotEmpty()) return true
    val uri = args.dataUri
    return uri != null && uri != Uri.EMPTY
  }

  /** 这条消息有没有可以隐藏的说明文字（决定界面上要不要给「隐藏说明文字」开关）。 */
  fun canHideCaption(args: MultiShareArgs): Boolean {
    if (!hasAttachments(args)) return false
    val draft = args.draftText?.trim().orEmpty()
    if (draft.isNotEmpty()) return true
    return args.media.any { !it.caption.isNullOrBlank() }
  }

  /** 实际随转发发出的一条 [MultiShareArgs]；开着隐藏且有可隐藏说明时去掉说明文字。 */
  fun outgoingArgs(args: MultiShareArgs, hideCaption: Boolean): MultiShareArgs {
    if (!hideCaption || !canHideCaption(args)) return args
    val clearedMedia = args.media.map { media -> clearCaption(media) }
    return args.buildUpon()
      .withDraftText(null)
      .withBodyRanges(null)
      .withMentions(emptyList())
      .withMedia(clearedMedia)
      .build()
  }

  /** 多条一起转发时，对每一条按开关处理。 */
  fun outgoingArgs(args: List<MultiShareArgs>, hideCaption: Boolean): List<MultiShareArgs> {
    return args.map { outgoingArgs(it, hideCaption) }
  }

  /** 选了多条消息时，只要有一条能隐藏说明文字就显示开关。 */
  fun showsHideCaptionToggle(args: List<MultiShareArgs>): Boolean {
    return args.any { canHideCaption(it) }
  }

  private fun clearCaption(media: Media): Media {
    return if (media.caption.isNullOrBlank()) media else media.copy(caption = null)
  }
}
