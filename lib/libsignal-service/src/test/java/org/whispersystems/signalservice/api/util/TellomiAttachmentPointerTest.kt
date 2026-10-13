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
