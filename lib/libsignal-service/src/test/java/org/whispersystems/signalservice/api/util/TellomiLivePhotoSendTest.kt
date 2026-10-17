/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.TellomiAttachment
import java.util.Optional

/** ADR-0068 §4.4 (tellomi/tellomi#1263 P-4): a live photo's moving half goes out as `tellomi.livePhotoVideo` on the still. */
class TellomiLivePhotoSendTest {

  @Test
  fun `a live still carries the nested video pointer, the bytes the receive side reads`() {
    val video = videoPointer()
    val sent = AttachmentPointerUtil.createAttachmentPointer(attachment(live = video.encode()))

    assertEquals(video, sent.tellomi!!.livePhotoVideo)
    assertNull(sent.tellomi!!.spoiler)
    // Field 3 of TellomiAttachment, length-delimited: tag 0x1a, then the nested pointer.
    assertEquals("1a", sent.tellomi!!.encode().toByteString().hex().substring(0, 2))
    // Through the bytes, as a receiver gets it (Signal-Android#177's rules).
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(sent.encode())
    assertArrayEquals(video.encode(), received.tellomiLivePhotoVideo)
  }

  @Test
  fun `a covered live photo carries both`() {
    val sent = AttachmentPointerUtil.createAttachmentPointer(attachment(spoiler = true, live = videoPointer().encode()))
    assertEquals(true, sent.tellomi!!.spoiler)
    assertNotNull(sent.tellomi!!.livePhotoVideo)
  }

  @Test
  fun `only a still image sent as media carries it`() {
    val live = videoPointer().encode()
    assertNull(AttachmentPointerUtil.createAttachmentPointer(attachment(contentType = "video/mp4", live = live)).tellomi)

    val asFile = AttachmentPointerUtil.createAttachmentPointer(attachment(asFile = true, live = live))
    assertEquals(true, asFile.tellomi?.asFile)
    assertNull(asFile.tellomi?.livePhotoVideo)
  }

  @Test
  fun `a nested pointer the receiver would refuse is not sent`() {
    // Not a video, no key, no CDN location, nested twice, not a pointer at all: the still goes out alone.
    val refused = listOf(
      videoPointer().copy(contentType = "image/jpeg").encode(),
      videoPointer().copy(key = null).encode(),
      videoPointer().copy(cdnKey = null).encode(),
      videoPointer().copy(tellomi = TellomiAttachment(livePhotoVideo = videoPointer())).encode(),
      byteArrayOf(0x7f, 0x01, 0x02)
    )
    for (bytes in refused) {
      assertNull(AttachmentPointerUtil.createAttachmentPointer(attachment(live = bytes)).tellomi)
    }
  }

  @Test
  fun `not live, no tellomi field at all`() {
    assertNull(AttachmentPointerUtil.createAttachmentPointer(attachment()).tellomi)
  }

  private fun videoPointer() = AttachmentPointer(
    cdnNumber = 3,
    cdnKey = "live-video",
    contentType = "video/mp4",
    key = ByteArray(64) { 7 }.toByteString(),
    size = 600_000,
    digest = ByteArray(32) { 9 }.toByteString(),
    width = 848,
    height = 478,
    uploadTimestamp = 1_760_000_000_000
  )

  /** What `PushSendJob` builds from a local attachment. */
  private fun attachment(contentType: String = "image/jpeg", asFile: Boolean = false, spoiler: Boolean = false, live: ByteArray? = null) = SignalServiceAttachmentPointer(
    cdnNumber = 3,
    remoteId = SignalServiceAttachmentRemoteId.V4("cdn-key"),
    contentType = contentType,
    key = ByteArray(64),
    size = Optional.of(1234),
    preview = Optional.empty(),
    width = 0,
    height = 0,
    digest = Optional.of(ByteArray(32)),
    incrementalDigest = Optional.empty(),
    incrementalMacChunkSize = 0,
    fileName = Optional.empty(),
    voiceNote = false,
    isBorderless = false,
    isGif = false,
    caption = Optional.empty(),
    blurHash = Optional.empty(),
    uploadTimestamp = 0,
    uuid = null,
    tellomiAsFile = asFile,
    tellomiSpoiler = spoiler,
    tellomiLivePhotoVideo = live
  )
}
