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
 * 一块 [chunkSize] 字节；断线、偏移对不上（409 `offset_mismatch`）或这一块得到 500 / 502 / 503 / 504 时，
 * 用 HEAD 问服务端收到了多少，从那里接着传，最多接 [maxResumes] 次。
 * 服务端回的偏移没有往前走（卡住）也算一次中断，同样占 [maxResumes]，所以不会无限循环。
 * 引擎存储（本机盘 / R2）出错时 `PATCH` 回 500 `internal`，偏移不动，同一块可以重发；反代的 502 / 503 / 504 同理
 * （引擎对账记录 D-1，和 iOS `FeedImageUploader` 一致）。其余引擎答复（400、404、413、415、429……）直接抛。
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

    /**
     * HEAD 回的偏移超过文件长度，就当作已传完（和 iOS `min(offset, bytes.count)` 一样，不报错）；
     * 负数（坏头）当作 0，从头再传，不让 `copyOfRange` 抛 IllegalArgumentException。
     */
    internal fun clampOffset(serverOffset: Long, size: Int): Long = serverOffset.coerceIn(0L, size.toLong())

    /** 这一块得到这些状态码时，偏移没动，问一下偏移再重发是安全的。 */
    private val RESUMABLE_SERVER_ERRORS = setOf(500, 502, 503, 504)

    /** 能不能用「问偏移再接着传」处理：没拿到引擎答复的（断线、超时）、409 `offset_mismatch`、500 / 502 / 503 / 504。 */
    internal fun isResumable(e: IOException): Boolean {
      if (e !is FeedApiException) return true
      return (e.status == 409 && e.code == "offset_mismatch") || e.status in RESUMABLE_SERVER_ERRORS
    }

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

  /** 一块发出去了，服务端回的偏移却没有超过发送前的偏移。和 iOS `FeedImageUploader` 的 `StuckUpload` 一样，算一次可续传的中断。 */
  private class StuckUpload(offset: Long) : IOException("upload offset did not advance past $offset")

  suspend fun upload(bytes: ByteArray): String {
    val start = api.startUpload(bytes.size.toLong())
    var offset = 0L
    var resumes = 0
    while (offset < bytes.size) {
      val end = minOf(offset + chunkSize, bytes.size.toLong()).toInt()
      offset = try {
        val next = api.uploadChunk(start.id, offset, bytes.copyOfRange(offset.toInt(), end))
        // 服务端说收到了，偏移却没往前走：当作一次中断（算进 maxResumes），问一下它现在在哪，不会无限循环
        if (next <= offset) throw StuckUpload(offset)
        next
      } catch (e: IOException) {
        if (!isResumable(e) || ++resumes > maxResumes) throw e
        Log.w(TAG, "upload interrupted at $offset, asking the server where to resume", e)
        clampOffset(api.uploadOffset(start.id), bytes.size)
      }
    }
    return start.key
  }
}
