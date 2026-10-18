/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect

import android.app.Application
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi (interaction audit A-05): in a conversation too short to scroll the whole list moves to keep its header pinned under
 * the toolbar. Moving down (the content grew) it jumps and the rows start their slides that much higher; moving up (the
 * content shrank) it is held and eased up with the rows, so nothing starts below the list's bottom edge and gets cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ConversationItemAnimatorListTranslationTest {

  private val listTranslation = FakeListTranslation(delta = 0f)

  private val animator = ConversationItemAnimator(
    isInMultiSelectMode = { false },
    shouldPlayMessageAnimations = { true },
    isParentFilled = { false },
    shouldUseSlideAnimation = { true },
    listTranslation = listTranslation
  )

  private fun row() = object : RecyclerView.ViewHolder(View(ApplicationProvider.getApplicationContext())) {}

  private fun at(top: Int) = RecyclerView.ItemAnimator.ItemHolderInfo().apply {
    this.top = top
    bottom = top + 97
  }

  @Test
  fun `content shrinks - a row above the gap starts where it was and the list is held`() {
    // A 97 px message below it went: its layout moves down 97, the list will ease up 97.
    listTranslation.delta = -97f
    val above = row()

    assertTrue(animator.animatePersistence(above, at(1569), at(1666)))

    assertEquals(-97f, above.itemView.translationY, 0f)
    assertEquals(listOf(97f), listTranslation.offsets)
  }

  @Test
  fun `content shrinks - a row below the gap keeps its layout and rides up with the list`() {
    listTranslation.delta = -97f
    val below = row()

    assertFalse(animator.animatePersistence(below, at(1846), at(1846)))

    assertEquals(0f, below.itemView.translationY, 0f)
  }

  @Test
  fun `content grows - the list jumps down and a row below starts that much higher`() {
    // A reaction pill made the row above it 57 px taller.
    listTranslation.delta = 57f
    val below = row()

    assertTrue(animator.animatePersistence(below, at(1692), at(1692)))

    assertEquals(-57f, below.itemView.translationY, 0f)
    assertEquals(listOf(0f), listTranslation.offsets)
  }

  @Test
  fun `content grows - a row above stays put without an animation`() {
    listTranslation.delta = 57f
    val above = row()

    assertFalse(animator.animatePersistence(above, at(697), at(640)))

    assertEquals(0f, above.itemView.translationY, 0f)
  }

  @Test
  fun `a list that scrolls is never held or eased`() {
    val moved = row()

    assertTrue(animator.animatePersistence(moved, at(800), at(700)))

    assertEquals(100f, moved.itemView.translationY, 0f)
    assertTrue(listTranslation.offsets.isEmpty())
  }

  @Test
  fun `content that grows while the list is still easing up stops the easing and jumps`() {
    listTranslation.delta = -97f
    animator.animatePersistence(row(), at(1569), at(1666))
    animator.runPendingAnimations()

    listTranslation.delta = 57f
    animator.animatePersistence(row(), at(1692), at(1692))

    // Held at 97, the easing started from there, then let go at once for the jump.
    assertEquals(97f, listTranslation.offsets.first(), 0f)
    assertEquals(0f, listTranslation.offsets.last(), 0f)
  }

  @Test
  fun `ending every animation lets the held list go`() {
    listTranslation.delta = -97f
    animator.animatePersistence(row(), at(1569), at(1666))
    animator.runPendingAnimations()
    assertTrue(animator.isRunning)

    animator.endAnimations()

    assertEquals(0f, listTranslation.offsets.last(), 0f)
    assertFalse(animator.isRunning)
  }
}
