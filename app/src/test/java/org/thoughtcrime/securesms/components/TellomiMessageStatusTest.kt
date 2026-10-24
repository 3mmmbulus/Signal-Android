/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.TellomiMessageStatus.Display
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.mms.ImageSlide
import org.thoughtcrime.securesms.mms.Slide
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.mms.TextSlide
import org.thoughtcrime.securesms.util.MediaUtil
import java.time.Duration

/**
 * Tellomi（tellomi/tellomi#1183，需求 message-status-and-read-receipts §3.1–3.2）：
 * 一个勾 = 已发出（含已送达），两个勾 = 已读；文字消息发送中 2 秒后才转圈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiMessageStatusTest {

  @Test
  fun `delivered is one check like sent, only read or viewed is two checks`() {
    assertThat(TellomiMessageStatus.display(isPending = true, hasReadReceipt = false, isViewed = false)).isEqualTo(Display.SENDING)
    assertThat(TellomiMessageStatus.display(isPending = false, hasReadReceipt = false, isViewed = false)).isEqualTo(Display.SENT)
    assertThat(TellomiMessageStatus.display(isPending = false, hasReadReceipt = true, isViewed = false)).isEqualTo(Display.READ)
    assertThat(TellomiMessageStatus.display(isPending = false, hasReadReceipt = false, isViewed = true)).isEqualTo(Display.READ)
  }

  @Test
  fun `text waits two seconds from sending before the spinner shows, attachments show it at once`() {
    assertThat(TellomiMessageStatus.sendingIndicatorDelayMs(isTextOnly = true, sentAtMillis = 10_000, nowMillis = 10_000)).isEqualTo(2_000L)
    assertThat(TellomiMessageStatus.sendingIndicatorDelayMs(isTextOnly = true, sentAtMillis = 10_000, nowMillis = 11_500)).isEqualTo(500L)
    assertThat(TellomiMessageStatus.sendingIndicatorDelayMs(isTextOnly = true, sentAtMillis = 10_000, nowMillis = 13_000)).isEqualTo(0L)
    assertThat(TellomiMessageStatus.sendingIndicatorDelayMs(isTextOnly = true, sentAtMillis = 10_000, nowMillis = 9_000)).isEqualTo(2_000L)
    assertThat(TellomiMessageStatus.sendingIndicatorDelayMs(isTextOnly = false, sentAtMillis = 10_000, nowMillis = 10_000)).isEqualTo(0L)
  }

  @Test
  fun `only a real attachment makes a message not text, long text still counts as text`() {
    assertThat(TellomiMessageStatus.isTextOnly(mmsWith())).isTrue()
    assertThat(TellomiMessageStatus.isTextOnly(mmsWith(mockk<TextSlide>()))).isTrue()
    assertThat(TellomiMessageStatus.isTextOnly(mmsWith(mockk<ImageSlide>()))).isFalse()
    assertThat(TellomiMessageStatus.isTextOnly(mmsWith(mockk<TextSlide>(), mockk<ImageSlide>()))).isFalse()
    assertThat(TellomiMessageStatus.isTextOnly(mockk<MessageRecord>())).isTrue()

    assertThat(TellomiMessageStatus.isTextOnlySnippet(null)).isTrue()
    assertThat(TellomiMessageStatus.isTextOnlySnippet(MediaUtil.LONG_TEXT)).isTrue()
    assertThat(TellomiMessageStatus.isTextOnlySnippet(MediaUtil.IMAGE_JPEG)).isFalse()
  }

  @Test
  fun `with my read receipts off, group members who read or viewed show as delivered in message details`() {
    assertThat(TellomiMessageStatus.groupStatusForDetails(GroupReceiptTable.STATUS_READ, readReceiptsEnabled = false)).isEqualTo(GroupReceiptTable.STATUS_DELIVERED)
    assertThat(TellomiMessageStatus.groupStatusForDetails(GroupReceiptTable.STATUS_VIEWED, readReceiptsEnabled = false)).isEqualTo(GroupReceiptTable.STATUS_DELIVERED)
    assertThat(TellomiMessageStatus.groupStatusForDetails(GroupReceiptTable.STATUS_READ, readReceiptsEnabled = true)).isEqualTo(GroupReceiptTable.STATUS_READ)
    assertThat(TellomiMessageStatus.groupStatusForDetails(GroupReceiptTable.STATUS_UNDELIVERED, readReceiptsEnabled = false)).isEqualTo(GroupReceiptTable.STATUS_UNDELIVERED)
  }

  @Test
  fun `a delayed spinner holds its place without being drawn, then shows and spins, and a rebind restarts the wait`() {
    val view = attachedStatusView()

    view.setPending(2_000)
    idle(100)
    assertThat(view.visibility).isEqualTo(View.INVISIBLE)
    // 挂着动画的 View 即使 INVISIBLE 也会被画出来（走查模拟器上实测踩到过），等待期间不能挂转圈动画
    assertThat(view.animation).isNull()
    assertThat(view.isPending).isTrue()

    idle(1_400)
    view.setPending(2_000)
    idle(1_000)
    assertThat(view.visibility).isEqualTo(View.INVISIBLE)
    assertThat(view.animation).isNull()

    idle(1_000)
    assertThat(view.visibility).isEqualTo(View.VISIBLE)
    assertThat(view.animation).isNotNull()
  }

  @Test
  fun `a recycled view that was spinning for an older message stops being drawn while the new one waits`() {
    val view = attachedStatusView()

    view.setPending(0)
    idle(100)
    assertThat(view.animation).isNotNull()

    view.setPending(2_000)
    idle(100)
    assertThat(view.visibility).isEqualTo(View.INVISIBLE)
    assertThat(view.animation).isNull()

    idle(2_000)
    assertThat(view.visibility).isEqualTo(View.VISIBLE)
    assertThat(view.animation).isNotNull()
  }

  @Test
  fun `no delay shows the spinner at once, and one check replaces a waiting spinner for good`() {
    val view = attachedStatusView()

    view.setPending(0)
    assertThat(view.visibility).isEqualTo(View.VISIBLE)

    view.setPending(2_000)
    view.setSent()
    idle(3_000)
    assertThat(view.visibility).isEqualTo(View.VISIBLE)
    assertThat(view.isPending).isFalse()
  }

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese message details use the same sent, delivered and read words`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    assertThat(context.getString(R.string.message_details_recipient_header__sent_to)).isEqualTo("已發出")
    assertThat(context.getString(R.string.message_details_recipient_header__delivered_to)).isEqualTo("已送達")
    assertThat(context.getString(R.string.message_details_recipient_header__read_by)).isEqualTo("已讀")
  }

  private fun mmsWith(vararg slides: Slide): MessageRecord {
    val deck = mockk<SlideDeck> { every { this@mockk.slides } returns slides.toList() }
    return mockk<MmsMessageRecord> { every { slideDeck } returns deck }
  }

  private fun attachedStatusView(): DeliveryStatusView {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = DeliveryStatusView(activity)
    activity.setContentView(FrameLayout(activity).apply { addView(view, 48, 48) })
    shadowOf(Looper.getMainLooper()).idle()
    return view
  }

  private fun idle(millis: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
  }
}
