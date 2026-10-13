/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.TransformProperties
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.jobmanager.impl.NotInCallConstraint
import org.thoughtcrime.securesms.recipients.Recipient

/** ADR-0068 (tellomi/tellomi#1263): a photo sent as a file follows the "documents" auto-download setting. */
class TellomiAsFileAutoDownloadTest {

  private val context = mockk<Context>(relaxed = true)
  private val messageTable = mockk<MessageTable>(relaxed = true)
  private val threadTable = mockk<ThreadTable>(relaxed = true)
  private val fromRecipient = mockk<Recipient>(relaxed = true)
  private val messageRecord = mockk<MessageRecord>(relaxed = true)

  @Before
  fun setUp() {
    mockkObject(SignalDatabase.Companion)
    every { SignalDatabase.instance } returns mockk {
      every { messageTable } returns this@TellomiAsFileAutoDownloadTest.messageTable
      every { threadTable } returns this@TellomiAsFileAutoDownloadTest.threadTable
    }
    mockkStatic(NotInCallConstraint::class)
    mockkStatic(NetworkUtil::class)
    mockkStatic(TextSecurePreferences::class)

    every { NotInCallConstraint.isNotInConnectedCall() } returns true
    every { NetworkUtil.isConnectedWifi(context) } returns true
    every { NetworkUtil.isConnectedRoaming(context) } returns false
    every { NetworkUtil.isConnectedMobile(context) } returns false
    every { TextSecurePreferences.getRoamingMediaDownloadAllowed(context) } returns emptySet()
    every { TextSecurePreferences.getMobileMediaDownloadAllowed(context) } returns emptySet()

    every { messageTable.getMessageRecord(any()) } returns messageRecord
    every { messageRecord.fromRecipient } returns fromRecipient
    every { messageRecord.isOutgoing } returns false
    every { threadTable.getRecipientForThreadId(any()) } returns null
    every { fromRecipient.isSystemContact } returns true
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `a photo sent as a file waits when only photos auto-download`() {
    every { TextSecurePreferences.getWifiMediaDownloadAllowed(context) } returns setOf("image")

    assertFalse(AttachmentUtil.isAutoDownloadPermitted(context, photo(asFile = true)))
    assertTrue(AttachmentUtil.isAutoDownloadPermitted(context, photo(asFile = false)))
  }

  @Test
  fun `a photo sent as a file downloads when documents auto-download`() {
    every { TextSecurePreferences.getWifiMediaDownloadAllowed(context) } returns setOf("documents")

    assertTrue(AttachmentUtil.isAutoDownloadPermitted(context, photo(asFile = true)))
    assertFalse(AttachmentUtil.isAutoDownloadPermitted(context, photo(asFile = false)))
  }

  private fun photo(asFile: Boolean): DatabaseAttachment {
    return DatabaseAttachment(
      attachmentId = AttachmentId(1L),
      mmsId = 42L,
      hasData = false,
      hasThumbnail = false,
      contentType = "image/jpeg",
      transferProgress = AttachmentTable.TRANSFER_PROGRESS_PENDING,
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
      transformProperties = if (asFile) TransformProperties(tellomiAsFile = true) else null,
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
