/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import kotlin.time.Duration.Companion.hours

/**
 * Tellomi (interaction audit A-39): the mute durations open in a menu anchored where the user tapped, with the same
 * choices, in the same order, as the old centered dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class TellomiAnchoredMuteMenuTest {

  @Test
  fun `the anchored menu offers the same durations as the dialog, in the same order`() {
    val (menu, _) = showMenu()

    assertEquals(
      listOf("1 Hour", "8 Hours", "1 Day", "1 Week", "Until…", "Always"),
      titles(menu)
    )
  }

  @Test
  fun `picking a duration mutes until then and closes the menu`() {
    val (menu, muted) = showMenu()
    val before = System.currentTimeMillis()

    itemViews(menu)[0].performClick()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, muted.size)
    assertTrue(muted[0] - before in 1.hours.inWholeMilliseconds..1.hours.inWholeMilliseconds + 60_000)
    assertFalse(menu.isShowing)
  }

  @Test
  fun `always mutes forever`() {
    val (menu, muted) = showMenu()

    itemViews(menu)[5].performClick()

    assertEquals(listOf(Long.MAX_VALUE), muted)
  }

  private fun showMenu(): Pair<SignalContextMenu, MutableList<Long>> {
    val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    activity.setTheme(R.style.Signal_DayNight)
    val container = FrameLayout(activity)
    val anchor = View(activity)
    container.addView(anchor, FrameLayout.LayoutParams(400, 100))
    activity.setContentView(container, ViewGroup.LayoutParams(1080, 2000))
    shadowOf(Looper.getMainLooper()).idle()

    val muted = mutableListOf<Long>()
    val menu = MuteDialog.showAnchored(SignalContextMenu.Builder(anchor, container), activity.supportFragmentManager, activity) { muted += it }
    shadowOf(Looper.getMainLooper()).idle()
    layOut(menu)
    return menu to muted
  }

  private fun layOut(menu: SignalContextMenu) {
    val content = menu.contentView
    content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
    content.layout(0, 0, content.measuredWidth, content.measuredHeight)
  }

  private fun itemViews(menu: SignalContextMenu): List<View> {
    val list = menu.contentView.findViewById<RecyclerView>(R.id.signal_context_menu_list)
    return (0 until list.childCount).map { list.getChildAt(it) }
  }

  private fun titles(menu: SignalContextMenu): List<String> {
    return itemViews(menu).map { it.findViewById<TextView>(R.id.signal_context_menu_item_title).text.toString() }
  }
}
