/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.media.MediaMetadataRetriever
import android.net.Uri
import org.signal.core.models.database.AttachmentId
import org.signal.core.util.Base64
import org.signal.core.util.Util
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.net.RetryLaterException
import org.signal.mediasend.TellomiMotionPhoto
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.thoughtcrime.securesms.attachments.AttachmentUploadUtil
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.net.SignalNetwork
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.TellomiLivePhoto
import org.thoughtcrime.securesms.video.StreamingTranscoder
import org.thoughtcrime.securesms.video.TranscoderOptions
import org.thoughtcrime.securesms.video.TranscodingConfig
import org.thoughtcrime.securesms.video.postprocessing.Mp4FaststartPostProcessor
import org.thoughtcrime.securesms.video.videoconverter.mediadatasource.InputStreamMediaDataSource
import org.thoughtcrime.securesms.video.videoconverter.utils.VideoConstants
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Tellomi（tellomi/tellomi#1263 spec P-4，ADR-0068 §4.4）：发一张实况照片（安卓 = 动态照片）时的第二次上传——照片里的那段视频。
 *
 * 从原文件里截出视频那一段，转成 H.264（长边 ≤ 848、约 1.6 Mbps、AAC 64 kbps、留声音、不截短，照 Telegram iOS
 * `TGMediaVideoConverter` 的这组参数，只取机制），加密上传，把指针记在静态图附件上（`TransformProperties.tellomiLivePhotoVideo`，
 * 和收到的实况照片同一个地方）。消息的发送任务排在它后面（预上传结果的任务列表里有它），发出去的静态图带着
 * `AttachmentPointer.tellomi.livePhotoVideo`。
 *
 * 转不出来、原文件没了、服务器一直拒收：只发静态图——这个任务照样算成功，不拖住、不弄坏消息。没网时转码照做，上传等有网再试。
 */
class TellomiLivePhotoUploadJob private constructor(
  parameters: Parameters,
  private val attachmentId: AttachmentId,
  private val source: Uri,
  private val videoOffset: Long,
  private val videoLength: Long
) : BaseJob(parameters) {

  companion object {
    const val KEY = "TellomiLivePhotoUploadJob"

    private val TAG = Log.tag(TellomiLivePhotoUploadJob::class.java)

    private const val KEY_ATTACHMENT_ID = "attachment_id"
    private const val KEY_SOURCE = "source"
    private const val KEY_OFFSET = "offset"
    private const val KEY_LENGTH = "length"

    const val MAX_LONG_EDGE = 848
    const val VIDEO_BITRATE = 1_600_000
    const val AUDIO_BITRATE = 64_000

    /** 动态照片里的视频只有几秒；比这还大的不当它是。 */
    const val MAX_SOURCE_BYTES = 100L * 1024 * 1024

    /** 服务器回错误码（不是没网）时最多试这么多次，之后只发静态图。 */
    private const val MAX_SERVER_ERROR_ATTEMPTS = 5

    /** 转码器按短边定大小：短边取多少，长边才不超过 848（本来就小的不放大）。 */
    @JvmStatic
    fun shortEdgeFor(width: Int, height: Int): Int {
      val shortSide = min(width, height)
      val longSide = max(width, height)
      if (shortSide <= 0) {
        return 0
      }
      return if (longSide <= MAX_LONG_EDGE) shortSide else (MAX_LONG_EDGE.toLong() * shortSide / longSide).toInt()
    }

    /** 转码目标：一档，固定码率（[VideoConstants] 按 1000 进位）。 */
    @JvmStatic
    fun qualityTier(shortEdge: Int): TranscodingConfig.QualityTier {
      return TranscodingConfig.QualityTier(
        resolution = shortEdge,
        videoBitrateMbps = VIDEO_BITRATE / VideoConstants.MB.toFloat(),
        audioBitrateKbps = AUDIO_BITRATE / VideoConstants.KB,
        maxDurationSec = Int.MAX_VALUE
      )
    }
  }

  constructor(attachmentId: AttachmentId, source: Uri, range: TellomiMotionPhoto.VideoRange) : this(
    Parameters.Builder()
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .build(),
    attachmentId,
    source,
    range.offset,
    range.length
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(KEY_ATTACHMENT_ID, attachmentId.id)
      .putString(KEY_SOURCE, source.toString())
      .putLong(KEY_OFFSET, videoOffset)
      .putLong(KEY_LENGTH, videoLength)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  override fun onRun() {
    val attachment = SignalDatabase.attachments.getAttachment(attachmentId)
    if (attachment == null) {
      Log.w(TAG, "[$attachmentId] The still is gone. Nothing to do.")
      cleanUp()
      return
    }
    if (TellomiLivePhoto.isLivePhoto(attachment)) {
      Log.i(TAG, "[$attachmentId] Already has its moving half.")
      cleanUp()
      return
    }
    if (!MediaUtil.isImageType(attachment.contentType) || attachment.transformProperties?.tellomiAsFile == true) {
      Log.i(TAG, "[$attachmentId] Not a still image sent as media. Sending the still only.")
      cleanUp()
      return
    }

    val video = transcodedVideo() ?: return

    val pointer = try {
      upload(video)
    } catch (e: NonSuccessfulResponseCodeException) {
      if (runAttempt + 1 >= MAX_SERVER_ERROR_ATTEMPTS) {
        Log.w(TAG, "[$attachmentId] The server keeps refusing the upload (${e.code}). Sending the still only.", e)
        cleanUp()
        return
      }
      throw e
    }

    if (SignalDatabase.attachments.setTellomiLivePhotoVideo(attachmentId, Base64.encodeWithPadding(pointer.encode()))) {
      Log.i(TAG, "[$attachmentId] Uploaded the moving half (${video.length()} bytes).")
      TellomiLivePhoto.rememberLocal(context, attachmentId, video)
    }
    cleanUp()
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return e is IOException
  }

  override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
    if (exception is RetryLaterException && exception.duration != null) {
      return exception.duration.toMillis()
    }
    return super.getNextRunAttemptBackoff(pastAttemptCount, exception)
  }

  override fun onFailure() {
    cleanUp()
  }

  private val workDirectory: File
    get() = File(context.cacheDir, "tellomi-live").apply { mkdirs() }

  /** 转好的视频（重试时用上一次转好的）；转不出来就是 null，只发静态图。 */
  private fun transcodedVideo(): File? {
    val output = File(workDirectory, "${attachmentId.id}.mp4")
    if (output.length() > 0) {
      return output
    }
    if (videoLength <= 0 || videoLength > MAX_SOURCE_BYTES) {
      Log.w(TAG, "[$attachmentId] The video in the photo has an odd length ($videoLength). Sending the still only.")
      return null
    }

    val extracted = File(workDirectory, "${attachmentId.id}.source")
    val partial = File(workDirectory, "${attachmentId.id}.partial")
    val ready = File(workDirectory, "${attachmentId.id}.ready")
    return try {
      if (!extractVideo(extracted)) {
        Log.w(TAG, "[$attachmentId] Couldn't read the video out of the photo. Sending the still only.")
        return null
      }

      val (width, height) = videoSize(extracted)
      val shortEdge = shortEdgeFor(width, height)
      if (shortEdge <= 0) {
        Log.w(TAG, "[$attachmentId] The video in the photo has no size. Sending the still only.")
        return null
      }

      val mdatLength = FileMediaDataSource(extracted).use { dataSource ->
        // Options with no end time force the transcode without trimming (as AttachmentCompressionJob does for a picked level).
        val transcoder = StreamingTranscoder(dataSource, TranscoderOptions(0, 0), listOf(qualityTier(shortEdge)), AttachmentUploadJob.maxPlaintextSize, false, false)
        FileOutputStream(partial).use { outputStream ->
          transcoder.transcode({ }, outputStream, { isCanceled })
        }
      }
      // The streaming muxer leaves the mdat size for afterwards; the faststart pass writes it and puts moov first, like
      // AttachmentCompressionJob does for every transcoded video (players other than ours can't read it otherwise).
      FileOutputStream(ready).use { outputStream ->
        Mp4FaststartPostProcessor { FileInputStream(partial) }
          .processWithMdatLength(partial.length(), Util.toIntExact(mdatLength))
          .use { it.copyTo(outputStream) }
      }
      if (!ready.renameTo(output)) {
        throw IOException("Couldn't move the transcoded video into place")
      }
      Log.i(TAG, "[$attachmentId] Transcoded the moving half: ${width}x$height, ${extracted.length()} -> ${output.length()} bytes.")
      output
    } catch (e: Exception) {
      Log.w(TAG, "[$attachmentId] Couldn't transcode the moving half. Sending the still only.", e)
      null
    } finally {
      extracted.delete()
      partial.delete()
      ready.delete()
    }
  }

  /** 原文件里 [videoOffset] 起的 [videoLength] 字节（照片后面接的那段 MP4）。 */
  private fun extractVideo(destination: File): Boolean {
    val descriptor = context.contentResolver.openFileDescriptor(source, "r") ?: return false
    descriptor.use {
      if (descriptor.statSize > 0 && videoOffset + videoLength > descriptor.statSize) {
        return false
      }
      FileInputStream(descriptor.fileDescriptor).use { input ->
        input.channel.position(videoOffset)
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(64 * 1024)
          var remaining = videoLength
          while (remaining > 0) {
            val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
            if (read < 0) {
              return false
            }
            output.write(buffer, 0, read)
            remaining -= read
          }
        }
      }
    }
    return destination.length() == videoLength
  }

  private fun videoSize(file: File): Pair<Int, Int> {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      width to height
    } finally {
      retriever.release()
    }
  }

  /** 加密上传，返回视频的指针（`video/mp4`，同附件上传的流程，不带进度条）。 */
  private fun upload(video: File): AttachmentPointer {
    val size = video.length()
    val (width, height) = videoSize(video)
    val key = Util.getSecretBytes(64)
    val iv = Util.getSecretBytes(16)
    val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(size))

    val form = when (val result = SignalNetwork.attachmentApi.getAttachmentV4UploadForm(ciphertextLength)) {
      is RequestResult.Success -> result.result
      is RequestResult.NonSuccess -> throw result.error
      is RequestResult.RetryableNetworkError -> throw RetryLaterException(result.retryAfter ?: 1.minutes.toJavaDuration())
      is RequestResult.ApplicationError -> throw result.cause
    }

    val checksum = FileInputStream(video).use { AttachmentUploadUtil.computeCiphertextChecksum(key, iv, it, size) }
    val stream = SignalServiceAttachment.newStreamBuilder()
      .withStream(FileInputStream(video))
      .withContentType(MediaUtil.VIDEO_MP4)
      .withLength(size)
      .withWidth(width)
      .withHeight(height)
      .withUploadTimestamp(System.currentTimeMillis())
      .withCancelationSignal { isCanceled }
      .build()

    val result = stream.use {
      SignalNetwork.attachmentApi.uploadAttachmentV4(form = form, key = key, iv = iv, checksumSha256 = checksum, attachmentStream = it).successOrThrow()
    }

    return AttachmentPointerUtil.createAttachmentPointer(
      SignalServiceAttachmentPointer(
        cdnNumber = result.cdnNumber,
        remoteId = result.remoteId,
        contentType = MediaUtil.VIDEO_MP4,
        key = result.key,
        size = Optional.of(Util.toIntExact(result.dataSize)),
        preview = Optional.empty(),
        width = width,
        height = height,
        digest = Optional.of(result.digest),
        incrementalDigest = Optional.ofNullable(result.incrementalDigest),
        incrementalMacChunkSize = result.incrementalDigestChunkSize,
        fileName = Optional.empty(),
        voiceNote = false,
        isBorderless = false,
        isGif = false,
        caption = Optional.empty(),
        blurHash = Optional.empty(),
        uploadTimestamp = result.uploadTimestamp,
        uuid = null
      )
    )
  }

  private fun cleanUp() {
    File(workDirectory, "${attachmentId.id}.mp4").delete()
  }

  /**
   * 转码器读本机临时文件用的。每次读都新开一个流（同上游的 [InputStreamMediaDataSource]）：转码器读完元数据就把数据源关掉
   * （`MediaMetadataRetriever.release`），之后解码还要接着读。
   */
  private class FileMediaDataSource(private val file: File) : InputStreamMediaDataSource() {
    override fun close() = Unit

    override fun getSize(): Long = file.length()

    override fun createInputStream(position: Long): InputStream {
      return FileInputStream(file).also { it.channel.position(position) }
    }
  }

  class Factory : Job.Factory<TellomiLivePhotoUploadJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): TellomiLivePhotoUploadJob {
      val data = JsonJobData.deserialize(serializedData)
      return TellomiLivePhotoUploadJob(
        parameters,
        AttachmentId(data.getLong(KEY_ATTACHMENT_ID)),
        Uri.parse(data.getString(KEY_SOURCE)),
        data.getLong(KEY_OFFSET),
        data.getLong(KEY_LENGTH)
      )
    }
  }
}
