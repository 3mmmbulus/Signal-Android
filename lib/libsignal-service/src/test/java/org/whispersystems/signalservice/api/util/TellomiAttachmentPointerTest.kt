/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.signalservice.api.util

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.TellomiAttachment
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/** ADR-0068 §4.1 / §4.7 (tellomi/tellomi#1263): `AttachmentPointer.tellomi = 1001`, `DataMessage.tellomi = 1000`. */
class TellomiAttachmentPointerTest {

  @Test
  fun `field numbers match ADR-0068, golden bytes shared by the three clients`() {
    // key = 1001 << 3 | 2 = ca 3e; TellomiAttachment{asFile = true} = 08 01
    assertEquals("ca3e020801", AttachmentPointer(tellomi = TellomiAttachment(asFile = true)).encode().toByteString().hex())
    // key = 1000 << 3 | 2 = c2 3e; TellomiDataMessage{captionAboveMedia = true} = 08 01
    assertEquals("c23e020801", DataMessage(tellomi = TellomiDataMessage(captionAboveMedia = true)).encode().toByteString().hex())
    // spoiler = 2 (bool), livePhotoVideo = 3 (AttachmentPointer): defined now, used by later releases
    assertEquals("ca3e021001", AttachmentPointer(tellomi = TellomiAttachment(spoiler = true)).encode().toByteString().hex())
    assertEquals("ca3e021a00", AttachmentPointer(tellomi = TellomiAttachment(livePhotoVideo = AttachmentPointer())).encode().toByteString().hex())
  }

  @Test
  fun `asFile survives the receive then send round trip`() {
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(asFile = true)))
    assertTrue(received.tellomiAsFile)

    val sent = AttachmentPointerUtil.createAttachmentPointer(received)
    assertEquals(true, sent.tellomi?.asFile)
    assertEquals("IMG_0001.HEIC", sent.fileName)
    assertEquals("image/heic", sent.contentType)
  }

  @Test
  fun `an ordinary attachment carries no tellomi field, so it stays byte-identical to upstream`() {
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(pointer(null))
    assertFalse(received.tellomiAsFile)

    val sent = AttachmentPointerUtil.createAttachmentPointer(received)
    assertNull(sent.tellomi)
    assertEquals(sent.copy(tellomi = null).encode().toByteString(), sent.encode().toByteString())
  }

  @Test
  fun `asFile false, or a spoiler alone, is not as file`() {
    assertFalse(AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(asFile = false))).tellomiAsFile)
    assertFalse(AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(spoiler = true))).tellomiAsFile)
  }

  /** ADR-0068 §4.1 rules 1–3: a spoiler only counts on an image / video, and asFile wins; anything else is ignored, not dropped. */
  @Test
  fun `a spoiler counts on an image or a video, and nowhere else`() {
    assertTrue(spoiler(TellomiAttachment(spoiler = true), "image/jpeg"))
    assertTrue(spoiler(TellomiAttachment(spoiler = true), "IMAGE/HEIC"))
    assertTrue(spoiler(TellomiAttachment(spoiler = true), "video/mp4"))

    assertFalse("a document can't be covered", spoiler(TellomiAttachment(spoiler = true), "application/pdf"))
    assertFalse("asFile wins", spoiler(TellomiAttachment(asFile = true, spoiler = true), "image/jpeg"))
    assertFalse(spoiler(TellomiAttachment(spoiler = false), "image/jpeg"))
    assertFalse(spoiler(null, "image/jpeg"))
    assertFalse("no content type", spoiler(TellomiAttachment(spoiler = true), null))
  }

  @Test
  fun `a file with a spoiler on it is still received as a file`() {
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(asFile = true, spoiler = true)))
    assertTrue(received.tellomiAsFile)
    assertFalse(received.tellomiSpoiler)
  }

  /** ADR-0068 §4.3: Tellomi's protocol version 9 is the covered-media one, and it is what this client supports. */
  @Test
  fun `the current protocol version is Tellomi's 9`() {
    assertEquals(9, DataMessage.ProtocolVersion.TELLOMI_MEDIA_SPOILER.value)
    assertEquals(9, DataMessage.ProtocolVersion.CURRENT.value)
  }

  /** ADR-0068 §4.1 rules 2–3, spec R-2: the moving half of a live photo, on a still only, one level, a playable pointer. */
  @Test
  fun `a live photo's video is kept with the still, and only when it is valid`() {
    val video = video()
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(livePhotoVideo = video)).copy(contentType = "image/jpeg"))
    assertEquals(video, AttachmentPointer.ADAPTER.decode(received.tellomiLivePhotoVideo!!))

    // The still and the spoiler together are fine (a covered live photo)
    assertTrue(live(TellomiAttachment(spoiler = true, livePhotoVideo = video), "image/heic"))

    assertFalse("asFile wins", live(TellomiAttachment(asFile = true, livePhotoVideo = video), "image/heic"))
    assertFalse("only on a still", live(TellomiAttachment(livePhotoVideo = video), "video/mp4"))
    assertFalse("the moving half is a video", live(TellomiAttachment(livePhotoVideo = video.copy(contentType = "image/jpeg")), "image/heic"))
    assertFalse("one level only", live(TellomiAttachment(livePhotoVideo = video.copy(tellomi = TellomiAttachment(livePhotoVideo = video()))), "image/heic"))
    assertFalse("needs a key", live(TellomiAttachment(livePhotoVideo = video.copy(key = null)), "image/heic"))
    assertFalse("needs where it is", live(TellomiAttachment(livePhotoVideo = video.copy(cdnKey = null)), "image/heic"))
    assertFalse(live(null, "image/heic"))
  }

  @Test
  fun `a live photo with a bad video is still received, as an ordinary picture`() {
    val received = AttachmentPointerUtil.createSignalAttachmentPointer(pointer(TellomiAttachment(livePhotoVideo = AttachmentPointer())))
    assertNull(received.tellomiLivePhotoVideo)
    assertEquals("image/heic", received.contentType)
  }

  private fun live(tellomi: TellomiAttachment?, contentType: String?): Boolean {
    return AttachmentPointerUtil.createSignalAttachmentPointer(pointer(tellomi).copy(contentType = contentType)).tellomiLivePhotoVideo != null
  }

  private fun video() = AttachmentPointer(
    cdnNumber = 3,
    cdnKey = "cdn-key-video",
    contentType = "video/mp4",
    key = ByteArray(64) { 7 }.toByteString(),
    size = 456789,
    digest = ByteArray(32) { 9 }.toByteString()
  )

  private fun spoiler(tellomi: TellomiAttachment?, contentType: String?): Boolean {
    return AttachmentPointerUtil.createSignalAttachmentPointer(pointer(tellomi).copy(contentType = contentType)).tellomiSpoiler
  }

  private fun pointer(tellomi: TellomiAttachment?) = AttachmentPointer(
    cdnNumber = 3,
    cdnKey = "cdn-key",
    contentType = "image/heic",
    key = ByteArray(64).toByteString(),
    size = 1234,
    digest = ByteArray(32).toByteString(),
    fileName = "IMG_0001.HEIC",
    tellomi = tellomi
  )
}
