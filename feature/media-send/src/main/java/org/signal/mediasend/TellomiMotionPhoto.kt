/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.util.LruCache
import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import kotlinx.parcelize.Parcelize
import org.signal.core.models.media.Media
import org.signal.core.util.logging.Log

/**
 * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：安卓的「实况照片」= 动态照片（Motion Photo）——一张 JPEG / HEIC 后面
 * 接一段 MP4，位置写在 XMP 里。认两种写法（同 Telegram 安卓 `MediaController.PhotoEntry.isLivePhoto()` 用 media3
 * `XmpMotionPhotoDescriptionParser` 认的那两种，只取机制，代码是自己写的）：
 *
 * - 新写法（Motion Photo v1）：`GCamera:MotionPhoto="1"`（或 `Camera:MotionPhoto`），`Container:Directory` 里
 *   `Item:Semantic="MotionPhoto"` 的 `Item:Length` 就是文件末尾那段视频的长度（它后面没有别的项）。
 * - 旧写法（MVIMG）：`GCamera:MicroVideo="1"`，`GCamera:MicroVideoOffset` = 视频从文件末尾往前多少字节。
 *
 * 视频在文件里的起点 = 文件长度 − 视频长度。不认得的、长度不对的一律当普通照片。
 */
object TellomiMotionPhoto {

  private val TAG = Log.tag(TellomiMotionPhoto::class)

  /** 视频那一段在文件里的位置。 */
  @Parcelize
  data class VideoRange(val offset: Long, val length: Long) : Parcelable

  /** 只有这几种照片可能是动态照片。 */
  fun canBeMotionPhoto(contentType: String?): Boolean {
    return when (contentType?.lowercase()) {
      "image/jpeg", "image/jpg", "image/heic", "image/heif" -> true
      else -> false
    }
  }

  /** 从 XMP 里读出视频那一段的长度（字节）；不是动态照片就是 null。 */
  fun videoLength(xmp: String?): Long? {
    if (xmp.isNullOrEmpty()) {
      return null
    }
    if (value(xmp, "GCamera:MotionPhoto") == "1" || value(xmp, "Camera:MotionPhoto") == "1") {
      containerVideoLength(xmp)?.let { return it }
    }
    if (value(xmp, "GCamera:MicroVideo") == "1") {
      return value(xmp, "GCamera:MicroVideoOffset")?.toLongOrNull()?.takeIf { it > 0 }
    }
    return null
  }

  /** 文件长 [fileLength] 时视频的位置；长度不合理（不比文件短）就不是。 */
  fun videoRange(xmp: String?, fileLength: Long): VideoRange? {
    val length = videoLength(xmp) ?: return null
    if (fileLength <= 0 || length <= 0 || length >= fileLength) {
      return null
    }
    return VideoRange(offset = fileLength - length, length = length)
  }

  private val cache = LruCache<Uri, Result>(512)

  private class Result(val range: VideoRange?)

  /** 这一张是不是动态照片（读文件开头的 XMP，几毫秒；按 URI 记住）。读不了的当普通照片。 */
  @WorkerThread
  fun detect(context: Context, media: Media): VideoRange? {
    if (!canBeMotionPhoto(media.contentType)) {
      return null
    }
    cache.get(media.uri)?.let { return it.range }
    val range = try {
      context.contentResolver.openFileDescriptor(media.uri, "r")?.use { descriptor ->
        val xmp = ExifInterface(descriptor.fileDescriptor).getAttribute(ExifInterface.TAG_XMP)
        videoRange(xmp, descriptor.statSize)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the XMP of ${media.uri}", e)
      null
    }
    cache.put(media.uri, Result(range))
    return range
  }

  /** `Container:Directory` 里 `Semantic="MotionPhoto"` 那一项的 `Length`（项既可能写成属性，也可能写成子元素）。 */
  private fun containerVideoLength(xmp: String): Long? {
    val items = Regex("<Container:Item\\b(.*?)(/>|</Container:Item>)", RegexOption.DOT_MATCHES_ALL).findAll(xmp)
    for (item in items) {
      val body = item.groupValues[1]
      if (value(body, "Item:Semantic") == "MotionPhoto") {
        return value(body, "Item:Length")?.toLongOrNull()?.takeIf { it > 0 }
      }
    }
    return null
  }

  /** 属性写法 `Name="v"` 或元素写法 `<Name>v</Name>`（名字前面不能紧挨着别的字母，免得 `Camera:` 认成 `GCamera:` 的一部分）。 */
  private fun value(text: String, name: String): String? {
    val escaped = Regex.escape(name)
    Regex("(?<![A-Za-z])$escaped\\s*=\\s*\"([^\"]*)\"").find(text)?.let { return it.groupValues[1].trim() }
    Regex("<$escaped>\\s*([^<]*?)\\s*</$escaped>").find(text)?.let { return it.groupValues[1].trim() }
    return null
  }
}
