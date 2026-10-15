/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R
import java.time.Duration

/**
 * Tellomi (interaction audit A-14): the scroll-to-bottom button no longer gets stuck hidden when it is asked to show
 * again while it is still fading out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiScrollToButtonTest {

  @Test
  fun `showing again while it fades out leaves the button shown`() {
    val button = newButton()
    button.setShown(true)
    settle()

    button.setShown(false)
    button.setShown(true)
    settle()

    assertEquals(View.VISIBLE, button.visibility)
    assertEquals(1f, button.alpha, 0f)
    assertEquals(1f, button.scaleX, 0f)
  }

  @Test
  fun `hiding ends invisible and faded out`() {
    val button = newButton()
    button.setShown(true)
    settle()

    button.setShown(false)
    settle()

    assertEquals(View.INVISIBLE, button.visibility)
    assertEquals(0f, button.alpha, 0f)
  }

  @Test
  fun `hiding again while it fades in leaves the button hidden`() {
    val button = newButton()

    button.setShown(true)
    button.setShown(false)
    settle()

    assertEquals(View.INVISIBLE, button.visibility)
  }

  @Test
  fun `the unread badge shows the count and hides at zero`() {
    val button = newButton()
    button.setShown(true)
    settle()
    val badge = button.findViewById<TextView>(R.id.conversation_scroll_to_count)

    button.setUnreadCount(3)
    settle()
    assertEquals(View.VISIBLE, badge.visibility)
    assertEquals("3", badge.text.toString())

    button.setUnreadCount(1200)
    settle()
    assertEquals("999+", badge.text.toString())
    assertEquals(1f, badge.scaleX, 0f)

    button.setUnreadCount(0)
    settle()
    assertEquals(View.GONE, badge.visibility)
  }

  private fun newButton(): ConversationScrollToView {
    val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Signal_DayNight)
    return ConversationScrollToView(context).apply { visibility = View.INVISIBLE }
  }

  private fun settle() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
  }
}
