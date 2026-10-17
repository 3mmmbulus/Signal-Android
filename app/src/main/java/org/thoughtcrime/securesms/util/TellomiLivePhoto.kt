/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.Slide
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream.IntegrityCheck
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.1，spec R-2）：收到的实况照片的动态部分。
 *
 * 静态图照常是一个附件；动态部分的 `AttachmentPointer` 跟着静态图存在 `TransformProperties.tellomiLivePhotoVideo` 里，
 * 不另建附件行（不进相册、媒体页、备份、转发的那些查询）。第一次要播时下载、解密，放进这次运行的加密临时存储
 * （`BlobProvider` 单次会话，下次启动清掉），这次运行里再播就用它；下载不了就只显示静态图。
 */
object TellomiLivePhoto {

  private val TAG = Log.tag(TellomiLivePhoto::class)

  /** 动态部分最多这么大（Telegram 的参数下几秒的视频远小于它；超了不下）。 */
  private const val MAX_VIDEO_BYTES = 30L * 1024 * 1024

  private val downloaded: MutableMap<AttachmentId, Uri> = ConcurrentHashMap()

  @JvmStatic
  fun isLivePhoto(attachment: Attachment?): Boolean = attachment?.transformProperties?.tellomiLivePhotoVideo != null

  @JvmStatic
  fun isLivePhoto(slide: Slide?): Boolean = slide != null && isLivePhoto(slide.asAttachment())

  /** 这次运行里已经下好的动态部分（没有就是 null，不会去下）。 */
  @JvmStatic
  fun cachedVideo(attachment: Attachment?): Uri? = (attachment as? DatabaseAttachment)?.let { downloaded[it.attachmentId] }

  /** 动态部分的指针（解析不了就是 null）。 */
  @JvmStatic
  fun videoPointer(attachment: Attachment?): SignalServiceAttachmentPointer? {
    val encoded = attachment?.transformProperties?.tellomiLivePhotoVideo ?: return null
    return try {
      AttachmentPointerUtil.createSignalAttachmentPointer(Base64.decode(encoded))
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the live photo video pointer", e)
      null
    }
  }

  /** 动态部分的指针原样的字节（发送时写进 `AttachmentPointer.tellomi.livePhotoVideo`）；没有、解不开就是 null。按文件发的没有。 */
  @JvmStatic
  fun videoPointerBytes(attachment: Attachment?): ByteArray? {
    val properties = attachment?.transformProperties ?: return null
    if (properties.tellomiAsFile) {
      return null
    }
    val encoded = properties.tellomiLivePhotoVideo ?: return null
    return try {
      Base64.decode(encoded)
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the live photo video pointer", e)
      null
    }
  }

  /** 转发时上传还能复用多久（同附件自己的 `AttachmentUploadJob.UPLOAD_REUSE_THRESHOLD`，3 天）。 */
  val FORWARD_REUSE_MILLIS: Long = TimeUnit.DAYS.toMillis(3)

  /**
   * Tellomi（#1263 P-4，ADR-0068 §4.4）：转发时带不带动态部分：上传还在 [FORWARD_REUSE_MILLIS] 之内就原样带上（同一个 CDN 对象），
   * 更早的、读不出上传时间的只转静态图（本机没有视频可以重传）。
   */
  @JvmStatic
  fun forwardablePointer(attachment: Attachment?, now: Long = System.currentTimeMillis()): String? {
    val encoded = attachment?.transformProperties?.tellomiLivePhotoVideo ?: return null
    val uploaded = videoPointer(attachment)?.uploadTimestamp ?: return null
    return encoded.takeIf { uploaded > 0 && now - uploaded in 0 until FORWARD_REUSE_MILLIS }
  }

  /**
   * Tellomi（#1263 P-4）：自己刚发出去的实况照片：这次运行里播本机转好的那份（拷进加密的临时存储），不用再从 CDN 下回来。
   */
  @JvmStatic
  @WorkerThread
  fun rememberLocal(context: Context, attachmentId: AttachmentId, video: File) {
    try {
      val uri = FileInputStream(video).use { input ->
        AppDependencies.blobs
          .forData(input, video.length())
          .withMimeType(MediaUtil.VIDEO_MP4)
          .createForSingleSessionOnDisk(context)
      }
      downloaded[attachmentId] = uri
    } catch (e: Exception) {
      Log.w(TAG, "[$attachmentId] Couldn't keep the sent live photo video for playback", e)
    }
  }

  /** 下载、解密动态部分，返回能播的 uri；已经下过就直接给。失败返回 null（只显示静态图）。 */
  @JvmStatic
  @WorkerThread
  fun fetchVideo(context: Context, attachment: DatabaseAttachment): Uri? {
    downloaded[attachment.attachmentId]?.let { return it }

    val pointer = videoPointer(attachment) ?: return null
    val size = pointer.size.orElse(0).toLong()
    val digest = pointer.digest.orElse(null)
    if (size <= 0 || size > MAX_VIDEO_BYTES || digest == null) {
      Log.w(TAG, "[${attachment.attachmentId}] Live photo video has a bad size ($size) or no digest")
      return null
    }

    val transferFile = AppDependencies.blobs.forNonAutoEncryptingSingleSessionOnDisk(context)
    return try {
      val downloadLimit = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(size))
      AppDependencies.signalServiceMessageReceiver
        .retrieveAttachment(pointer, transferFile, downloadLimit, IntegrityCheck.forEncryptedDigest(digest))
        .use { input ->
          AppDependencies.blobs
            .forData(input, size)
            .withMimeType(pointer.contentType ?: MediaUtil.VIDEO_MP4)
            .createForSingleSessionOnDisk(context)
        }
        .also { downloaded[attachment.attachmentId] = it }
    } catch (e: Exception) {
      Log.w(TAG, "[${attachment.attachmentId}] Couldn't download the live photo video", e)
      null
    } finally {
      transferFile.delete()
    }
  }
}
