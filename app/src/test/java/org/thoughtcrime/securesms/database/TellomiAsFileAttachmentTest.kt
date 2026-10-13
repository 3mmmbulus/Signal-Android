/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import android.database.Cursor
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.database.AttachmentId
import org.signal.core.models.media.TransformProperties
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.mms.DocumentSlide
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.MediaUtil
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * ADR-0068 (tellomi/tellomi#1263): an image received with `AttachmentPointer.tellomi.asFile` keeps the
 * flag from the pointer, through the download, into the file row and the "files" tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiAsFileAttachmentTest {

  @get:Rule val recipients = RecipientTestRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  @Test
  fun `the flag from the pointer is stored with the not-yet-downloaded attachment`() {
    val (_, attachmentId) = receive(asFile = true)

    val stored = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(stored.transformProperties!!.tellomiAsFile).isTrue()
    assertThat(MediaUtil.getSlideForAttachment(stored)).isInstanceOf(DocumentSlide::class)
  }

  @Test
  fun `the download keeps the flag`() {
    val (messageId, attachmentId) = receive(asFile = true)

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageId, attachmentId, ByteArrayInputStream(Random.nextBytes(2048)))

    val downloaded = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(downloaded.transferState).isEqualTo(AttachmentTable.TRANSFER_PROGRESS_DONE)
    assertThat(downloaded.transformProperties!!.tellomiAsFile).isTrue()
    assertThat(downloaded.transformProperties!!.skipTransform).isTrue()
  }

  @Test
  fun `an ordinary image stays an image and its JSON stays upstream's`() {
    val (messageId, attachmentId) = receive(asFile = false)

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageId, attachmentId, ByteArrayInputStream(Random.nextBytes(2048)))

    val downloaded = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(downloaded.transformProperties!!.tellomiAsFile).isFalse()
    assertThat(downloaded.transformProperties!!.serialize().contains("tellomiAsFile")).isFalse()
    assertThat(MediaUtil.getSlideForAttachment(downloaded)).isInstanceOf(ImageSlide::class)
  }

  @Test
  fun `media, files and links lists it under files, not photos`() {
    val (asFileMessage, asFileAttachment) = receive(asFile = true)
    val (photoMessage, photoAttachment) = receive(asFile = false, threadOf = asFileMessage)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(asFileMessage, asFileAttachment, ByteArrayInputStream(Random.nextBytes(2048)))
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(photoMessage, photoAttachment, ByteArrayInputStream(Random.nextBytes(2048)))
    val threadId = SignalDatabase.messages.getThreadIdForMessage(asFileMessage)

    val photos = SignalDatabase.media.getGalleryMediaForThread(threadId, MediaTable.Sorting.Newest).attachmentIds()
    val files = SignalDatabase.media.getDocumentMediaForThread(threadId, MediaTable.Sorting.Newest).attachmentIds()

    assertThat(photos).isEqualTo(listOf(photoAttachment))
    assertThat(files).isEqualTo(listOf(asFileAttachment))
  }

  private fun receive(asFile: Boolean, threadOf: Long? = null): Pair<Long, AttachmentId> {
    val threadId = threadOf?.let { SignalDatabase.messages.getThreadIdForMessage(it) }
      ?: SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipients.createRecipient("Some Contact")))
    val from = SignalDatabase.threads.getRecipientIdForThreadId(threadId)!!

    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      body = null,
      sentTimeMillis = System.nanoTime(),
      serverTimeMillis = 100L,
      receivedTimeMillis = 200L,
      attachments = listOf(pointer(asFile))
    )

    val messageId = SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
    return messageId to SignalDatabase.attachments.getAttachmentsForMessage(messageId).first().attachmentId
  }

  private fun pointer(asFile: Boolean): Attachment {
    return PointerAttachment(
      contentType = "image/jpeg",
      transferState = AttachmentTable.TRANSFER_PROGRESS_PENDING,
      size = 2048,
      fileName = "IMG_0001.JPG",
      cdn = Cdn.CDN_3,
      location = "cdn-key-${Random.nextInt()}",
      key = Base64.encodeWithPadding(Random.nextBytes(64)),
      iv = null,
      digest = Random.nextBytes(32),
      incrementalDigest = null,
      incrementalMacChunkSize = 0,
      fastPreflightId = null,
      voiceNote = false,
      borderless = false,
      videoGif = false,
      width = 4032,
      height = 3024,
      uploadTimestamp = 0,
      caption = null,
      stickerLocator = null,
      blurHash = null,
      uuid = null,
      quote = false,
      transformProperties = if (asFile) TransformProperties(tellomiAsFile = true) else null
    )
  }

  private fun Cursor.attachmentIds(): List<AttachmentId> {
    return use {
      buildList {
        while (it.moveToNext()) {
          add(AttachmentId(it.getLong(it.getColumnIndexOrThrow(AttachmentTable.ID))))
        }
      }
    }
  }
}
