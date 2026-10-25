/*
 * Copyright 2026 Tellomi
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ClipDrawable
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.TellomiMessageStatus.Animation
import org.thoughtcrime.securesms.components.TellomiMessageStatus.Display

/**
 * Tellomi M7 品牌勾（tellomi/tellomi#1186，需求 §3.1）：一勾淡入、第二勾长出，只在同一条消息留在屏上时看到的变化才动；
 * 本机首次发出后一次性提示。规则和电脑端 #98 / iOS #366 一样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiMessageStatusBrandTest {

  @Test
  fun `only a change on the same message animates, a first draw or another message does not`() {
    assertThat(TellomiMessageStatus.statusAnimation(Display.SENDING, Display.SENT, sameMessage = true)).isEqualTo(Animation.SENT)
    assertThat(TellomiMessageStatus.statusAnimation(Display.SENT, Display.READ, sameMessage = true)).isEqualTo(Animation.READ)
    assertThat(TellomiMessageStatus.statusAnimation(Display.SENDING, Display.READ, sameMessage = true)).isEqualTo(Animation.READ)

    assertThat(TellomiMessageStatus.statusAnimation(Display.SENDING, Display.SENT, sameMessage = false)).isNull()
    assertThat(TellomiMessageStatus.statusAnimation(null, Display.READ, sameMessage = true)).isNull()
    assertThat(TellomiMessageStatus.statusAnimation(Display.READ, Display.READ, sameMessage = true)).isNull()
    assertThat(TellomiMessageStatus.statusAnimation(Display.SENT, Display.SENT, sameMessage = true)).isNull()
    assertThat(TellomiMessageStatus.statusAnimation(Display.READ, Display.SENT, sameMessage = true)).isNull()
    assertThat(TellomiMessageStatus.statusAnimation(Display.SENT, null, sameMessage = true)).isNull()
  }

  @Test
  fun `the checks hint shows once and never for note to self, edits or story replies`() {
    assertThat(TellomiMessageStatus.shouldShowChecksHint(alreadyShown = false, isNoteToSelf = false, isEdit = false, isStoryReply = false)).isTrue()
    assertThat(TellomiMessageStatus.shouldShowChecksHint(alreadyShown = true, isNoteToSelf = false, isEdit = false, isStoryReply = false)).isFalse()
    assertThat(TellomiMessageStatus.shouldShowChecksHint(alreadyShown = false, isNoteToSelf = true, isEdit = false, isStoryReply = false)).isFalse()
    assertThat(TellomiMessageStatus.shouldShowChecksHint(alreadyShown = false, isNoteToSelf = false, isEdit = true, isStoryReply = false)).isFalse()
    assertThat(TellomiMessageStatus.shouldShowChecksHint(alreadyShown = false, isNoteToSelf = false, isEdit = false, isStoryReply = true)).isFalse()
  }

  @Test
  fun `one check fades in when the same message on screen is sent`() {
    val view = attachedStatusView()

    view.setMessageKey(1)
    view.setPending(0)
    view.setMessageKey(1)
    view.setSent()

    assertThat(view.alpha).isLessThan(1f)
    assertThat(view.consumeLiveSentChange()).isTrue()
    assertThat(view.consumeLiveSentChange()).isFalse()
  }

  @Test
  fun `a view rebound to another message, as when opening a chat or scrolling back, does not animate`() {
    val view = attachedStatusView()

    view.setMessageKey(1)
    view.setPending(0)
    view.setMessageKey(2)
    view.setSent()
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.consumeLiveSentChange()).isFalse()

    view.setMessageKey(3)
    view.setRead()
    assertThat(view.drawable).isNotInstanceOf(ClipDrawable::class)
  }

  @Test
  fun `the second check grows when the same message is read, and ends as the full double check`() {
    val view = attachedStatusView()

    view.setMessageKey(1)
    view.setSent()
    view.setMessageKey(1)
    view.setRead()
    assertThat(view.drawable).isInstanceOf(ClipDrawable::class)
    assertThat(view.drawable.level).isEqualTo(TellomiMessageStatus.READ_GROW_START_LEVEL)

    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(TellomiMessageStatus.READ_GROW_MS + 50))
    assertThat(view.drawable.level).isEqualTo(10_000)
  }

  @Test
  fun `message details never animate because they never set a message key`() {
    val view = attachedStatusView()

    view.setPending()
    view.setSent()
    view.setRead()
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.drawable).isNotInstanceOf(ClipDrawable::class)
    assertThat(view.consumeLiveSentChange()).isFalse()
  }

  @Test
  fun `with system animations off nothing moves, but the first-send hint still counts the change`() {
    val view = attachedStatusView()
    Settings.Global.putFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    view.setMessageKey(1)
    view.setPending(0)
    view.setMessageKey(1)
    view.setSent()
    assertThat(view.alpha).isEqualTo(1f)
    assertThat(view.consumeLiveSentChange()).isTrue()

    view.setMessageKey(1)
    view.setRead()
    assertThat(view.drawable).isNotInstanceOf(ClipDrawable::class)
  }

  @Test
  fun `read checks use the accent only outside a bubble and without a wallpaper, same as iOS`() {
    assertThat(TellomiMessageStatus.usesAccentForReadChecks(hasNoBubble = true, hasWallpaper = false)).isTrue()
    assertThat(TellomiMessageStatus.usesAccentForReadChecks(hasNoBubble = true, hasWallpaper = true)).isFalse()
    assertThat(TellomiMessageStatus.usesAccentForReadChecks(hasNoBubble = false, hasWallpaper = false)).isFalse()
    assertThat(TellomiMessageStatus.usesAccentForReadChecks(hasNoBubble = false, hasWallpaper = true)).isFalse()
  }

  @Test
  fun `one check and two checks take the same width so the time does not shift`() {
    val view = attachedStatusView()

    view.setSent()
    val sentWidth = view.drawable.intrinsicWidth
    val sentHeight = view.drawable.intrinsicHeight
    view.setRead()

    assertThat(view.drawable.intrinsicWidth).isEqualTo(sentWidth)
    assertThat(view.drawable.intrinsicHeight).isEqualTo(sentHeight)
    assertThat(sentWidth).isEqualTo((18 * view.resources.displayMetrics.density).toInt())
  }

  @Test
  fun `sending, one check and two checks take the same 20dp so the time does not jump`() {
    val view = attachedStatusView()
    val density = view.resources.displayMetrics.density

    view.setPending()
    val pending = statusWidth(view)
    view.setSent()
    val sent = statusWidth(view)
    view.setRead()
    val read = statusWidth(view)

    view.setPending(2_000)
    val delayedPending = statusWidth(view)
    assertThat(view.visibility).isEqualTo(View.INVISIBLE)

    assertThat(pending).isEqualTo(sent)
    assertThat(read).isEqualTo(sent)
    assertThat(delayedPending).isEqualTo(sent)
    assertThat(sent).isEqualTo((18 * density).toInt() + (2 * density).toInt())
  }

  @Test
  fun `the hint anchors above the compose bar when it is on screen, otherwise falls back`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val bubble = View(activity)
    val composeBar = View(activity).apply { id = R.id.conversation_input_panel }
    activity.setContentView(FrameLayout(activity).apply { addView(bubble); addView(composeBar) })
    shadowOf(Looper.getMainLooper()).idle()

    assertThat(TellomiChecksHint.findComposeBar(bubble)).isNotNull()
    assertThat(TellomiChecksHint.findComposeBar(bubble)).isEqualTo(composeBar)

    composeBar.visibility = View.GONE
    assertThat(TellomiChecksHint.findComposeBar(bubble)).isNull()
  }

  @Test
  @Config(qualifiers = "zh-rCN")
  fun `simplified chinese hint text`() {
    assertThat(hint()).isEqualTo("一个勾：已发出；两个勾：对方已读")
  }

  @Test
  @Config(qualifiers = "zh-rTW")
  fun `taiwan hint text`() {
    assertThat(hint()).isEqualTo("一個勾：已發出；兩個勾：對方已讀")
  }

  @Test
  @Config(qualifiers = "zh-rHK")
  fun `hong kong hint text`() {
    assertThat(hint()).isEqualTo("一個勾：已發出；兩個勾：對方已讀")
  }

  @Test
  @Config(qualifiers = "yue")
  fun `cantonese hint text`() {
    assertThat(hint()).isEqualTo("一個剔：已發出；兩個剔：對方已讀")
  }

  @Test
  fun `english hint text`() {
    assertThat(hint()).isEqualTo("One check: sent. Two checks: read.")
  }

  private fun hint(): String {
    return ApplicationProvider.getApplicationContext<Application>().getString(R.string.TellomiChecksHint__one_check_sent_two_checks_read)
  }

  /** 状态位占的宽度：图标固有宽度 + 左右内边距（wrap_content 时就是 View 的宽）。 */
  private fun statusWidth(view: DeliveryStatusView): Int {
    return view.drawable.intrinsicWidth + view.paddingLeft + view.paddingRight
  }

  private fun attachedStatusView(): DeliveryStatusView {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = DeliveryStatusView(activity)
    activity.setContentView(FrameLayout(activity).apply { addView(view, 48, 48) })
    shadowOf(Looper.getMainLooper()).idle()
    return view
  }
}
