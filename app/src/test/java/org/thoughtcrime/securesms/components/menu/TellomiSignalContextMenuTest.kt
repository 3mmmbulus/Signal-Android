/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.menu

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.R

/** Tellomi (interaction audit A-25): context menus grow out of the corner next to their anchor; the chat list's long-press one dims what is behind it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiSignalContextMenuTest {

  @Test
  fun `the menu grows from the corner that touches the anchor`() {
    assertThat(SignalContextMenu.tellomiAnimationStyle(SignalContextMenu.TellomiPlacement.BELOW, alignedToLeft = true)).isEqualTo(R.style.TellomiContextMenuAnimation_TopStart)
    assertThat(SignalContextMenu.tellomiAnimationStyle(SignalContextMenu.TellomiPlacement.BELOW, alignedToLeft = false)).isEqualTo(R.style.TellomiContextMenuAnimation_TopEnd)
    assertThat(SignalContextMenu.tellomiAnimationStyle(SignalContextMenu.TellomiPlacement.ABOVE, alignedToLeft = true)).isEqualTo(R.style.TellomiContextMenuAnimation_BottomStart)
    assertThat(SignalContextMenu.tellomiAnimationStyle(SignalContextMenu.TellomiPlacement.ABOVE, alignedToLeft = false)).isEqualTo(R.style.TellomiContextMenuAnimation_BottomEnd)
    assertThat(SignalContextMenu.tellomiAnimationStyle(SignalContextMenu.TellomiPlacement.CENTERED, alignedToLeft = true)).isEqualTo(R.style.TellomiContextMenuAnimation_Center)
  }

  private fun showMenu(dim: Boolean): SignalContextMenu {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setTheme(R.style.Signal_DayNight)
    val container = FrameLayout(activity)
    val anchor = View(activity)
    container.addView(anchor, FrameLayout.LayoutParams(400, 100))
    activity.setContentView(container, android.view.ViewGroup.LayoutParams(1080, 2000))
    shadowOf(Looper.getMainLooper()).idle()

    val builder = SignalContextMenu.Builder(anchor, container)
    if (dim) builder.dimBackground()
    val menu = builder.show(listOf(ActionItem(org.signal.core.ui.R.drawable.symbol_trash_24, "Delete") {}))
    shadowOf(Looper.getMainLooper()).idle()
    return menu
  }

  @Test
  fun `a menu under its anchor grows down from the top-left corner`() {
    val menu = showMenu(dim = false)

    assertThat(menu.isShowing).isTrue()
    assertThat(menu.animationStyle).isEqualTo(R.style.TellomiContextMenuAnimation_TopStart)
    val params = menu.contentView.rootView.layoutParams as WindowManager.LayoutParams
    assertThat(params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0).isFalse()
    menu.dismiss()
  }

  @Test
  fun `the chat list menu dims what is behind it`() {
    val menu = showMenu(dim = true)

    val params = menu.contentView.rootView.layoutParams as WindowManager.LayoutParams
    assertThat(params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0).isTrue()
    assertThat(params.dimAmount).isEqualTo(SignalContextMenu.TELLOMI_DIM_AMOUNT)
    menu.dismiss()
  }
}
