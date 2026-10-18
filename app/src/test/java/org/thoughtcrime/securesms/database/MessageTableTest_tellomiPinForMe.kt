/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.mockk.every
import okio.ByteString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.PinnedMessage
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * Tellomi (ADR-0081 §4.3): a pin "for me only" lives in the ordinary pin columns, told apart by
 * [MessageTable.TELLOMI_PIN_FOR_ME] in place of the id of a "pinned a message" update.
 */
@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class MessageTableTest_tellomiPinForMe {

  @get:Rule
  val recipients = RecipientTestRule()

  private val messages: MessageTable
    get() = SignalDatabase.messages

  private lateinit var senderId: RecipientId
  private var threadId: Long = 0

  @Before
  fun setUp() {
    // A pin keeps to the pin limit, which comes from remote config; the rule only spies on RemoteConfig.
    every { RemoteConfig.pinLimit } returns 3
    senderId = recipients.createRecipient("Sender Name")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(senderId, false, ThreadTable.DistributionTypes.DEFAULT)
  }

  @Test
  fun aPinForMeIsAPinThatIsMineOnly() {
    val id = insertIncoming(time = 1000)

    messages.tellomiPinForMe(id, threadId, MessageTable.PIN_FOREVER, pinnedAt = 5000)

    assertThat(messages.getPinnedMessages(threadId, orderByPinned = true).map { it.id }).containsExactly(id)
    assertThat(messages.getMessageRecord(id).pinnedUntil).isEqualTo(MessageTable.PIN_FOREVER)
    assertThat(messages.tellomiIsPinnedForMe(id)).isTrue()
    assertThat(messages.tellomiPinnedForMeIds(threadId)).isEqualTo(setOf(id))
  }

  @Test
  fun aTimedPinForMeRunsFromWhenItWasPinned() {
    val id = insertIncoming(time = 1000)

    messages.tellomiPinForMe(id, threadId, pinDurationInSeconds = 3600, pinnedAt = 5000)

    assertThat(messages.getMessageRecord(id).pinnedUntil).isEqualTo(5000 + 3_600_000L)
  }

  @Test
  fun unpinningTakesItAway() {
    val id = insertIncoming(time = 1000)
    messages.tellomiPinForMe(id, threadId, MessageTable.PIN_FOREVER, pinnedAt = 5000)

    messages.unpinMessage(messageId = id, threadId = threadId)

    assertThat(messages.tellomiIsPinnedForMe(id)).isFalse()
    assertThat(messages.tellomiPinnedForMeIds(threadId)).isEmpty()
  }

  @Test
  fun onlyThisChatsPinsForMeAreListed() {
    val otherId = recipients.createRecipient("Other Name")
    val otherThreadId = SignalDatabase.threads.getOrCreateThreadIdFor(otherId, false, ThreadTable.DistributionTypes.DEFAULT)
    val here = insertIncoming(time = 1000)
    val there = insertIncoming(time = 1001, from = otherId, thread = otherThreadId)

    messages.tellomiPinForMe(here, threadId, MessageTable.PIN_FOREVER, pinnedAt = 5000)
    messages.tellomiPinForMe(there, otherThreadId, MessageTable.PIN_FOREVER, pinnedAt = 5001)

    assertThat(messages.tellomiPinnedForMeIds(threadId)).isEqualTo(setOf(here))
  }

  @Test
  fun anOrdinaryPinOfTheSameMessageMakesItEveryones() {
    val id = insertIncoming(time = 1000)
    messages.tellomiPinForMe(id, threadId, MessageTable.PIN_FOREVER, pinnedAt = 5000)

    insertOrdinaryPin(targetId = id, targetTimestamp = 1000, time = 6000)

    assertThat(messages.getPinnedMessages(threadId, orderByPinned = true).map { it.id }).containsExactly(id)
    assertThat(messages.tellomiIsPinnedForMe(id)).isFalse()
    assertThat(messages.tellomiPinnedForMeIds(threadId)).isEmpty()
  }

  private fun insertIncoming(time: Long, from: RecipientId = senderId, thread: Long = threadId): Long {
    val message = IncomingMessage(
      type = MessageType.NORMAL,
      from = from,
      sentTimeMillis = time,
      serverTimeMillis = time,
      receivedTimeMillis = time,
      body = "msg $time"
    )
    return messages.insertMessageInbox(message, thread).get().messageId
  }

  /** The "pinned a message" update an ordinary pin leaves behind, as a synced pin from my other device inserts it. */
  private fun insertOrdinaryPin(targetId: Long, targetTimestamp: Long, time: Long) {
    val pin = OutgoingMessage.pinMessage(
      threadRecipient = Recipient.resolved(senderId),
      sentTimeMillis = time,
      expiresIn = 0,
      messageExtras = MessageExtras(
        pinnedMessage = PinnedMessage(
          pinnedMessageId = targetId,
          targetAuthorAci = ByteString.EMPTY,
          targetTimestamp = targetTimestamp,
          pinDurationInSeconds = MessageTable.PIN_FOREVER
        )
      )
    )
    messages.insertMessageOutbox(pin, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null)
  }
}
