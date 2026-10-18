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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tellomi (interaction audit A-05): in a conversation too short to scroll the list moves itself together with a removal, and
 * the removed message fades where it was on screen. However the fade ends, the view goes back to the pool unmoved and opaque,
 * ready for the next message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ConversationItemAnimatorFadeOutTest {

  private val removedRowHeight = 97f

  // The list is about to move up by the removed row's height to keep its header pinned.
  private val listTranslation = FakeListTranslation(delta = -removedRowHeight)

  private val animator = ConversationItemAnimator(
    isInMultiSelectMode = { false },
    shouldPlayMessageAnimations = { true },
    isParentFilled = { false },
    shouldUseSlideAnimation = { true },
    listTranslation = listTranslation
  )

  private val holder = object : RecyclerView.ViewHolder(View(ApplicationProvider.getApplicationContext())) {}

  private fun remove(): Boolean {
    return animator.animateDisappearance(holder, RecyclerView.ItemAnimator.ItemHolderInfo(), null)
  }

  @Test
  fun `in a short list the list is held while a removed message starts fading where it was`() {
    assertTrue(remove())

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(listOf(removedRowHeight), listTranslation.offsets)
  }

  @Test
  fun `a removed message stays put when the list jumps down in the same frame`() {
    listTranslation.delta = 40f

    assertTrue(remove())

    assertEquals(-40f, holder.itemView.translationY, 0f)
    assertEquals(listOf(0f), listTranslation.offsets)
  }

  @Test
  fun `a fade ended before it starts gives the view back unmoved`() {
    listTranslation.delta = 40f
    remove()

    animator.endAnimation(holder)

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(1f, holder.itemView.alpha, 0f)
  }

  @Test
  fun `ending every animation before the fade starts gives the view back unmoved and lets the list go`() {
    remove()

    animator.endAnimations()

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(1f, holder.itemView.alpha, 0f)
    assertEquals(0f, listTranslation.offsets.last(), 0f)
  }

  @Test
  fun `a fade ended while it runs gives the view back unmoved`() {
    remove()
    animator.runPendingAnimations()

    animator.endAnimation(holder)

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(1f, holder.itemView.alpha, 0f)
  }
}

/** Records the offsets the animator holds the list at, in order. */
class FakeListTranslation(var delta: Float) : ConversationItemAnimator.ListTranslation {
  val offsets = mutableListOf<Float>()

  override fun pendingDelta(): Float = delta

  override fun setEasingOffset(offset: Float) {
    offsets += offset
  }
}
