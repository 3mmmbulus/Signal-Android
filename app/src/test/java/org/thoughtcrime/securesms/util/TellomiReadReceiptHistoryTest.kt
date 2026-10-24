/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.TestMms
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.testutil.SignalDatabaseRule
import org.thoughtcrime.securesms.util.TellomiReadReceiptHistory.Event
import org.thoughtcrime.securesms.util.TellomiReadReceiptHistory.History

/**
 * Tellomi（tellomi/tellomi#1183，需求 message-status-and-read-receipts §3.4 第 3 条、判据 6）：
 * 到达时关着已读回执的消息，之后打开开关再读也不发已读回执。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TellomiReadReceiptHistoryTest {

  @get:Rule
  val signalDatabaseRule = SignalDatabaseRule()

  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    TextSecurePreferences.getSharedPreferences(context).edit().clear().commit()
  }

  @After
  fun tearDown() {
    TellomiReadReceiptHistory.clock = { System.currentTimeMillis() }
  }

  private fun setReadReceipts(enabled: Boolean, at: Long) {
    TellomiReadReceiptHistory.clock = { at }
    TextSecurePreferences.setReadReceiptsEnabled(context, enabled)
  }

  private fun incomingMessage(arrivedAt: Long): MessageId {
    return MessageId(TestMms.insert(signalDatabaseRule.writeableDatabase, receivedTimestampMillis = arrivedAt))
  }

  @Test
  fun `the first write on a fresh install and writes that change nothing are not switches`() {
    setReadReceipts(enabled = true, at = 1_000)
    setReadReceipts(enabled = true, at = 2_000)
    assertThat(TellomiReadReceiptHistory.readHistory(context).events).isEmpty()

    setReadReceipts(enabled = false, at = 3_000)
    setReadReceipts(enabled = true, at = 4_000)
    assertThat(TellomiReadReceiptHistory.readHistory(context).events).containsExactly(Event(3_000, false), Event(4_000, true))
  }

  @Test
  fun `messages that arrived while read receipts were off never get a receipt, later ones do`() {
    setReadReceipts(enabled = true, at = 1_000)
    val beforeOff = incomingMessage(arrivedAt = 1_500)

    setReadReceipts(enabled = false, at = 2_000)
    val whileOff = listOf(incomingMessage(arrivedAt = 2_100), incomingMessage(arrivedAt = 2_200), incomingMessage(arrivedAt = 2_300))

    setReadReceipts(enabled = true, at = 3_000)
    val afterOn = incomingMessage(arrivedAt = 3_500)

    val ids = listOf(beforeOff) + whileOff + afterOn
    val filtered = TellomiReadReceiptHistory.filterArrivedWhileEnabled(context, ids, listOf(15L, 21L, 22L, 23L, 35L))

    assertThat(filtered.messageIds).containsExactly(beforeOff, afterOn)
    assertThat(filtered.values).containsExactly(15L, 35L)
  }

  @Test
  fun `after upgrading with read receipts off, everything before the first switch on counts as off`() {
    TextSecurePreferences.setBooleanPreference(context, TextSecurePreferences.READ_RECEIPTS_PREF, false)
    val old = incomingMessage(arrivedAt = 1_000)

    setReadReceipts(enabled = true, at = 2_000)
    val newer = incomingMessage(arrivedAt = 2_500)

    val filtered = TellomiReadReceiptHistory.filterArrivedWhileEnabled(context, listOf(old, newer), listOf(1L, 2L))
    assertThat(filtered.messageIds).containsExactly(newer)
  }

  @Test
  fun `without any recorded switch and read receipts on, every message counts as on`() {
    TextSecurePreferences.setBooleanPreference(context, TextSecurePreferences.READ_RECEIPTS_PREF, true)
    val a = incomingMessage(arrivedAt = 1_000)
    val b = incomingMessage(arrivedAt = 2_000)

    val filtered = TellomiReadReceiptHistory.filterArrivedWhileEnabled(context, listOf(a, b), listOf(1L, 2L))
    assertThat(filtered.messageIds).containsExactly(a, b)
  }

  @Test
  fun `a message that no longer exists, or a batch whose lists do not line up, sends nothing`() {
    TextSecurePreferences.setBooleanPreference(context, TextSecurePreferences.READ_RECEIPTS_PREF, true)
    val kept = incomingMessage(arrivedAt = 1_000)

    assertThat(TellomiReadReceiptHistory.filterArrivedWhileEnabled(context, listOf(kept, MessageId(9_999)), listOf(1L, 2L)).messageIds).containsExactly(kept)
    assertThat(TellomiReadReceiptHistory.filterArrivedWhileEnabled(context, listOf(kept), listOf(1L, 2L)).messageIds).isEmpty()
  }

  @Test
  fun `once older switches are dropped, messages from before the oldest kept switch count as off`() {
    var history = History.EMPTY
    for (i in 1..(TellomiReadReceiptHistory.MAX_EVENTS + 2)) {
      history = TellomiReadReceiptHistory.append(history, Event(i * 1_000L, enabled = i % 2 == 0))
    }

    assertThat(history.truncated).isTrue()
    assertThat(history.events.size).isEqualTo(TellomiReadReceiptHistory.MAX_EVENTS)
    assertThat(TellomiReadReceiptHistory.wasEnabledAt(history, currentlyEnabled = true, arrivedAtMillis = 500)).isFalse()
    assertThat(TellomiReadReceiptHistory.wasEnabledAt(history, currentlyEnabled = true, arrivedAtMillis = history.events.last().atMillis + 1)).isEqualTo(history.events.last().enabled)
  }

  @Test
  fun `switches are read by time, not by the order they were recorded in, when the clock was moved back`() {
    val history = History(listOf(Event(5_000, false), Event(3_000, true)), truncated = false)
    assertThat(TellomiReadReceiptHistory.wasEnabledAt(history, currentlyEnabled = true, arrivedAtMillis = 4_000)).isTrue()
    assertThat(TellomiReadReceiptHistory.wasEnabledAt(history, currentlyEnabled = true, arrivedAtMillis = 6_000)).isFalse()
    assertThat(TellomiReadReceiptHistory.wasEnabledAt(history, currentlyEnabled = true, arrivedAtMillis = 2_000)).isFalse()
  }

  @Test
  fun `the history survives a round trip through the preference string`() {
    val history = History(listOf(Event(1_727_250_000_000, false), Event(1_727_260_000_000, true)), truncated = true)
    assertThat(TellomiReadReceiptHistory.decode(TellomiReadReceiptHistory.encode(history))).isEqualTo(history)
    assertThat(TellomiReadReceiptHistory.decode(TellomiReadReceiptHistory.encode(History.EMPTY))).isEqualTo(History.EMPTY)
    assertThat(TellomiReadReceiptHistory.decode("garbage")).isEqualTo(History.EMPTY)
  }
}
