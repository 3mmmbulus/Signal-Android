/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.logging.Log
import org.signal.core.util.optionalLong
import org.signal.core.util.optionalString
import org.signal.core.util.orNull
import org.signal.mediasend.TellomiHeic
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tellomi（tellomi/tellomi#1263 P-5「以文件发送」，ADR-0068）：选图面板里选好的照片 / 视频 → 按文件发的那几个文件，顺序不变。
 * 每个都带上 [TransformProperties.forTellomiAsFile]：上传不压缩、不转码，对方画成文件行（带缩略图）。
 *
 * - 没编辑过的：就是相册里那个文件，带原文件名（spec P-5「原图、原文件名、不压缩」）。
 * - 编辑过的照片（涂鸦、裁剪、模糊……）：发编辑后的图（同 Telegram 安卓 `ChatActivity` 发 `photoEntry.imagePath`），
 *   不然打了码的地方会原样发出去；文件名沿用原图、后缀改成 .jpg。
 * - 视频的剪辑、静音不带（同 Telegram 安卓 `SendMessagesHelper.prepareSendingMedia`：forceDocument 时丢掉 videoEditedInfo）：
 *   剪辑要转码，就不是原文件了。
 * - 选了「转成 JPEG」：没编辑过的 HEIC 解码后存成 JPEG，后缀改成 .jpg（电脑端不认 HEIC）；解不了的原样发。
 * - 不知道文件名的（相机刚拍的）：按拍摄时间起一个，IMG_年月日_时分秒.jpg / VID_….mp4。
 */
object TellomiMediaAsFiles {

  private val TAG = Log.tag(TellomiMediaAsFiles::class.java)

  private const val JPEG_QUALITY = 90

  /**
   * @param transformed 发送前处理过的样子（[MediaSelectionRepository] 里 `transformMediaSync` 的结果），键是 [selected] 里的原样。
   */
  @WorkerThread
  fun prepare(context: Context, selected: List<Media>, transformed: Map<Media, Media>, convertHeicToJpeg: Boolean): List<Media> {
    return selected.map { media ->
      val edited: Media? = transformed[media]?.takeIf { it.uri != media.uri }
      val (displayName, querySize) = readFileInfo(context, media.uri)
      val name = displayName ?: media.fileName ?: generatedName(media)

      when {
        edited != null -> asFile(edited, fileName = withExtension(name, "jpg"))

        convertHeicToJpeg && TellomiHeic.isHeic(media.contentType) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
          heicToJpeg(context, media, fileName = withExtension(name, "jpg")) ?: asFile(media, fileName = name, size = querySize)
        }

        else -> asFile(media, fileName = name, size = querySize)
      }
    }
  }

  private fun asFile(media: Media, fileName: String, size: Long? = null): Media {
    return media.copy(size = size ?: media.size, transformProperties = TransformProperties.forTellomiAsFile(), fileName = fileName)
  }

  /** 相册里的文件名、大小（大小以文件本身为准，相册库里记的可能是旧的）；本机的（相机刚拍的、编辑出来的）用它自己记的。 */
  private fun readFileInfo(context: Context, uri: Uri): Pair<String?, Long?> {
    if (PartAuthority.isLocalUri(uri)) {
      return PartAuthority.getAttachmentFileName(context, uri) to PartAuthority.getAttachmentSize(context, uri)
    }

    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          cursor.optionalString(OpenableColumns.DISPLAY_NAME).orNull() to cursor.optionalLong(OpenableColumns.SIZE).orNull()
        } else {
          null
        }
      } ?: (null to null)
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the file name", e)
      null to null
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun heicToJpeg(context: Context, media: Media, fileName: String): Media? {
    return try {
      val bytes = PartAuthority.getAttachmentStream(context, media.uri).use { it.readBytes() }
      // ImageDecoder 会按 EXIF 转正方向。整张解出来（不缩小），转出来的就是同样大小的 JPEG。
      val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
      val width = bitmap.width
      val height = bitmap.height
      val jpeg = ByteArrayOutputStream()
      try {
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
      } finally {
        bitmap.recycle()
      }
      val uri = AppDependencies.blobs
        .forData(jpeg.toByteArray())
        .withMimeType(MediaUtil.IMAGE_JPEG)
        .withFileName(fileName)
        .createForSingleSessionOnDisk(context)
      media.copy(
        uri = uri,
        contentType = MediaUtil.IMAGE_JPEG,
        size = jpeg.size().toLong(),
        width = width,
        height = height,
        transformProperties = TransformProperties.forTellomiAsFile(),
        fileName = fileName
      )
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't convert a HEIC to JPEG, sending it as it is", e)
      null
    } catch (e: OutOfMemoryError) {
      Log.w(TAG, "Out of memory converting a HEIC to JPEG, sending it as it is", e)
      null
    }
  }

  /** 换后缀：IMG_0001.HEIC → IMG_0001.jpg；没有后缀的直接加上。 */
  @VisibleForTesting
  fun withExtension(fileName: String, extension: String): String {
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    return "$base.$extension"
  }

  /** 相机刚拍的没有文件名：IMG_20261013_153000.jpg / VID_20261013_153000.mp4（按拍摄时间，同相机的起名习惯）。 */
  @VisibleForTesting
  fun generatedName(media: Media): String {
    val prefix = if (MediaUtil.isVideoType(media.contentType)) "VID" else "IMG"
    val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(if (media.date > 0) media.date else System.currentTimeMillis()))
    val extension = extensionFor(media.contentType)
    return if (extension.isNullOrEmpty()) "${prefix}_$time" else "${prefix}_$time.$extension"
  }

  private fun extensionFor(contentType: String?): String? {
    return when {
      contentType.isNullOrEmpty() -> null
      contentType == MediaUtil.IMAGE_JPEG -> "jpg"
      else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: contentType.substringAfter('/', "").ifEmpty { null }
    }
  }
}
