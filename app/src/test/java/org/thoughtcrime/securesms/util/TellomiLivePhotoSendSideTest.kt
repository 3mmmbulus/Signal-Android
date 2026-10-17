/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.net.Uri
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.Base64
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import java.util.concurrent.TimeUnit

/**
 * tellomi/tellomi#1263 P-4, ADR-0068 §4.4: what the send takes from a still that has a moving half, and what a forward
 * keeps of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiLivePhotoSendSideTest {

  @Test
  fun `the send takes the stored pointer as it is, unless it goes as a file`() {
    val encoded = Base64.encodeWithPadding(videoPointer(uploadTimestamp = NOW).encode())

    assertThat(TellomiLivePhoto.videoPointerBytes(still(TransformProperties(tellomiLivePhotoVideo = encoded)))!!.toByteString())
      .isEqualTo(videoPointer(uploadTimestamp = NOW).encode().toByteString())
    assertThat(TellomiLivePhoto.videoPointerBytes(still(TransformProperties(tellomiAsFile = true, tellomiLivePhotoVideo = encoded)))).isNull()
    assertThat(TellomiLivePhoto.videoPointerBytes(still(null))).isNull()
  }

  @Test
  fun `a forward keeps the moving half while the upload can be reused`() {
    val fresh = Base64.encodeWithPadding(videoPointer(uploadTimestamp = NOW - TimeUnit.DAYS.toMillis(2)).encode())
    val stale = Base64.encodeWithPadding(videoPointer(uploadTimestamp = NOW - TimeUnit.DAYS.toMillis(4)).encode())
    val unknown = Base64.encodeWithPadding(videoPointer(uploadTimestamp = 0).encode())

    assertThat(TellomiLivePhoto.forwardablePointer(still(TransformProperties(tellomiLivePhotoVideo = fresh)), now = NOW)).isEqualTo(fresh)
    assertThat(TellomiLivePhoto.forwardablePointer(still(TransformProperties(tellomiLivePhotoVideo = stale)), now = NOW)).isNull()
    assertThat(TellomiLivePhoto.forwardablePointer(still(TransformProperties(tellomiLivePhotoVideo = unknown)), now = NOW)).isNull()
    assertThat(TellomiLivePhoto.forwardablePointer(still(null), now = NOW)).isNull()
  }

  private fun videoPointer(uploadTimestamp: Long) = AttachmentPointer(
    cdnNumber = 3,
    cdnKey = "live-video",
    contentType = "video/mp4",
    key = ByteArray(64) { 1 }.toByteString(),
    size = 600_000,
    digest = ByteArray(32) { 2 }.toByteString(),
    uploadTimestamp = uploadTimestamp
  )

  private fun still(transformProperties: TransformProperties?) = UriAttachment(
    dataUri = Uri.parse("content://media/1"),
    contentType = "image/jpeg",
    transferState = AttachmentTable.TRANSFER_PROGRESS_DONE,
    size = 1024,
    width = 100,
    height = 100,
    fileName = null,
    fastPreflightId = null,
    voiceNote = false,
    borderless = false,
    videoGif = false,
    quote = false,
    quoteTargetContentType = null,
    caption = null,
    stickerLocator = null,
    blurHash = null,
    audioHash = null,
    transformProperties = transformProperties,
    uuid = null
  )

  private companion object {
    const val NOW = 1_760_000_000_000L
  }
}
