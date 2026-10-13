/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.util

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.archive.proto.FilePointer
import org.signal.archive.proto.TellomiAttachment
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.Base64
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.whispersystems.signalservice.internal.push.AttachmentPointer

/**
 * ADR-0068 §4.5 (tellomi/tellomi#1263): how an attachment is presented goes into a backup and comes back out by the
 * rules of receiving it.
 */
class TellomiArchiveAttachmentsTest {

  private val video = AttachmentPointer(
    cdnKey = "live-video-cdn-key",
    cdnNumber = 3,
    contentType = "video/mp4",
    key = ByteArray(64) { 7 }.toByteString(),
    size = 456_789,
    digest = ByteArray(32) { 9 }.toByteString(),
    uploadTimestamp = 1_700_000_000_000,
    width = 1080,
    height = 1920
  )

  @Test
  fun `nothing Tellomi, nothing in the backup`() {
    assertNull(TellomiArchiveAttachments.toRemote(attachment(null)))
    assertNull(TellomiArchiveAttachments.toRemote(attachment(TransformProperties())))
    assertNull(TellomiArchiveAttachments.toLocal(null, "image/jpeg", quote = false))
    assertNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(), "image/jpeg", quote = false))
  }

  @Test
  fun `as a file and covered go out, uncovered on this device doesn't`() {
    assertEquals(TellomiAttachment(asFile = true), TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiAsFile = true))))
    assertEquals(
      TellomiAttachment(spoiler = true),
      TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiSpoiler = true, tellomiSpoilerRevealed = true)))
    )
  }

  @Test
  fun `a live photo's moving half goes out as a transit file pointer and comes back the same`() {
    val stored = Base64.encodeWithPadding(video.encode())
    val remote = TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiLivePhotoVideo = stored)))!!
    val pointer = remote.livePhotoVideo!!
    assertEquals("video/mp4", pointer.contentType)
    assertEquals("live-video-cdn-key", pointer.locatorInfo!!.transitCdnKey)
    assertEquals(3, pointer.locatorInfo!!.transitCdnNumber)
    assertEquals(456_789, pointer.locatorInfo!!.size)
    assertEquals(video.digest, pointer.locatorInfo!!.encryptedDigest)
    assertNull(pointer.locatorInfo!!.plaintextHash)

    val local = TellomiArchiveAttachments.toLocal(remote, "image/heic", quote = false)!!
    val back = AttachmentPointer.ADAPTER.decode(Base64.decode(local.tellomiLivePhotoVideo!!))
    assertEquals(video.cdnKey, back.cdnKey)
    assertEquals(video.cdnNumber, back.cdnNumber)
    assertEquals(video.contentType, back.contentType)
    assertEquals(video.key, back.key)
    assertEquals(video.size, back.size)
    assertEquals(video.digest, back.digest)
    assertEquals(video.uploadTimestamp, back.uploadTimestamp)
  }

  @Test
  fun `coming back, the rules of receiving apply`() {
    val live = TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiLivePhotoVideo = Base64.encodeWithPadding(video.encode()))))!!.livePhotoVideo

    // A quoted thumbnail is never a file and has no moving half, but stays covered
    val quoted = TellomiArchiveAttachments.toLocal(TellomiAttachment(asFile = true, livePhotoVideo = live), "image/jpeg", quote = true)
    assertNull(quoted)
    assertEquals(TransformProperties(tellomiSpoiler = true), TellomiArchiveAttachments.toLocal(TellomiAttachment(spoiler = true), "image/jpeg", quote = true))

    // As a file wins
    assertEquals(
      TransformProperties(tellomiAsFile = true),
      TellomiArchiveAttachments.toLocal(TellomiAttachment(asFile = true, spoiler = true, livePhotoVideo = live), "image/jpeg", quote = false)
    )

    // Covered only on images and videos
    assertNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(spoiler = true), "audio/aac", quote = false))
    assertTrue(TellomiArchiveAttachments.toLocal(TellomiAttachment(spoiler = true), "video/mp4", quote = false)!!.tellomiSpoiler)

    // A moving half only on a still, and only when it is a video with where it is and its keys
    assertNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(livePhotoVideo = live), "video/mp4", quote = false))
    assertNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(livePhotoVideo = live!!.copy(contentType = "image/jpeg")), "image/jpeg", quote = false))
    assertNull(
      TellomiArchiveAttachments.toLocal(
        TellomiAttachment(livePhotoVideo = live.copy(locatorInfo = live.locatorInfo!!.copy(encryptedDigest = null))),
        "image/jpeg",
        quote = false
      )
    )
    assertNull(
      TellomiArchiveAttachments.toLocal(
        TellomiAttachment(livePhotoVideo = live.copy(locatorInfo = live.locatorInfo!!.copy(transitCdnKey = null))),
        "image/jpeg",
        quote = false
      )
    )
    assertNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(livePhotoVideo = FilePointer(contentType = "video/mp4")), "image/jpeg", quote = false))
    assertNotNull(TellomiArchiveAttachments.toLocal(TellomiAttachment(livePhotoVideo = live), "image/jpeg", quote = false)!!.tellomiLivePhotoVideo)
  }

  @Test
  fun `a stored pointer that can't be read leaves the moving half out`() {
    assertNull(TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiLivePhotoVideo = "not-a-pointer"))))
    val covered = TellomiArchiveAttachments.toRemote(attachment(TransformProperties(tellomiSpoiler = true, tellomiLivePhotoVideo = "not-a-pointer")))!!
    assertTrue(covered.spoiler)
    assertNull(covered.livePhotoVideo)
    assertFalse(covered.asFile)
  }

  private fun attachment(transformProperties: TransformProperties?): DatabaseAttachment {
    return DatabaseAttachment(
      attachmentId = AttachmentId(1L),
      mmsId = 42L,
      hasData = false,
      hasThumbnail = false,
      contentType = "image/jpeg",
      transferProgress = AttachmentTable.TRANSFER_PROGRESS_DONE,
      size = 1_000L,
      fileName = "IMG_0001.JPG",
      cdn = Cdn.CDN_3,
      location = null,
      key = null,
      digest = null,
      incrementalDigest = null,
      incrementalMacChunkSize = 0,
      fastPreflightId = null,
      voiceNote = false,
      borderless = false,
      videoGif = false,
      width = 0,
      height = 0,
      quote = false,
      caption = null,
      stickerLocator = null,
      blurHash = null,
      audioHash = null,
      transformProperties = transformProperties,
      displayOrder = 0,
      uploadTimestamp = 0,
      dataHash = null,
      archiveCdn = null,
      thumbnailRestoreState = AttachmentTable.ThumbnailRestoreState.NONE,
      archiveTransferState = AttachmentTable.ArchiveTransferState.NONE,
      archiveThumbnailTransferState = AttachmentTable.ArchiveTransferState.NONE,
      uuid = null,
      quoteTargetContentType = null,
      metadata = null
    )
  }
}
