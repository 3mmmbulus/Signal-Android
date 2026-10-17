/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import androidx.core.net.toUri
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.mediasend.TellomiMotionPhoto
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.video.TranscodingQuality

/**
 * tellomi/tellomi#1263 P-4, ADR-0068 §4.4: the moving half of a live photo goes out as H.264 with the long edge at most
 * 848, about 1.6 Mbps, AAC 64 kbps (Telegram iOS's numbers), and the job survives being written down and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLivePhotoUploadJobTest {

  @Test
  fun `the long edge ends up at most 848 and nothing is scaled up`() {
    // Pixel motion photos: 4:3 and 16:9, either way round.
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(1440, 1080)).isEqualTo(636)
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(1080, 1440)).isEqualTo(636)
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(1920, 1080)).isEqualTo(477)
    // Small already: kept.
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(640, 480)).isEqualTo(480)
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(848, 478)).isEqualTo(478)
    assertThat(TellomiLivePhotoUploadJob.shortEdgeFor(0, 0)).isEqualTo(0)
  }

  @Test
  fun `the transcoder target is 1_6 Mbps video and 64 kbps audio at that short edge`() {
    val quality = TranscodingQuality.createFromQualityTiers(listOf(TellomiLivePhotoUploadJob.qualityTier(636)), durationMs = 3_000)
    assertThat(quality.outputResolution).isEqualTo(636)
    assertThat(quality.targetVideoBitRate).isEqualTo(1_600_000)
    assertThat(quality.targetAudioBitRate).isEqualTo(64_000)
  }

  @Test
  fun `the job reads back what it wrote down`() {
    val job = TellomiLivePhotoUploadJob(AttachmentId(42), "content://media/external/images/media/7".toUri(), TellomiMotionPhoto.VideoRange(3_000_000, 2_000_000))
    val restored = TellomiLivePhotoUploadJob.Factory().create(Job.Parameters.Builder().build(), job.serialize())

    assertThat(restored.serialize()!!.decodeToString()).isEqualTo(job.serialize()!!.decodeToString())
    assertThat(restored.factoryKey).isEqualTo(TellomiLivePhotoUploadJob.KEY)
  }
}
