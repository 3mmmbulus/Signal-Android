/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v2

import android.net.Uri
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi（tellomi/tellomi#1263 spec P-5，ADR-0068 §4.4）：选图「···」→「罩住图片」罩上的那几张，带进要发的附件。
 * 标记是附件的 [TransformProperties.tellomiSpoiler]（同收到的遮罩），发送时写进 `AttachmentPointer.tellomi.spoiler`
 * （`PushSendJob`），自己这边也罩着（同 Telegram 两端）。只有图片 / 视频罩（§4.1）。
 */
object TellomiMediaSpoilers {

  /** 罩住的原 URI → 发出去的那一份的 URI（编辑过的、压过画质的换成了渲染后的新 URI）。 */
  fun renderedUris(selected: List<Media>, oldToNew: Map<Media, Media>, spoileredUris: Set<Uri>): Set<Uri> {
    if (spoileredUris.isEmpty()) {
      return emptySet()
    }
    return selected
      .filter { it.uri in spoileredUris && MediaUtil.isImageOrVideoType(it.contentType) }
      .mapTo(mutableSetOf()) { (oldToNew[it] ?: it).uri }
  }

  /** 没预上传的（定时、直接交给会话页的）：附件跟着 [Media.transformProperties] 落库。 */
  fun mark(media: Media, spoileredUris: Set<Uri>): Media {
    if (media.uri !in spoileredUris) {
      return media
    }
    val properties = media.transformProperties ?: TransformProperties.empty()
    return media.copy(transformProperties = properties.copy(tellomiSpoiler = true, tellomiSpoilerRevealed = false))
  }

  /** 已经预上传的：附件早就落库了，补上标记。 */
  fun markPreUploaded(results: Collection<MessageSender.PreUploadResult>, spoileredUris: Set<Uri>) {
    if (spoileredUris.isEmpty()) {
      return
    }
    results
      .filter { it.media.uri in spoileredUris }
      .forEach { SignalDatabase.attachments.setTellomiSpoiler(it.attachmentId) }
  }
}
