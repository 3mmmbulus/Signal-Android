/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.ACI
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.TellomiDataMessage
import java.util.UUID

/**
 * ADR-0081 §4.3 / §七 3: a pin "for me only" never leaves its sender's own devices, so one that reaches someone else is dropped
 * whole — no pin, no "pinned a message" — and someone else's unpin never takes away my own pin for me. Drives the real
 * `DataMessageProcessor.process` with a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiPinForSelfOnlyReceiveTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private lateinit var alice: Recipient
  private var threadId = 0L
  private var clock = 1_790_000_000_000L

  @Before
  fun setUp() {
    alice = Recipient.resolved(recipients.createRecipient("Alice"))
    every { AppDependencies.jobManager } returns mockk(relaxed = true)
    every { SignalStore.remoteConfig } returns mockk(relaxed = true)
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(alice)
  }

  @Test
  fun `her pin for herself only that reaches me is dropped`() {
    val target = insertFromAlice()

    receive(DataMessage(pinMessage = pinOf(target), tellomi = TellomiDataMessage(pinForSelfOnly = true)))

    assertEquals("not pinned", 0L, SignalDatabase.messages.getMessageRecord(target.id).pinnedUntil)
    assertEquals("no \"pinned a message\"", 1, SignalDatabase.messages.getMessageCountForThread(threadId))
  }

  @Test
  fun `the same pin without the flag is an ordinary pin`() {
    val target = insertFromAlice()

    receive(DataMessage(pinMessage = pinOf(target)))

    assertEquals(MessageTable.PIN_FOREVER, SignalDatabase.messages.getMessageRecord(target.id).pinnedUntil)
    assertEquals("plus \"pinned a message\"", 2, SignalDatabase.messages.getMessageCountForThread(threadId))
  }

  @Test
  fun `her unpin for herself only is dropped too`() {
    val target = insertFromAlice()
    receive(DataMessage(pinMessage = pinOf(target)))

    receive(DataMessage(unpinMessage = DataMessage.UnpinMessage(targetAuthorAciBinary = alice.requireAci().toByteString(), targetSentTimestamp = target.dateSent), tellomi = TellomiDataMessage(pinForSelfOnly = true)))

    assertEquals("still pinned for both", MessageTable.PIN_FOREVER, SignalDatabase.messages.getMessageRecord(target.id).pinnedUntil)
  }

  @Test
  fun `her unpin does not take back my pin for me`() {
    val target = insertFromAlice()
    SignalDatabase.messages.tellomiPinForMe(target.id, threadId, MessageTable.PIN_FOREVER, pinnedAt = clock)

    receive(DataMessage(unpinMessage = DataMessage.UnpinMessage(targetAuthorAciBinary = alice.requireAci().toByteString(), targetSentTimestamp = target.dateSent)))

    assertTrue(SignalDatabase.messages.tellomiIsPinnedForMe(target.id))
  }

  private class Target(val id: Long, val dateSent: Long)

  private fun insertFromAlice(): Target {
    val sent = ++clock
    val message = IncomingMessage(type = MessageType.NORMAL, from = alice.id, sentTimeMillis = sent, serverTimeMillis = sent, receivedTimeMillis = sent, body = "hi $sent")
    return Target(SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId, sent)
  }

  private fun pinOf(target: Target) = DataMessage.PinMessage(
    targetAuthorAciBinary = alice.requireAci().toByteString(),
    targetSentTimestamp = target.dateSent,
    pinDurationForever = true
  )

  private fun receive(message: DataMessage) {
    val timestamp = ++clock
    DataMessageProcessor.process(
      context = ApplicationProvider.getApplicationContext(),
      senderRecipient = alice,
      threadRecipient = alice,
      envelope = Envelope(clientTimestamp = timestamp, serverTimestamp = timestamp + 1),
      content = Content(dataMessage = message.copy(timestamp = timestamp)),
      metadata = EnvelopeMetadata(alice.requireAci(), null, 1, true, null, ACI.from(UUID.randomUUID()), 0),
      receivedTime = timestamp + 2,
      earlyMessageCacheEntry = null,
      localMetrics = null,
      batchCache = OneTimeBatchCache()
    )
  }
}
