/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.app.Application
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement

/**
 * Tellomi（tellomi/tellomi#1109，ADR-0058 §2）：滑动回复改成手指从右往左滑。
 * Robolectric 默认密度是 1，所以 dp 和 px 在这里相等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSwipeToReplyTest {

  private fun swipeableElement(isOutgoing: Boolean): RecyclerView.ViewHolder {
    val holder = mockk<RecyclerView.ViewHolder>(moreInterfaces = arrayOf(InteractiveConversationElement::class), relaxed = true)
    val element = holder as InteractiveConversationElement
    every { element.conversationMessage.messageRecord.isOutgoing } returns isOutgoing
    every { element.disallowSwipe(any(), any()) } returns false
    return holder
  }

  private fun callback(): ConversationItemSwipeCallback {
    return ConversationItemSwipeCallback({ true }, { })
  }

  @Test
  fun `the reply swipe runs towards the start edge, i_e_ the finger moves right to left`() {
    val dirs = callback().getSwipeDirs(mockk(relaxed = true), swipeableElement(isOutgoing = false))
    assertThat(dirs).isEqualTo(ItemTouchHelper.START)
  }

  @Test
  fun `a message that cannot be replied to does not swipe at all`() {
    val cannotReply = ConversationItemSwipeCallback({ false }, { })
    assertThat(cannotReply.getSwipeDirs(mockk(relaxed = true), swipeableElement(isOutgoing = false))).isEqualTo(0)
  }

  @Test
  fun `trigger distance is 45dp for incoming and 60dp for outgoing messages, as in Telegram`() {
    assertThat(ConversationItemSwipeCallback.triggerDx(swipeableElement(isOutgoing = false) as InteractiveConversationElement)).isEqualTo(45f)
    assertThat(ConversationItemSwipeCallback.triggerDx(swipeableElement(isOutgoing = true) as InteractiveConversationElement)).isEqualTo(60f)
  }

  @Test
  fun `the bubble follows the finger 1 to 1 up to the trigger`() {
    assertThat(TellomiSwipeToReply.bubbleOffset(0f, 45f, 100f, 180f)).isEqualTo(0f)
    assertThat(TellomiSwipeToReply.bubbleOffset(-12f, 45f, 100f, 180f)).isEqualTo(0f)
    assertThat(TellomiSwipeToReply.bubbleOffset(30f, 45f, 100f, 180f)).isEqualTo(30f)
    assertThat(TellomiSwipeToReply.bubbleOffset(45f, 45f, 100f, 180f)).isEqualTo(45f)
  }

  @Test
  fun `past the trigger it rubber-bands and never exceeds the trigger plus the band or the cap`() {
    // 过阈值 100：(1 - 1 / (100 × 0.4 / 100 + 1)) × 100 ≈ 28.57
    assertThat(TellomiSwipeToReply.bubbleOffset(145f, 45f, 100f, 180f)).isCloseTo(73.571f, 0.01f)
    val far = TellomiSwipeToReply.bubbleOffset(10_000f, 60f, 100f, 180f)
    assertThat(far).isGreaterThan(155f)
    assertThat(far).isLessThan(160f)
    assertThat(TellomiSwipeToReply.bubbleOffset(10_000f, 45f, 100f, 50f)).isEqualTo(50f)
  }

  @Test
  fun `reply direction sign is negative left-to-right and positive right-to-left`() {
    assertThat(TellomiSwipeToReply.replySign(isRtl = false)).isEqualTo(-1f)
    assertThat(TellomiSwipeToReply.replySign(isRtl = true)).isEqualTo(1f)
  }

  // 交互审计 A-10：松手用投射落点判定、弹簧从手指速度起步、不在系统返回手势的边缘区起手。

  @Test
  fun `releasing past the trigger without moving still replies`() {
    assertThat(TellomiSwipeToReply.shouldReply(fingerOffset = 50f, fingerVelocity = 0f, trigger = 45f)).isTrue()
    assertThat(TellomiSwipeToReply.shouldReply(fingerOffset = 40f, fingerVelocity = 0f, trigger = 45f)).isFalse()
  }

  @Test
  fun `a quick flick that has not reached the trigger yet replies`() {
    // 30 + 800 × 0.1 = 110 > 45
    assertThat(TellomiSwipeToReply.shouldReply(fingerOffset = 30f, fingerVelocity = 800f, trigger = 45f)).isTrue()
    // 12 + 200 × 0.1 = 32 < 45：轻微晃动不算
    assertThat(TellomiSwipeToReply.shouldReply(fingerOffset = 12f, fingerVelocity = 200f, trigger = 45f)).isFalse()
  }

  @Test
  fun `flicking back from past the trigger cancels the reply`() {
    // 70 − 400 × 0.1 = 30 < 60
    assertThat(TellomiSwipeToReply.shouldReply(fingerOffset = 70f, fingerVelocity = -400f, trigger = 60f)).isFalse()
  }

  @Test
  fun `the bubble moves with the finger speed up to the trigger and slower in the rubber band`() {
    assertThat(TellomiSwipeToReply.bubbleVelocity(30f, 500f, 45f, 100f, 180f)).isEqualTo(500f)
    // 过阈值 100：斜率 0.4 / (100 × 0.4 / 100 + 1)² = 0.4 / 1.96
    assertThat(TellomiSwipeToReply.bubbleVelocity(145f, 500f, 45f, 100f, 180f)).isCloseTo(500f * 0.4f / 1.96f, 0.01f)
    assertThat(TellomiSwipeToReply.bubbleVelocity(145f, -500f, 45f, 100f, 180f)).isLessThan(0f)
  }

  @Test
  fun `the bubble does not move when it is held at the cap or has not left yet`() {
    assertThat(TellomiSwipeToReply.bubbleVelocity(10_000f, 500f, 45f, 100f, 50f)).isEqualTo(0f)
    assertThat(TellomiSwipeToReply.bubbleVelocity(-5f, 500f, 45f, 100f, 180f)).isEqualTo(0f)
  }

  @Test
  fun `a press inside the system back gesture edges does not start a reply swipe`() {
    assertThat(TellomiSwipeToReply.startsInGestureEdge(xInWindow = 10f, windowWidth = 1080, leftEdge = 63, rightEdge = 63)).isTrue()
    assertThat(TellomiSwipeToReply.startsInGestureEdge(xInWindow = 1030f, windowWidth = 1080, leftEdge = 63, rightEdge = 63)).isTrue()
    assertThat(TellomiSwipeToReply.startsInGestureEdge(xInWindow = 540f, windowWidth = 1080, leftEdge = 63, rightEdge = 63)).isFalse()
  }

  @Test
  fun `with button navigation there are no gesture edges`() {
    assertThat(TellomiSwipeToReply.startsInGestureEdge(xInWindow = 0f, windowWidth = 1080, leftEdge = 0, rightEdge = 0)).isFalse()
    assertThat(TellomiSwipeToReply.startsInGestureEdge(xInWindow = 1079f, windowWidth = 1080, leftEdge = 0, rightEdge = 0)).isFalse()
  }
}
