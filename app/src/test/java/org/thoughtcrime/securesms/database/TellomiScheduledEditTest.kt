/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.linkpreview.LinkPreview
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import java.util.Optional

/**
 * tellomi/tellomi#1611 (M-4, like Telegram on both clients): a scheduled message that hasn't gone out can be edited in place —
 * text, styles, mentions and link preview change; the scheduled time stays. Once it has been claimed for sending
 * (`clearScheduledStatus`) the edit is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiScheduledEditTest {

  @get:Rule val recipients = RecipientTestRule()

  companion object {
    @BeforeClass
    @JvmStatic
    fun setUpClass() {
      Log.initialize(SystemOutLogger())
    }
  }

  private val messages: MessageTable
    get() = SignalDatabase.messages

  private lateinit var recipientId: RecipientId
  private var threadId: Long = 0
  private val sendAt = System.currentTimeMillis() + 3_600_000

  @Before
  fun setUp() {
    recipientId = recipients.createRecipient("Recipient Name")
    threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipientId, false, ThreadTable.DistributionTypes.DEFAULT)
  }

  @Test
  fun `the text, styles and mentions change and the time stays`() {
    val messageId = insertScheduled("original")
    val ranges = BodyRangeList(ranges = listOf(BodyRangeList.BodyRange(start = 0, length = 4, style = BodyRangeList.BodyRange.Style.BOLD)))
    val mention = Mention(recipientId, 5, 1)

    val edited = messages.tellomiEditScheduledMessage(threadId, messageId, "bold ￼", ranges, listOf(mention), linkPreviews = null)

    assertThat(edited).isTrue()
    val record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.body).isEqualTo("bold ￼")
    assertThat(record.messageRanges).isEqualTo(ranges)
    assertThat(record.scheduledDate).isEqualTo(sendAt)
    assertThat(SignalDatabase.mentions.getMentionsForMessage(messageId)).isEqualTo(listOf(mention))
  }

  /** Like [MessageTable.insertMessageOutbox]: the editor's "@Name" is stored as a placeholder, and styles after it move with it. */
  @Test
  fun `a mention typed as a name is stored as a placeholder`() {
    val messageId = insertScheduled("original")
    val body = "hi @Recipient Name ok"
    val mention = Mention(recipientId, 3, "@Recipient Name".length)
    val okStart = body.indexOf("ok")
    val ranges = BodyRangeList(ranges = listOf(BodyRangeList.BodyRange(start = okStart, length = 2, style = BodyRangeList.BodyRange.Style.BOLD)))

    messages.tellomiEditScheduledMessage(threadId, messageId, body, ranges, listOf(mention), linkPreviews = null)

    val record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.body).isEqualTo("hi \uFFFC ok")
    assertThat(SignalDatabase.mentions.getMentionsForMessage(messageId)).isEqualTo(listOf(Mention(recipientId, 3, 1)))
    assertThat(record.messageRanges?.ranges?.first()?.start).isEqualTo(5)
  }

  @Test
  fun `styles and mentions can be removed`() {
    val messageId = insertScheduled("original")
    messages.tellomiEditScheduledMessage(threadId, messageId, "bold ￼", BodyRangeList(ranges = listOf(BodyRangeList.BodyRange(start = 0, length = 4, style = BodyRangeList.BodyRange.Style.BOLD))), listOf(Mention(recipientId, 5, 1)), linkPreviews = null)

    messages.tellomiEditScheduledMessage(threadId, messageId, "plain", null, emptyList(), linkPreviews = null)

    val record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.body).isEqualTo("plain")
    assertThat(record.messageRanges).isNull()
    assertThat(SignalDatabase.mentions.getMentionsForMessage(messageId)).isEmpty()
  }

  @Test
  fun `the link preview is replaced, kept or removed`() {
    val messageId = insertScheduled("https://example.com")
    val preview = LinkPreview("https://example.org", "Example", "A site", 0, Optional.empty())

    messages.tellomiEditScheduledMessage(threadId, messageId, "https://example.org", null, emptyList(), listOf(preview))
    var record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.linkPreviews.map { it.url }).isEqualTo(listOf("https://example.org"))
    assertThat(record.linkPreviews.first().title).isEqualTo("Example")

    // null = leave it as it is
    messages.tellomiEditScheduledMessage(threadId, messageId, "see https://example.org", null, emptyList(), linkPreviews = null)
    record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.linkPreviews.map { it.url }).isEqualTo(listOf("https://example.org"))

    // empty = remove it
    messages.tellomiEditScheduledMessage(threadId, messageId, "no preview", null, emptyList(), emptyList())
    record = messages.getMessageRecord(messageId) as MmsMessageRecord
    assertThat(record.linkPreviews).isEmpty()
  }

  @Test
  fun `a message already claimed for sending can't be edited`() {
    val messageId = insertScheduled("original")
    assertThat(messages.clearScheduledStatus(threadId, messageId, 0)).isTrue()

    val edited = messages.tellomiEditScheduledMessage(threadId, messageId, "too late", null, emptyList(), linkPreviews = null)

    assertThat(edited).isFalse()
    assertThat(messages.getMessageRecord(messageId).body).isEqualTo("original")
  }

  private fun insertScheduled(body: String): Long {
    val message = OutgoingMessage.text(
      threadRecipient = Recipient.resolved(recipientId),
      body = body,
      expiresIn = 0,
      sentTimeMillis = System.currentTimeMillis()
    ).sendAt(sendAt)
    return messages.insertMessageOutbox(message, threadId).messageId
  }
}
