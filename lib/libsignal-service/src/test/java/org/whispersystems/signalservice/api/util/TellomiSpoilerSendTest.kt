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
import org.whispersystems.signalservice.api.TellomiSpoilerVersion
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.internal.push.AttachmentPointer
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.TellomiAttachment
import java.util.Optional

/** ADR-0068 §4.2–4.4 (tellomi/tellomi#1263 P-5): the send side of "Hide with Spoiler". */
class TellomiSpoilerSendTest {

  @Test
  fun `a covered attachment goes out with only the spoiler flag, the bytes the receive side reads`() {
    val covered = AttachmentPointerUtil.createAttachmentPointer(attachment(spoiler = true))

    assertEquals("1001", covered.tellomi!!.encode().toByteString().hex())
    assertTrue(AttachmentPointerUtil.isTellomiSpoiler(covered))
    // Through the bytes, as a receiver gets it.
    assertTrue(AttachmentPointerUtil.createSignalAttachmentPointer(covered.encode()).tellomiSpoiler)
  }

  @Test
  fun `not covered, no tellomi field at all`() {
    val sent = AttachmentPointerUtil.createAttachmentPointer(AttachmentPointerUtil.createSignalAttachmentPointer(pointer(null)))
    assertNull(sent.tellomi)
  }

  @Test
  fun `sent as a file wins over the spoiler`() {
    val sent = AttachmentPointerUtil.createAttachmentPointer(attachment(asFile = true, spoiler = true))
    assertEquals(true, sent.tellomi?.asFile)
    assertNull(sent.tellomi?.spoiler)
  }

  @Test
  fun `any covered pointer, body or quote thumbnail, asks for version 9`() {
    val covered = pointer(TellomiAttachment(spoiler = true))
    val plain = pointer(null)
    assertTrue(TellomiSpoilerVersion.needsSpoilerVersion(listOf(plain, covered), null))
    assertFalse(TellomiSpoilerVersion.needsSpoilerVersion(listOf(plain), null))
    assertFalse(TellomiSpoilerVersion.needsSpoilerVersion(emptyList(), null))

    val coveredQuote = DataMessage.Quote(attachments = listOf(DataMessage.Quote.QuotedAttachment(contentType = "image/jpeg", thumbnail = covered)))
    val plainQuote = DataMessage.Quote(attachments = listOf(DataMessage.Quote.QuotedAttachment(contentType = "image/jpeg", thumbnail = plain)))
    assertTrue(TellomiSpoilerVersion.needsSpoilerVersion(emptyList(), coveredQuote))
    assertFalse(TellomiSpoilerVersion.needsSpoilerVersion(emptyList(), plainQuote))
    assertEquals(9, DataMessage.ProtocolVersion.TELLOMI_MEDIA_SPOILER.value)
  }

  /** What `PushSendJob` builds from a local attachment. */
  private fun attachment(asFile: Boolean = false, spoiler: Boolean = false) = SignalServiceAttachmentPointer(
    cdnNumber = 3,
    remoteId = SignalServiceAttachmentRemoteId.from(pointer(null)),
    contentType = "image/jpeg",
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
    tellomiSpoiler = spoiler
  )

  private fun pointer(tellomi: TellomiAttachment?) = AttachmentPointer(
    cdnNumber = 3,
    cdnKey = "cdn-key",
    contentType = "image/jpeg",
    key = ByteArray(64).toByteString(),
    size = 1234,
    digest = ByteArray(32).toByteString(),
    tellomi = tellomi
  )
}
