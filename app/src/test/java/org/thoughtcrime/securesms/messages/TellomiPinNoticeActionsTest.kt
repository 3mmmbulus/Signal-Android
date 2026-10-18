/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.ReactionMenu
import org.thoughtcrime.securesms.conversation.v2.ConversationRepository
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.PinnedMessage
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.util.LocaleUtil
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import org.thoughtcrime.securesms.util.RemoteConfig
import org.thoughtcrime.securesms.util.isPinNoticeDeletedForEveryone
import org.thoughtcrime.securesms.util.isValidReactionTarget
import org.thoughtcrime.securesms.util.pulsesAsOutgoing
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import java.util.Locale

/**
 * Tellomi (ADR-0081 §4.2, pinned-messages.md 3.7): "×× pinned «…»" can be reacted to, replied to and deleted — nothing else —
 * as in both Telegram apps. Its identity is (who pinned, when the pin was sent): the row's sender and sent time. Real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiPinNoticeActionsTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var alice: Recipient
  private var threadId = 0L
  private var clock = 1_790_000_000_000L

  @Before
  fun setUp() {
    every { RemoteConfig.pinLimit } returns 3
    // Read once, when MessageConstraintsUtil first loads.
    every { RemoteConfig.regularDeleteThreshold } returns 86_400L
    every { RemoteConfig.adminDeleteThreshold } returns 86_400L
    // A conversation message formats its time with the app's language setting.
    mockkObject(LocaleUtil)
    every { LocaleUtil.getFirstLocale() } returns Locale.US
    every { AppDependencies.jobManager } returns mockk(relaxed = true)
    // The long-press menu asks the labs settings about starring.
    every { SignalStore.labs } returns mockk(relaxed = true)
    alice = Recipient.resolved(recipients.createRecipient("Alice"))
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(alice)
  }

  @After
  fun tearDown() {
    unmockkObject(LocaleUtil)
  }

  @Test
  fun `a pin notice takes reactions, other updates still don't`() {
    val pin = record(insertPinFromAlice(insertFromAlice("Lunch at noon")))
    assertTrue(pin.isValidReactionTarget())

    val timerUpdate = record(insertTimerUpdateFromAlice())
    assertFalse(timerUpdate.isValidReactionTarget())
  }

  @Test
  fun `the long-press menu on a pin notice is reply and delete, with the emoji strip`() {
    val pin = record(insertPinFromAlice(insertFromAlice("Lunch at noon")))

    val menu = ReactionMenu.of(
      context = context,
      conversationRecipient = alice,
      conversationMessage = ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(context, pin, alice),
      isNonAdminInAnnouncementGroup = false,
      canEditGroupInfo = true,
      onAction = {}
    )

    assertEquals(listOf("Reply", "Delete"), menu.items.map { it.title.toString() })
    assertTrue(menu.canReact)
  }

  @Test
  fun `I can delete my own pin notice for everyone, not someone else's`() {
    val target = insertFromAlice("See you there")
    val mine = insertMyPin(target)
    SignalDatabase.messages.markAsSent(mine, true)
    val minePin = record(mine)
    assertTrue(MessageConstraintsUtil.isValidRemoteDeleteSend(minePin, minePin.dateSent + 1_000))
    assertFalse("after the limit", MessageConstraintsUtil.isValidRemoteDeleteSend(minePin, minePin.dateSent + 2 * 86_400_000L))

    val hers = record(insertPinFromAlice(target))
    assertFalse(MessageConstraintsUtil.isValidRemoteDeleteSend(hers, hers.dateSent + 1_000))
  }

  @Test
  fun `a reply to a pin notice quotes this app's own line for it`() {
    val target = insertFromAlice("Lunch at noon")
    val herPin = record(insertPinFromAlice(target))

    val quote = DataMessageProcessor.getValidatedQuote(
      context,
      ++clock,
      DataMessage(quote = DataMessage.Quote(id = herPin.dateSent, authorAci = alice.requireAci().toString(), text = "Alice hat «Lunch at noon» angeheftet")),
      alice,
      alice
    )

    assertNotNull(quote)
    assertFalse(quote!!.isOriginalMissing)
    assertEquals("Alice pinned “Lunch at noon”", quote.text)
  }

  @Test
  fun `deleting a pin notice for everyone removes it and the pin stays`() {
    val target = insertFromAlice("Lunch at noon")
    SignalDatabase.messages.tellomiPinForMe(target, threadId, MessageTable.PIN_FOREVER, ++clock)
    val herPin = record(insertPinFromAlice(target))

    val deleted = DataMessageProcessor.handleRemoteDelete(
      context,
      Envelope(clientTimestamp = ++clock, serverTimestamp = clock),
      DataMessage(delete = DataMessage.Delete(targetSentTimestamp = herPin.dateSent)),
      alice.id,
      null,
      OneTimeBatchCache()
    )

    assertNotNull(deleted)
    assertNull("the notice is gone", SignalDatabase.messages.getMessageRecordOrNull(herPin.id))
    assertTrue("the message is still pinned", SignalDatabase.messages.getMessageRecord(target).pinnedUntil > 0)
  }

  @Test
  fun `my reply quotes the pin notice in the third person`() {
    val target = insertFromAlice("See you there")
    val mine = record(insertMyPin(target))
    val (_, body) = ConversationRepository(context, false)
      .getSlideDeckAndBodyForReply(context, ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(context, mine, alice))

    assertEquals("${Recipient.self().getDisplayName(context)} pinned “See you there”", body.toString())
  }

  @Test
  fun `my pin notice deleted for everyone leaves the chat at once, a message shows it was deleted`() {
    val mine = insertMyPin(insertFromAlice("See you there"))
    SignalDatabase.messages.markAsSent(mine, true)
    assertFalse(record(mine).isPinNoticeDeletedForEveryone())

    MessageSender.sendRemoteDelete(mine)
    assertTrue("gone before the delete is out", record(mine).isPinNoticeDeletedForEveryone())

    val myMessage = insertMyMessage("On my way")
    SignalDatabase.messages.markAsSent(myMessage, true)
    MessageSender.sendRemoteDelete(myMessage)
    assertTrue(record(myMessage).isRemoteDelete)
    assertFalse("still there, as deleted", record(myMessage).isPinNoticeDeletedForEveryone())
  }

  @Test
  fun `a jump to my pin notice pulses as lightly as over an incoming bubble`() {
    val target = insertFromAlice("See you there")
    val minePin = record(insertMyPin(target))
    assertTrue(minePin.isOutgoing)
    assertFalse("it sits on the chat's background", minePin.pulsesAsOutgoing())

    val myMessage = record(insertMyMessage("On my way"))
    assertTrue("my bubbles still pulse darker", myMessage.pulsesAsOutgoing())
    assertFalse(record(target).pulsesAsOutgoing())
  }

  // MARK: Helpers

  private fun record(id: Long): MessageRecord = SignalDatabase.messages.getMessageRecord(id)

  private fun insertFromAlice(body: String): Long {
    val sent = ++clock
    val message = IncomingMessage(type = MessageType.NORMAL, from = alice.id, sentTimeMillis = sent, serverTimeMillis = sent, receivedTimeMillis = sent, body = body)
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun insertTimerUpdateFromAlice(): Long {
    val sent = ++clock
    val message = IncomingMessage(type = MessageType.EXPIRATION_UPDATE, from = alice.id, sentTimeMillis = sent, serverTimeMillis = sent, receivedTimeMillis = sent, expiresIn = 3_600_000L)
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun pinned(pinnedMessageId: Long) = MessageExtras(
    pinnedMessage = PinnedMessage(pinnedMessageId = pinnedMessageId, targetAuthorAci = ByteString.EMPTY, targetTimestamp = 1, pinDurationInSeconds = MessageTable.PIN_FOREVER)
  )

  private fun insertPinFromAlice(pinnedMessageId: Long): Long {
    val sent = ++clock
    val message = IncomingMessage(
      type = MessageType.PINNED_MESSAGE,
      from = alice.id,
      sentTimeMillis = sent,
      serverTimeMillis = sent,
      receivedTimeMillis = sent,
      messageExtras = pinned(pinnedMessageId)
    )
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun insertMyMessage(body: String): Long {
    val message = OutgoingMessage.text(threadRecipient = alice, body = body, expiresIn = 0, sentTimeMillis = ++clock)
    return SignalDatabase.messages.insertMessageOutbox(message, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null).messageId
  }

  private fun insertMyPin(pinnedMessageId: Long): Long {
    val pin = OutgoingMessage.pinMessage(threadRecipient = alice, sentTimeMillis = ++clock, expiresIn = 0, messageExtras = pinned(pinnedMessageId))
    return SignalDatabase.messages.insertMessageOutbox(pin, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null).messageId
  }
}
