/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.database.model.databaseprotos.MessageExtras
import org.thoughtcrime.securesms.database.model.databaseprotos.PinnedMessage
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule

/**
 * Tellomi (ADR-0081 §4.4, pinned-messages.md 3.2): "×× pinned «the first 15 characters…»" — in the chat, the chat list and
 * notifications — and when it is pushed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiPinNoticeTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  private lateinit var alice: RecipientId
  private var threadId = 0L
  private var clock = 1_790_000_000_000L

  @Before
  fun setUp() {
    alice = recipients.createRecipient("Alice")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(alice, false, ThreadTable.DistributionTypes.DEFAULT)
  }

  // MARK: The preview

  @Test
  fun `short text is quoted whole, on one line`() {
    assertEquals("Lunch at noon", TellomiPinNotice.clip("Lunch at noon"))
    assertEquals("Line two", TellomiPinNotice.clip("Line\n\ntwo"))
    assertEquals("Lunch at noon t…", TellomiPinNotice.clip("Lunch at noon\ntomorrow"))
  }

  @Test
  fun `more than 15 characters are cut to 15 with an ellipsis`() {
    assertEquals("123456789012345", TellomiPinNotice.clip("123456789012345"))
    assertEquals("123456789012345…", TellomiPinNotice.clip("1234567890123456"))
    assertEquals("明天中午十二点在公司楼下的餐厅…", TellomiPinNotice.clip("明天中午十二点在公司楼下的餐厅一起吃饭"))
  }

  @Test
  fun `an emoji is one character and never split`() {
    val family = "👨‍👩‍👧"
    assertEquals("12345678901234$family…", TellomiPinNotice.clip("12345678901234${family}tail"))
  }

  // MARK: The sentence

  @Test
  fun `someone else pinning text`() {
    val target = insertFromAlice("Hello pinned world, this is long")

    val pin = insertPinFromAlice(pinnedMessageId = target)

    assertEquals("Alice pinned “Hello pinned wo…”", describe(pin))
  }

  @Test
  fun `my own pin`() {
    val target = insertFromAlice("See you there")

    val pin = insertMyPin(pinnedMessageId = target)

    assertEquals("You pinned “See you there”", describe(pin))
  }

  @Test
  fun `a pin whose message is gone`() {
    val pin = insertPinFromAlice(pinnedMessageId = -1)

    assertEquals("Alice pinned a message", describe(pin))
  }

  // MARK: Pushed or not

  @Test
  fun `only a group pin with notifying is pushed`() {
    val notifying = MessageExtras(pinnedMessage = PinnedMessage(pinnedMessageId = 1))
    val silent = MessageExtras(pinnedMessage = PinnedMessage(pinnedMessageId = 1, tellomiSilent = true))

    assertTrue(TellomiPinNotice.notifies(notifying, isGroup = true))
    assertFalse("pinned without notifying", TellomiPinNotice.notifies(silent, isGroup = true))
    assertFalse("a 1:1 pin is never pushed", TellomiPinNotice.notifies(notifying, isGroup = false))
  }

  @Test
  fun `a 1-1 pin from someone else is unread but not pushed`() {
    val target = insertFromAlice("Unread but quiet")

    val pin = insertPinFromAlice(pinnedMessageId = target)

    assertEquals("her message and her pin", 2, SignalDatabase.messages.getUnreadCount(threadId))
    recipients.signalDatabase.readableDatabase.rawQuery("SELECT ${MessageTable.NOTIFIED} FROM ${MessageTable.TABLE_NAME} WHERE ${MessageTable.ID} = $pin", null).use {
      assertTrue(it.moveToFirst())
      assertEquals("already counted as notified", 1, it.getInt(0))
    }
  }

  // MARK: Helpers

  private fun describe(pinId: Long): String = TellomiPinNotice.describe(context, SignalDatabase.messages.getMessageRecord(pinId))

  private fun insertFromAlice(body: String): Long {
    val sent = ++clock
    val message = IncomingMessage(type = MessageType.NORMAL, from = alice, sentTimeMillis = sent, serverTimeMillis = sent, receivedTimeMillis = sent, body = body)
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun pinned(pinnedMessageId: Long) = MessageExtras(
    pinnedMessage = PinnedMessage(pinnedMessageId = pinnedMessageId, targetAuthorAci = ByteString.EMPTY, targetTimestamp = 1, pinDurationInSeconds = MessageTable.PIN_FOREVER)
  )

  private fun insertPinFromAlice(pinnedMessageId: Long): Long {
    val sent = ++clock
    val message = IncomingMessage(
      type = MessageType.PINNED_MESSAGE,
      from = alice,
      sentTimeMillis = sent,
      serverTimeMillis = sent,
      receivedTimeMillis = sent,
      messageExtras = pinned(pinnedMessageId)
    )
    return SignalDatabase.messages.insertMessageInbox(message, threadId).get().messageId
  }

  private fun insertMyPin(pinnedMessageId: Long): Long {
    val pin = OutgoingMessage.pinMessage(threadRecipient = Recipient.resolved(alice), sentTimeMillis = ++clock, expiresIn = 0, messageExtras = pinned(pinnedMessageId))
    return SignalDatabase.messages.insertMessageOutbox(pin, threadId, false, GroupReceiptTable.STATUS_UNKNOWN, null).messageId
  }
}
