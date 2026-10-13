/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.util

import org.signal.archive.proto.FilePointer
import org.signal.archive.proto.TellomiAttachment
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.5）：附件的呈现方式进出备份（`MessageAttachment.tellomi`）。
 *
 * 导出：本机 `transform_properties` 里的按文件、遮罩、实况指针；遮罩「已揭示」是这台设备的状态（spec D10），不导出。
 * 导入：照收到消息时的规则（`AttachmentPointerUtil`、`PointerAttachment`）收下——引用缩略图不按文件、不带实况；遮罩只在图片 / 视频上，
 * 按文件优先；实况只在静态图上，嵌套的必须是视频、有传输层位置、密钥和摘要。不合规的忽略这一项，附件照常导入。
 */
object TellomiArchiveAttachments {

  private val TAG = Log.tag(TellomiArchiveAttachments::class)

  /** 导出：没有任何 Tellomi 标记就是 null。 */
  @JvmStatic
  fun toRemote(attachment: DatabaseAttachment): TellomiAttachment? {
    val properties = attachment.transformProperties ?: return null
    val livePhotoVideo = properties.tellomiLivePhotoVideo?.let { liveVideoToRemote(it) }
    if (!properties.tellomiAsFile && !properties.tellomiSpoiler && livePhotoVideo == null) {
      return null
    }
    return TellomiAttachment(
      asFile = properties.tellomiAsFile,
      spoiler = properties.tellomiSpoiler,
      livePhotoVideo = livePhotoVideo
    )
  }

  /** 导入：要写进 `transform_properties` 的那几项；什么都没有就是 null。 */
  @JvmStatic
  fun toLocal(tellomi: TellomiAttachment?, contentType: String?, quote: Boolean): TransformProperties? {
    if (tellomi == null) {
      return null
    }
    val type = contentType?.lowercase(Locale.ROOT) ?: ""
    val asFile = !quote && tellomi.asFile
    val spoiler = tellomi.spoiler && !tellomi.asFile && (type.startsWith("image/") || type.startsWith("video/"))
    val livePhotoVideo = if (quote || tellomi.asFile || !type.startsWith("image/")) {
      null
    } else {
      tellomi.livePhotoVideo?.let { liveVideoToLocal(it) }
    }
    return TransformProperties(
      tellomiAsFile = asFile,
      tellomiSpoiler = spoiler,
      tellomiLivePhotoVideo = livePhotoVideo
    ).tellomiOnly()
  }

  /** 本机存的是嵌套 `AttachmentPointer` 的字节（base64），备份里是一个只有传输层位置的 [FilePointer]（动态部分不落库，没有本地文件）。 */
  private fun liveVideoToRemote(encoded: String): FilePointer? {
    val pointer = try {
      AttachmentPointer.ADAPTER.decode(Base64.decode(encoded))
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the live photo video pointer", e)
      return null
    }
    val key = pointer.key ?: return null
    val digest = pointer.digest ?: return null
    val cdnKey = pointer.cdnKey ?: pointer.cdnId?.toString() ?: return null
    val incrementalMac = pointer.incrementalMac?.takeIf { it.size > 0 && (pointer.chunkSize ?: 0) > 0 }
    return FilePointer(
      contentType = pointer.contentType,
      incrementalMac = incrementalMac,
      incrementalMacChunkSize = pointer.chunkSize.takeIf { incrementalMac != null },
      fileName = pointer.fileName,
      width = pointer.width?.takeIf { it > 0 },
      height = pointer.height?.takeIf { it > 0 },
      locatorInfo = FilePointer.LocatorInfo(
        key = key,
        size = pointer.size ?: 0,
        transitCdnKey = cdnKey,
        transitCdnNumber = pointer.cdnNumber ?: 0,
        transitTierUploadTimestamp = pointer.uploadTimestamp?.takeIf { it > 0 }?.clampToValidBackupRange(),
        encryptedDigest = digest
      )
    )
  }

  private fun liveVideoToLocal(filePointer: FilePointer): String? {
    val locator = filePointer.locatorInfo ?: return null
    val contentType = filePointer.contentType
    if (contentType == null || !contentType.lowercase(Locale.ROOT).startsWith("video/")) {
      return null
    }
    val cdnKey = locator.transitCdnKey?.takeIf { it.isNotBlank() } ?: return null
    val digest = locator.encryptedDigest ?: return null
    if (locator.key.size == 0) {
      return null
    }
    val pointer = AttachmentPointer(
      cdnKey = cdnKey,
      cdnNumber = locator.transitCdnNumber ?: 0,
      contentType = contentType,
      key = locator.key,
      size = locator.size,
      digest = digest,
      uploadTimestamp = locator.transitTierUploadTimestamp,
      incrementalMac = filePointer.incrementalMac,
      chunkSize = filePointer.incrementalMacChunkSize,
      fileName = filePointer.fileName,
      width = filePointer.width,
      height = filePointer.height
    )
    return Base64.encodeWithPadding(pointer.encode())
  }
}
