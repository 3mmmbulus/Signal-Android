/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.model.databaseprotos.AdminDeleteStatus
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.TellomiMessageExtras
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.thoughtcrime.securesms.util.TellomiCaptionAbove
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.TellomiDataMessage

/**
 * ADR-0068 §4.7 (tellomi/tellomi#1263): "caption above media" arrives as `DataMessage.tellomi.captionAboveMedia`, is kept in
 * the message's `message_extras` (outside upstream's oneof, no new column), and goes back out from there when the message
 * is sent or resent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiCaptionAboveTest {

  @get:Rule val recipients = RecipientTestRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  @Test
  fun `the flag on a received message is what is kept`() {
    val above = TellomiCaptionAbove.extrasFrom(DataMessage(body = "caption", tellomi = TellomiDataMessage(captionAboveMedia = true)))
    val ordinary = TellomiCaptionAbove.extrasFrom(DataMessage(body = "caption"))
    val no = TellomiCaptionAbove.extrasFrom(DataMessage(body = "caption", tellomi = TellomiDataMessage(captionAboveMedia = false)))

    assertThat(TellomiCaptionAbove.isCaptionAboveMedia(above)).isTrue()
    assertThat(ordinary).isNull()
    assertThat(no).isNull()
  }

  @Test
  fun `a received message keeps it in its extras`() {
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(recipients.createRecipient("Some Contact")))
    val from = SignalDatabase.threads.getRecipientIdForThreadId(threadId)!!

    val above = receive(threadId, from, TellomiCaptionAbove.extras(true))
    val ordinary = receive(threadId, from, TellomiCaptionAbove.extras(false))

    assertThat(TellomiCaptionAbove.isCaptionAboveMedia(SignalDatabase.messages.getMessageRecord(above).messageExtras)).isTrue()
    assertThat(SignalDatabase.messages.getMessageRecord(ordinary).messageExtras).isNull()
  }

  @Test
  fun `a sent message goes out with it, and with nothing else from its extras`() {
    val recipient = Recipient.resolved(recipients.createRecipient("Some Contact"))
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val extras = MessageExtras(
      adminDeleteStatus = AdminDeleteStatus(status = AdminDeleteStatus.Status.PENDING),
      tellomi = TellomiMessageExtras(captionAboveMedia = true)
    )
    val messageId = SignalDatabase.messages.insertMessageOutbox(
      OutgoingMessage(threadRecipient = recipient, sentTimeMillis = System.currentTimeMillis(), body = "caption", isSecure = true, messageExtras = extras),
      threadId
    ).messageId

    val reloaded = SignalDatabase.messages.getOutgoingMessage(messageId)

    assertThat(reloaded.messageExtras).isEqualTo(MessageExtras(tellomi = TellomiMessageExtras(captionAboveMedia = true)))
    assertThat(TellomiCaptionAbove.isCaptionAboveMedia(reloaded.messageExtras)).isTrue()
  }

  @Test
  fun `an ordinary sent message has no extras, as upstream`() {
    val recipient = Recipient.resolved(recipients.createRecipient("Some Contact"))
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId = SignalDatabase.messages.insertMessageOutbox(
      OutgoingMessage(threadRecipient = recipient, sentTimeMillis = System.currentTimeMillis(), body = "caption", isSecure = true),
      threadId
    ).messageId

    val reloaded = SignalDatabase.messages.getOutgoingMessage(messageId)

    assertThat(reloaded.messageExtras).isNull()
    assertThat(TellomiCaptionAbove.isCaptionAboveMedia(reloaded.messageExtras)).isFalse()
  }

  private fun receive(threadId: Long, from: RecipientId, extras: MessageExtras?): Long {
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      body = "caption",
      sentTimeMillis = System.nanoTime(),
      serverTimeMillis = 100L,
      receivedTimeMillis = 200L,
      messageExtras = extras
    )
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }
}
