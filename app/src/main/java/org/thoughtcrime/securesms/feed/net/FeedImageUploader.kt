/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.net

import android.content.Context
import android.net.Uri
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.ImageCompressionUtil
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.IOException

/**
 * 把一张图传到内容引擎（tus 1.0，引擎 `docs/api.md`「图片上传」），返回发帖用的 media key。
 *
 * 一块 [chunkSize] 字节；断线或偏移对不上时用 HEAD 问服务端收到了多少，从那里接着传，最多接 [maxResumes] 次。
 */
class FeedImageUploader(
  private val api: FeedApi,
  private val chunkSize: Int = CHUNK_SIZE,
  private val maxResumes: Int = MAX_RESUMES
) {

  companion object {
    private val TAG = Log.tag(FeedImageUploader::class)

    const val CHUNK_SIZE = 512 * 1024
    const val MAX_RESUMES = 3

    /** 长边上限和质量：够在手机上看清，远低于引擎的 20 MB / 12000 像素。 */
    const val MAX_DIMENSION = 2560
    const val JPEG_QUALITY = 85

    /**
     * 读出要上传的字节：一律重新编码成 JPEG。重编码顺带去掉 EXIF（包括拍摄位置，第三节第 22 条），
     * HEIC / HEIF 也在这里转成 JPEG（引擎只收 JPEG / PNG / WebP）。方向由 Glide 按 EXIF 转正。
     */
    fun prepare(context: Context, uri: Uri): ByteArray {
      return try {
        ImageCompressionUtil.compress(context, null, MediaUtil.IMAGE_JPEG, uri, MAX_DIMENSION, JPEG_QUALITY).data
      } catch (e: Exception) {
        throw IOException("could not read image", e)
      }
    }
  }

  suspend fun upload(bytes: ByteArray): String {
    val start = api.startUpload(bytes.size.toLong())
    var offset = 0L
    var resumes = 0
    while (offset < bytes.size) {
      val end = minOf(offset + chunkSize, bytes.size.toLong()).toInt()
      offset = try {
        api.uploadChunk(start.id, offset, bytes.copyOfRange(offset.toInt(), end))
      } catch (e: IOException) {
        val resumable = e !is FeedApiException || (e.status == 409 && e.code == "offset_mismatch")
        if (!resumable || ++resumes > maxResumes) throw e
        Log.w(TAG, "upload interrupted at $offset, asking the server where to resume", e)
        api.uploadOffset(start.id)
      }
    }
    return start.key
  }
}
