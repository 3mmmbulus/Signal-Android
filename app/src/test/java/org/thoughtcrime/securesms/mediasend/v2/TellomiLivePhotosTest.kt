/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v2

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.Media
import org.signal.mediasend.TellomiMotionPhoto
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.TellomiLivePhotoUploadJob
import org.thoughtcrime.securesms.sms.MessageSender.PreUploadResult

/**
 * tellomi/tellomi#1263 P-4, ADR-0068 §4.4: live photos turned on in the picker each get a second upload, and the send waits
 * for it through the pre-upload results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLivePhotosTest {

  @Test
  fun `live originals map to what is actually sent, stills only`() {
    val live = photo("content://media/live")
    val edited = photo("content://media/edited")
    val renderedEdit = photo("content://blob/edited-render")
    val video = photo("content://media/video", contentType = "video/mp4")
    val plain = photo("content://media/plain")
    val liveVideos = mapOf(live.uri to RANGE, edited.uri to RANGE, video.uri to RANGE)

    val sources = TellomiLivePhotos.renderedSources(
      selected = listOf(live, edited, video, plain),
      oldToNew = mapOf(live to live, edited to renderedEdit, video to video, plain to plain),
      liveVideos = liveVideos
    )

    // The edited one is sent as its render, with the original's video; a video can't be a live photo.
    assertThat(sources).isEqualTo(
      mapOf(
        live.uri to TellomiLivePhotos.Source(live.uri, RANGE),
        renderedEdit.uri to TellomiLivePhotos.Source(edited.uri, RANGE)
      )
    )
    assertThat(TellomiLivePhotos.renderedSources(listOf(live), mapOf(live to live), emptyMap())).isEmpty()
  }

  @Test
  fun `each live photo's pre-upload result waits for its own upload job`() {
    val live = photo("content://media/live")
    val plain = photo("content://media/plain")
    val results = listOf(
      PreUploadResult(live, AttachmentId(1), listOf("compress-1", "upload-1")),
      PreUploadResult(plain, AttachmentId(2), listOf("compress-2", "upload-2"))
    )
    val enqueued = mutableListOf<Job>()

    val withJobs = TellomiLivePhotos.withUploadJobs(results, mapOf(live.uri to TellomiLivePhotos.Source(live.uri, RANGE)), enqueue = { enqueued += it })

    assertThat(enqueued.size).isEqualTo(1)
    assertThat(enqueued.single()).isInstanceOf(TellomiLivePhotoUploadJob::class)
    assertThat(withJobs[0].jobIds.toList()).containsExactly("compress-1", "upload-1", enqueued.single().id)
    assertThat(withJobs[0].attachmentId).isEqualTo(AttachmentId(1))
    assertThat(withJobs[1].jobIds.toList()).containsExactly("compress-2", "upload-2")

    assertThat(TellomiLivePhotos.withUploadJobs(results, emptyMap(), enqueue = { enqueued += it })).isEqualTo(results)
    assertThat(enqueued.size).isEqualTo(1)
  }

  private companion object {
    val RANGE = TellomiMotionPhoto.VideoRange(offset = 3_000_000, length = 2_000_000)

    fun photo(uri: String, contentType: String = "image/jpeg") = Media(
      uri = uri.toUri(),
      contentType = contentType,
      date = 0,
      width = 100,
      height = 100,
      size = 5_000_000,
      duration = 0,
      isBorderless = false,
      isVideoGif = false,
      bucketId = null,
      caption = null,
      transformProperties = null,
      fileName = null
    )
  }
}
