/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.logging.Log
import org.signal.core.util.orNull
import org.signal.mediasend.MediaSendFlowActivityContract
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideFactory
import org.thoughtcrime.securesms.util.MediaUtil

/**
 * Tellomi（tellomi/tellomi#1121）：附件 Sheet「文件」页选好的文件 → 要发的附件，每个一条（都按「文件」发，不压缩、不转码，
 * 同附件键盘的「文件」）。
 *
 * 图片、视频要带上 ADR-0068 的 asFile（[DocumentSlide.tellomiAsFile]）：不带的话 contentType 是 image / video，
 * 上传前照样被压缩，两边都画成普通照片（tellomi/tellomi#1263 之前就是这样）。
 *
 * - 最近发过的（F-7、F-8）：从本机那一行附件读（落库时按内容哈希认出是同一份，不会多存一份；刚传过的还复用 CDN）。
 *   本机已经没有文件的算发不了。
 * - 系统选择器挑的（F-4）、「从相册中选择」勾的（F-3，相册里的原文件）：文件名、大小从系统拿；超过上限的不发，
 *   名字交给调用方提示「文件太大」。
 */
object TellomiAttachmentFiles {

  private val TAG = Log.tag(TellomiAttachmentFiles::class.java)

  data class Prepared(
    /** 按顺序：先最近发过的（勾选顺序），再系统选择器挑的，再相册里勾的（勾选顺序）。 */
    val slides: List<Slide>,
    /** 超过上限、没发的文件名。 */
    val tooLarge: List<String>,
    /** 读不到的（本机没有了、选择器给的读不出来）。 */
    val unavailable: Int
  )

  @WorkerThread
  fun prepare(context: Context, result: MediaSendFlowActivityContract.AttachmentFilesResult, maxFileSize: Long): Prepared {
    val slides = ArrayList<Slide>()
    val tooLarge = ArrayList<String>()
    var unavailable = 0

    for (id in result.recentAttachmentIds) {
      val attachment = SignalDatabase.attachments.getAttachment(AttachmentId(id))
      if (attachment?.uri == null) {
        Log.w(TAG, "Recent file $id is no longer on this device")
        unavailable++
        continue
      }
      val uri = PartAuthority.getAttachmentDataUri(attachment.attachmentId)
      val contentType = attachment.contentType ?: MediaUtil.OCTET
      slides += if (MediaUtil.isImageOrVideoType(contentType)) {
        DocumentSlide.tellomiAsFile(context, uri, contentType, attachment.size, attachment.width, attachment.height, attachment.fileName)
      } else {
        DocumentSlide(context, uri, contentType, attachment.size, attachment.fileName)
      }
    }

    for (uri in result.pickedUris + result.galleryUris) {
      val slide = SlideFactory.getSlide(context, MediaUtil.UNKNOWN, uri, 0, 0, null)
      if (slide == null) {
        Log.w(TAG, "Couldn't read a picked file")
        unavailable++
        continue
      }
      if (slide.fileSize > maxFileSize) {
        tooLarge += slide.fileName.orNull() ?: uri.lastPathSegment.orEmpty()
        continue
      }
      slides += if (MediaUtil.isImageOrVideoType(slide.contentType)) {
        val (width, height) = MediaUtil.getDimensions(context, slide.contentType, uri)
        DocumentSlide.tellomiAsFile(context, slide.uri ?: uri, slide.contentType, slide.fileSize, width, height, slide.fileName.orNull())
      } else {
        slide
      }
    }

    return Prepared(slides, tooLarge, unavailable)
  }
}
