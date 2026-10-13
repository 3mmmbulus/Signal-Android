/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.TellomiSpoiler
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Tellomi (tellomi/tellomi#1263, spec R-1, ADR-0068 §4.1): an image received with `AttachmentPointer.tellomi.spoiler` stays
 * covered through the download until this device uncovers it, and the reveal is remembered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiSpoilerAttachmentTest {

  @get:Rule val recipients = RecipientTestRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  @Test
  fun `the cover is stored with the attachment and kept by the download`() {
    val (messageId, attachmentId) = receive(spoiler = true)
    assertThat(TellomiSpoiler.isCovered(SignalDatabase.attachments.getAttachment(attachmentId))).isTrue()

    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageId, attachmentId, ByteArrayInputStream(Random.nextBytes(2048)))

    val downloaded = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(downloaded.transferState).isEqualTo(AttachmentTable.TRANSFER_PROGRESS_DONE)
    assertThat(downloaded.transformProperties!!.tellomiSpoiler).isTrue()
    assertThat(TellomiSpoiler.isCovered(downloaded)).isTrue()
  }

  @Test
  fun `uncovering is remembered on this device and survives the download`() {
    val (messageId, attachmentId) = receive(spoiler = true)

    SignalDatabase.attachments.setTellomiSpoilerRevealed(attachmentId)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(messageId, attachmentId, ByteArrayInputStream(Random.nextBytes(2048)))

    val downloaded = SignalDatabase.attachments.getAttachment(attachmentId)!!
    assertThat(downloaded.transformProperties!!.tellomiSpoiler).isTrue()
    assertThat(downloaded.transformProperties!!.tellomiSpoilerRevealed).isTrue()
    assertThat(TellomiSpoiler.isCovered(downloaded)).isFalse()
  }

  @Test
  fun `uncovering something that was never covered changes nothing`() {
    val (_, attachmentId) = receive(spoiler = false)

    SignalDatabase.attachments.setTellomiSpoilerRevealed(attachmentId)

    val stored = SignalDatabase.attachments.getAttachment(attachmentId)!!.transformProperties
    assertThat(stored?.tellomiSpoiler == true).isFalse()
    assertThat(stored?.tellomiSpoilerRevealed == true).isFalse()
  }

  @Test
  fun `the chat list snippet has no thumbnail for a covered image`() {
    val (coveredMessage, coveredAttachment) = receive(spoiler = true)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(coveredMessage, coveredAttachment, ByteArrayInputStream(Random.nextBytes(2048)))
    val threadId = SignalDatabase.messages.getThreadIdForMessage(coveredMessage)
    SignalDatabase.threads.update(threadId, unarchive = false)
    assertThat(SignalDatabase.threads.getThreadRecord(threadId)!!.snippetUri).isNull()

    val (plainMessage, plainAttachment) = receive(spoiler = false, threadOf = coveredMessage)
    SignalDatabase.attachments.finalizeAttachmentAfterDownload(plainMessage, plainAttachment, ByteArrayInputStream(Random.nextBytes(2048)))
    SignalDatabase.threads.update(threadId, unarchive = false)
    assertThat(SignalDatabase.threads.getThreadRecord(threadId)!!.snippetUri).isNotNull()
  }

  private fun receive(spoiler: Boolean, threadOf: Long? = null): Pair<Long, AttachmentId> {
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
      attachments = listOf(pointer(spoiler))
    )

    val messageId = SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
    return messageId to SignalDatabase.attachments.getAttachmentsForMessage(messageId).first().attachmentId
  }

  private fun pointer(spoiler: Boolean): Attachment {
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
      transformProperties = if (spoiler) TransformProperties(tellomiSpoiler = true) else null
    )
  }
}
