/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.mediasend.SentMediaQuality
import org.signal.mediasend.screens.edit.video.VideoTrimData
import org.thoughtcrime.securesms.mediasend.VideoTrimTransform

/**
 * Tellomi（tellomi/tellomi#1518 V-7 ②）：每个视频选的画质档存在 `TransformProperties.tellomiVideoShortEdge` 里——
 * 只在选了档时写进 JSON（其余附件的 JSON 和上游一样），算「改过的视频」（预上传、去重都不复用别的档的转码），
 * 剪辑页的状态一路带到发送用的属性里，整批高清也不会把它冲掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiVideoLevelTransformTest {

  @Test
  fun `the level is only in the JSON when one was picked, and survives a round trip`() {
    assertThat(TransformProperties.empty().serialize().contains("tellomiVideoShortEdge")).isFalse()

    val picked = TransformProperties(tellomiVideoShortEdge = 1080)
    val json = picked.serialize()
    assertThat(json.contains("\"tellomiVideoShortEdge\":1080")).isTrue()
    assertThat(parseTransformProperties(json).tellomiVideoShortEdge).isEqualTo(1080)
  }

  @Test
  fun `a picked level counts as an edited video`() {
    assertThat(TransformProperties().videoEdited).isFalse()
    assertThat(TransformProperties(tellomiVideoShortEdge = 720).videoEdited).isTrue()
  }

  @Test
  fun `the trim screen's level reaches the send properties and high quality keeps it`() {
    val media = Media(
      uri = "content://media/video/1".toUri(),
      contentType = "video/mp4",
      date = 0,
      width = 1920,
      height = 1080,
      size = 1024,
      duration = 60_000,
      isBorderless = false,
      isVideoGif = false,
      bucketId = Media.ALL_MEDIA_BUCKET_ID,
      caption = null,
      transformProperties = null,
      fileName = null
    )

    val transformed = VideoTrimTransform(VideoTrimData(totalInputDurationUs = 60_000_000, endTimeUs = 60_000_000, tellomiShortEdge = 720))
      .transform(org.robolectric.RuntimeEnvironment.getApplication(), media)
    assertThat(transformed.transformProperties!!.tellomiVideoShortEdge).isEqualTo(720)

    val high = transformPropertiesForSentMediaQuality(java.util.Optional.ofNullable(transformed.transformProperties), SentMediaQuality.HIGH)
    assertThat(high.tellomiVideoShortEdge).isEqualTo(720)
    assertThat(high.sentMediaQuality).isEqualTo(SentMediaQuality.HIGH.code)
  }
}
