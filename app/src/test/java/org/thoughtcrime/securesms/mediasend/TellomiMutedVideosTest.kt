/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.signal.core.models.media.Media
import org.signal.core.models.media.TransformProperties
import org.signal.mediasend.screens.edit.video.VideoTrimData

/**
 * Tellomi（tellomi/tellomi#1518 V-6，owner 2026-10-14 照 TG 两端）：静音的视频当 GIF 发、单独成一条，其余照常成组。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiMutedVideosTest {

  @Test
  fun `muting a video marks it as a GIF with its audio to be dropped`() {
    val muted = VideoTrimTransform(VideoTrimData(totalInputDurationUs = 5_000_000, endTimeUs = 5_000_000, isMuted = true))
      .transform(RuntimeEnvironment.getApplication(), video("1"))
    assertThat(muted.isVideoGif).isTrue()
    assertThat(muted.transformProperties!!.videoMuted).isTrue()
    assertThat(TellomiMutedVideos.isMutedGif(muted)).isTrue()

    val trimmed = VideoTrimTransform(VideoTrimData(isDurationEdited = true, totalInputDurationUs = 5_000_000, startTimeUs = 1_000_000, endTimeUs = 5_000_000))
      .transform(RuntimeEnvironment.getApplication(), video("2"))
    assertThat(trimmed.isVideoGif).isFalse()
  }

  @Test
  fun `a keyboard GIF is not a muted video`() {
    assertThat(TellomiMutedVideos.isMutedGif(video("1").copy(isVideoGif = true))).isFalse()
  }

  @Test
  fun `muted videos go one per message after the rest, which stay together and carry the caption`() {
    val photo = photo("p")
    val muted = mutedVideo("m")
    val plain = video("v")

    val split = TellomiMutedVideos.split(listOf(photo, muted, plain))!!
    assertThat(split.groups).isEqualTo(listOf(listOf(0, 2), listOf(1)))
    assertThat(split.bodyIndex).isEqualTo(0)

    val allMuted = TellomiMutedVideos.split(listOf(muted, mutedVideo("n")))!!
    assertThat(allMuted.groups).isEqualTo(listOf(listOf(0), listOf(1)))
    assertThat(allMuted.bodyIndex).isEqualTo(1)

    assertThat(TellomiMutedVideos.split(listOf(photo, plain))).isEqualTo(null)
    assertThat(TellomiMutedVideos.split(listOf(muted))).isEqualTo(null)
  }

  @Test
  fun `a long caption's text attachment goes with the caption, not as a message of its own`() {
    val longText = video("t").copy(contentType = "text/x-signal-plain", duration = 0)

    // One muted video and a long caption: still one message.
    assertThat(TellomiMutedVideos.split(listOf(mutedVideo("m"), longText))).isEqualTo(null)

    val split = TellomiMutedVideos.split(listOf(photo("p"), mutedVideo("m"), longText))!!
    assertThat(split.groups).isEqualTo(listOf(listOf(0, 2), listOf(1)))

    val allMuted = TellomiMutedVideos.split(listOf(mutedVideo("m"), mutedVideo("n"), longText))!!
    assertThat(allMuted.groups).isEqualTo(listOf(listOf(0), listOf(1, 2)))
    assertThat(allMuted.bodyIndex).isEqualTo(1)
  }

  private fun mutedVideo(id: String) = video(id).copy(isVideoGif = true, transformProperties = TransformProperties(videoMuted = true))

  private fun photo(id: String) = video(id).copy(contentType = "image/jpeg", duration = 0)

  private fun video(id: String) = Media(
    uri = "content://media/$id".toUri(),
    contentType = "video/mp4",
    date = 0,
    width = 1920,
    height = 1080,
    size = 1024,
    duration = 5_000,
    isBorderless = false,
    isVideoGif = false,
    bucketId = Media.ALL_MEDIA_BUCKET_ID,
    caption = null,
    transformProperties = null,
    fileName = null
  )
}
