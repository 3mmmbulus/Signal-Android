/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Activity
import android.app.Application
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.database.MessageTypes
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import java.time.Duration

/** Tellomi（M9 发送动画）：飞的几何（照 Telegram 两端）、认哪一条、等不到就不飞。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TellomiSendTransitionTest {

  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val fromBox = RectF(24f, 1800f, 1056f, 1900f)
  private val fromText = PointF(60f, 1822f)
  private val toBubble = RectF(700f, 1500f, 1040f, 1600f)
  private val toText = PointF(736f, 1521f)

  @After
  fun tearDown() {
    TellomiSendTransition.cancelAll()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun frame(t: Float, fromSize: Float = 48f, toSize: Float = 64f) = TellomiSendTransition.frameAt(t, fromBox, fromText, fromSize, toBubble, toText, toSize)

  @Test
  fun `the first frame is the input field and the last one is the bubble`() {
    val first = frame(0f)
    assertEquals(fromBox, first.bubble)
    assertEquals(fromText, first.textOrigin)
    assertEquals(0.75f, first.scale, 0.0001f)
    assertEquals(0f, first.bubbleAlpha, 0f)

    val last = frame(1f)
    assertEquals(toBubble, last.bubble)
    assertEquals(toText, last.textOrigin)
    assertEquals(1f, last.scale, 0f)
    assertEquals(1f, last.bubbleAlpha, 0f)
  }

  @Test
  fun `the bubble with its tail is opaque after 120 of the 280 ms`() {
    assertEquals(0.5f, frame(60f / 280f).bubbleAlpha, 0.001f)
    assertEquals(1f, frame(120f / 280f).bubbleAlpha, 0f)
    assertEquals(1f, frame(0.8f).bubbleAlpha, 0f)
  }

  /** 横向前快、纵向跟列表：同一时刻横向走得比纵向远，所以是一道弧线（Telegram 两端都是这样）。 */
  @Test
  fun `sideways runs ahead of upwards so the path is an arc`() {
    for (t in listOf(0.1f, 0.2f, 0.3f, 0.5f, 0.7f)) {
      val origin = frame(t).textOrigin
      val sideways = (origin.x - fromText.x) / (toText.x - fromText.x)
      val upwards = (origin.y - fromText.y) / (toText.y - fromText.y)
      assertTrue("t=$t: sideways $sideways, upwards $upwards", sideways > upwards)
    }
  }

  /** 气泡框的左右边跟横向进度、上下边跟纵向进度：输入框的框一路变成气泡。 */
  @Test
  fun `the box turns into the bubble edge by edge`() {
    val middle = frame(0.3f)
    val sideways = (middle.bubble.left - fromBox.left) / (toBubble.left - fromBox.left)
    val upwards = (middle.bubble.top - fromBox.top) / (toBubble.top - fromBox.top)
    assertEquals(sideways, (middle.bubble.right - fromBox.right) / (toBubble.right - fromBox.right), 0.0001f)
    assertEquals(upwards, (middle.bubble.bottom - fromBox.bottom) / (toBubble.bottom - fromBox.bottom), 0.0001f)
    assertTrue(sideways > upwards)
    assertEquals(sideways, (middle.textOrigin.x - fromText.x) / (toText.x - fromText.x), 0.0001f)
  }

  @Test
  fun `the text size goes from the input field's to the bubble's`() {
    assertEquals(1f, frame(0f, fromSize = 64f, toSize = 64f).scale, 0f)
    assertEquals(0.5f, frame(0f, fromSize = 32f, toSize = 64f).scale, 0f)
    assertTrue(frame(0.5f, fromSize = 32f, toSize = 64f).scale in 0.5f..1f)
  }

  /** 只认点发送之后写进库、正文一样的自己的那一条，一次发送只认一条；被认的那一行藏起来，输入框先透明。 */
  @Test
  fun `only the message sent after the tap with the same text is held`() {
    val (host, recycler, compose, box) = views()
    val tapped = System.currentTimeMillis()
    val transition = TellomiSendTransition.start(host, recycler, compose, box, body = "hi", matchAnyBody = false)
    assertNotNull(transition)
    assertEquals(0f, compose.alpha, 0f)

    fun held(id: Long, dateSent: Long, body: String, outgoing: Boolean = true): Boolean {
      val row = View(host.context)
      val record = FakeMessageRecords.buildMediaMmsMessageRecord(
        id = id,
        dateSent = dateSent,
        body = body,
        mailbox = if (outgoing) MessageTypes.BASE_SENT_TYPE else MessageTypes.BASE_INBOX_TYPE
      )
      TellomiSendTransition.holdIfSending(record, row)
      return row.alpha == 0f
    }

    assertEquals("sent before the tap", false, held(1, tapped - 1000, "hi"))
    assertEquals("received", false, held(2, tapped + 1000, "hi", outgoing = false))
    assertEquals("another text", false, held(3, tapped + 1000, "hello"))
    assertEquals("this one", true, held(4, tapped + 1000, "hi"))
    assertEquals("this one, bound again", true, held(4, tapped + 1000, "hi"))
    assertEquals("one message per send", false, held(5, tapped + 1001, "hi"))

    TellomiSendTransition.cancelAll()
    assertEquals(1f, compose.alpha, 0f)
    assertEquals(0, TellomiSendTransition.activeCountForTesting)
  }

  @Test
  fun `with mentions any text matches (the stored body has placeholders)`() {
    val (host, recycler, compose, box) = views()
    val tapped = System.currentTimeMillis()
    TellomiSendTransition.start(host, recycler, compose, box, body = "hi @Ann", matchAnyBody = true)
    val row = View(host.context)
    TellomiSendTransition.holdIfSending(
      FakeMessageRecords.buildMediaMmsMessageRecord(id = 9, dateSent = tapped + 1000, body = "hi ￼", mailbox = MessageTypes.BASE_SENT_TYPE),
      row
    )
    assertEquals(0f, row.alpha, 0f)
  }

  /** 藏着的行被回收去显示别的消息、或者这一次结束了，都要放回来（不然它会带着透明度去显示别的消息）。 */
  @Test
  fun `a held row that is rebound to another message, or left when the send ends, is shown again`() {
    val (host, recycler, compose, box) = views()
    val tapped = System.currentTimeMillis()
    TellomiSendTransition.start(host, recycler, compose, box, body = "hi", matchAnyBody = false)

    val row = View(host.context)
    TellomiSendTransition.holdIfSending(sent(id = 4, dateSent = tapped + 1000, body = "hi"), row)
    assertEquals(0f, row.alpha, 0f)
    TellomiSendTransition.holdIfSending(sent(id = 1, dateSent = tapped - 1000, body = "older"), row)
    assertEquals("rebound to an older message", 1f, row.alpha, 0f)

    val another = View(host.context)
    TellomiSendTransition.holdIfSending(sent(id = 4, dateSent = tapped + 1000, body = "hi"), another)
    assertEquals(0f, another.alpha, 0f)
    TellomiSendTransition.cancelAll()
    assertEquals("put back when the send ends", 1f, another.alpha, 0f)
  }

  /** 连着发两条：第一条收尾了、第二条还在等，输入框还是透明的；两条都完了才淡回。 */
  @Test
  fun `two quick sends - the field stays clear until both are done`() {
    val (host, recycler, compose, box) = views()
    val first = TellomiSendTransition.start(host, recycler, compose, box, body = "one", matchAnyBody = false)!!
    val second = TellomiSendTransition.start(host, recycler, compose, box, body = "two", matchAnyBody = false)!!
    assertEquals(0f, compose.alpha, 0f)

    first.cancel()
    // One frame at a time: an idle runs the whole frame chain, past the second one's 600 ms wait for its row.
    var frames = 0
    while (TellomiSendTransition.activeCountForTesting > 1 && frames++ < 200) {
      shadowOf(Looper.getMainLooper()).runOneTask()
    }
    assertEquals(1, TellomiSendTransition.activeCountForTesting)
    assertEquals(0f, compose.alpha, 0f)

    second.cancel()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiMotion.SendFlight.CANCEL_FADE_MS + 50))
    assertEquals(0, TellomiSendTransition.activeCountForTesting)
    assertEquals(1f, compose.alpha, 0f)
  }

  /** 等不到新的一行（写失败、不在底部……）：留在原处的那几行字淡掉，输入框淡回，收干净。 */
  @Test
  fun `nothing to fly to - the text fades where it was and everything is put back`() {
    val (host, recycler, compose, box) = views()
    TellomiSendTransition.start(host, recycler, compose, box, body = "hi", matchAnyBody = false)
    assertEquals(1, TellomiSendTransition.activeCountForTesting)

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TellomiMotion.SendFlight.WAIT_FOR_ROW_MS + TellomiMotion.SendFlight.CANCEL_FADE_MS + 100))

    assertEquals(0, TellomiSendTransition.activeCountForTesting)
    assertEquals(1f, compose.alpha, 0f)
  }

  @Test
  fun `no flight from a field that scrolls or has no layout`() {
    val (host, recycler, compose, box) = views(text = (1..12).joinToString("\n") { "line $it" })
    assertNull(TellomiSendTransition.start(host, recycler, compose, box, body = "x", matchAnyBody = false))
    assertEquals(1f, compose.alpha, 0f)

    val unlaidOut = EditText(host.context).apply { setText("hi") }
    assertNull(TellomiSendTransition.start(host, recycler, unlaidOut, box, body = "hi", matchAnyBody = false))
  }

  private fun sent(id: Long, dateSent: Long, body: String) = FakeMessageRecords.buildMediaMmsMessageRecord(id = id, dateSent = dateSent, body = body, mailbox = MessageTypes.BASE_SENT_TYPE)

  private data class Views(val host: ViewGroup, val recycler: RecyclerView, val compose: EditText, val box: View)

  private fun views(text: String = "hi"): Views {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val host = FrameLayout(activity)
    val recycler = RecyclerView(activity)
    val box = FrameLayout(activity)
    val compose = EditText(activity).apply { setText(text) }
    box.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    host.addView(recycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1500))
    host.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    activity.setContentView(host)
    host.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
    host.layout(0, 0, 1080, 2000)
    return Views(host, recycler, compose, box)
  }
}
