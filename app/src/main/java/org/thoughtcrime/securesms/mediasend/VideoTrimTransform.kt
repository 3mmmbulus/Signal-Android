package org.thoughtcrime.securesms.mediasend

import android.content.Context
import androidx.annotation.WorkerThread
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.video.VideoTrimData

class VideoTrimTransform(private val data: VideoTrimData) : MediaTransform {
  @WorkerThread
  override fun transform(context: Context, media: Media): Media {
    return Media(
      uri = media.uri,
      contentType = media.contentType,
      date = media.date,
      width = media.width,
      height = media.height,
      size = media.size,
      duration = media.duration,
      isBorderless = media.isBorderless,
      // Tellomi（tellomi/tellomi#1518 V-6，owner 2026-10-14 照 TG）：静音的视频当 GIF 发（自动播放、循环、无声）。
      isVideoGif = media.isVideoGif || data.isMuted,
      bucketId = media.bucketId,
      caption = media.caption,
      transformProperties = TransformProperties(
        skipTransform = false,
        videoTrim = data.isDurationEdited,
        videoTrimStartTimeUs = if (data.isDurationEdited) data.startTimeUs else 0,
        videoTrimEndTimeUs = if (data.isDurationEdited) data.endTimeUs else 0,
        sentMediaQuality = SentMediaQuality.STANDARD.code,
        mp4FastStart = false,
        videoMuted = data.isMuted,
        tellomiVideoShortEdge = data.tellomiShortEdge
      ),
      fileName = media.fileName
    )
  }
}
