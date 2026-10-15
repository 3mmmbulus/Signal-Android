/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversationlist

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Tellomi (interaction audit A-37): when a new message pushes a conversation to the top, every row it passes moves
 * on the same curve instead of only that row sliding while the others jump; bulk updates and updates while
 * scrolling snap.
 *
 * Robolectric runs view property animations to the end at once, so the list tests check what each row was queued
 * with (its starting offset) and what it was animated with (duration and curve), not frames in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TellomiChatListMotionTest {

  @Test
  fun `a reorder or one or two entrances and exits animate`() {
    assertTrue(TellomiChatListMotion.shouldAnimate(additions = 0, removals = 0, listScrolling = false))
    assertTrue(TellomiChatListMotion.shouldAnimate(additions = 1, removals = 0, listScrolling = false))
    assertTrue(TellomiChatListMotion.shouldAnimate(additions = 1, removals = 1, listScrolling = false))
  }

  @Test
  fun `a bulk update or an update while scrolling snaps`() {
    assertFalse(TellomiChatListMotion.shouldAnimate(additions = 3, removals = 0, listScrolling = false))
    assertFalse(TellomiChatListMotion.shouldAnimate(additions = 0, removals = 3, listScrolling = false))
    assertFalse(TellomiChatListMotion.shouldAnimate(additions = 0, removals = 0, listScrolling = true))
  }

  @Test
  fun `a new row fades in only while other rows make room`() {
    assertTrue(TellomiChatListMotion.shouldFadeIn(moves = 4, removals = 0))
    assertTrue(TellomiChatListMotion.shouldFadeIn(moves = 0, removals = 1))
    assertFalse(TellomiChatListMotion.shouldFadeIn(moves = 0, removals = 0))
  }

  @Test
  fun `the first conversations arriving in an empty list just appear`() {
    val (list, adapter, animator) = showList(count = 0)

    adapter.items.addAll(listOf(100L, 101L))
    adapter.notifyItemRangeInserted(0, 2)
    layOut(list)
    animator.runPendingAnimations()

    assertTrue(animator.lastRunAnimated)
    for (id in listOf(100L, 101L)) {
      assertEquals(1f, list.findViewHolderForItemId(id)!!.itemView.alpha, 0f)
    }
    assertFalse(animator.isRunning)
  }

  @Test
  fun `a conversation moving to the top slides every row it passes, not just itself`() {
    val (list, adapter, animator) = showList(count = 8)
    val passedRows = (0 until 5).map { adapter.items[it] }
    val movedRow = adapter.items[5]

    adapter.items.add(0, adapter.items.removeAt(5))
    adapter.notifyItemMoved(5, 0)
    adapter.notifyItemChanged(0)
    layOut(list)

    for (id in passedRows) {
      assertEquals("row $id starts one row up", -ROW_HEIGHT.toFloat(), list.findViewHolderForItemId(id)!!.itemView.translationY, 0f)
    }
    assertEquals(5f * ROW_HEIGHT, list.findViewHolderForItemId(movedRow)!!.itemView.translationY, 0f)

    advance(Duration.ofMillis(600))

    assertTrue(animator.lastRunAnimated)
    assertFalse(animator.isRunning)
    for (id in passedRows + movedRow) {
      val view = list.findViewHolderForItemId(id)!!.itemView
      assertEquals(0f, view.translationY, 0f)
      assertEquals("row $id duration", TellomiChatListMotion.MOVE_DURATION_MS, view.animate().duration)
      assertSame("row $id curve", TellomiChatListMotion.MOVE_INTERPOLATOR, view.animate().interpolator)
    }
  }

  @Test
  fun `a new conversation at the top fades in while the rows below slide, not after them`() {
    val (list, adapter, animator) = showList(count = 8)

    adapter.items.add(0, 100L)
    adapter.notifyItemInserted(0)
    list.scrollToPosition(0)
    layOut(list)

    assertEquals(0f, list.findViewHolderForItemId(100L)!!.itemView.alpha, 0f)
    assertEquals(-ROW_HEIGHT.toFloat(), list.findViewHolderForItemId(0L)!!.itemView.translationY, 0f)

    // The pass that starts the slide also starts the fade. DefaultItemAnimator posts the fade for after the whole
    // move duration and leaves the top slot empty; until then the row's animator has no duration set (300 ms
    // default). Robolectric does not hold that delay back, so this checks the pass itself, not frames.
    animator.runPendingAnimations()
    val added = list.findViewHolderForItemId(100L)!!.itemView
    assertTrue(animator.lastRunAnimated)
    assertEquals(TellomiChatListMotion.ADD_DURATION_MS, added.animate().duration)

    advance(Duration.ofMillis(600))
    assertEquals(1f, added.alpha, 0f)
    assertFalse(animator.isRunning)
  }

  @Test
  fun `a bulk insert snaps into place`() {
    val (list, adapter, animator) = showList(count = 8)

    adapter.items.addAll(0, listOf(100L, 101L, 102L))
    adapter.notifyItemRangeInserted(0, 3)
    list.scrollToPosition(0)
    layOut(list)
    advance(Duration.ofMillis(34))

    assertFalse(animator.lastRunAnimated)
    assertFalse(animator.isRunning)
    for (id in listOf(100L, 101L, 102L)) {
      assertEquals(1f, list.findViewHolderForItemId(id)!!.itemView.alpha, 0f)
    }
  }

  @Test
  fun `an update while the list is scrolling snaps`() {
    val (list, adapter, animator) = showList(count = 8)
    animator.setListScrolling(true)

    adapter.items.add(0, adapter.items.removeAt(5))
    adapter.notifyItemMoved(5, 0)
    layOut(list)
    advance(Duration.ofMillis(34))

    assertFalse(animator.lastRunAnimated)
    assertEquals(0f, list.findViewHolderForItemId(adapter.items[1])!!.itemView.translationY, 0f)
  }

  private class RowAdapter(count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val items = MutableList(count) { it.toLong() }

    init {
      setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position]
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
      val row = FrameLayout(parent.context).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT) }
      return object : RecyclerView.ViewHolder(row) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
  }

  private data class Shown(val list: RecyclerView, val adapter: RowAdapter, val animator: ConversationListItemAnimator)

  private fun showList(count: Int): Shown {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val animator = ConversationListItemAnimator()
    val adapter = RowAdapter(count)
    val list = RecyclerView(activity).apply {
      layoutManager = LinearLayoutManager(activity)
      itemAnimator = animator
      this.adapter = adapter
    }
    activity.setContentView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT * maxOf(count, 8)))
    advance(Duration.ofMillis(34))
    check(list.childCount == count) { "expected $count rows on screen, got ${list.childCount}" }
    return Shown(list, adapter, animator)
  }

  /** The layout pass that applies the adapter change; the item animator runs on the frame after it. */
  private fun layOut(list: RecyclerView) {
    list.measure(View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(list.height, View.MeasureSpec.EXACTLY))
    list.layout(list.left, list.top, list.right, list.bottom)
  }

  private fun advance(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  private companion object {
    const val ROW_HEIGHT = 100
  }
}
