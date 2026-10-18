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
 * Tellomi (interaction audit A-05): in a conversation too short to scroll the list moves itself in the same frame as a
 * removal, so the removed message is held where it was on screen while it fades. However the fade ends, the view goes back
 * to the pool unmoved and opaque, ready for the next message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ConversationItemAnimatorFadeOutTest {

  private val removedRowHeight = 97f

  private val animator = ConversationItemAnimator(
    isInMultiSelectMode = { false },
    shouldPlayMessageAnimations = { true },
    isParentFilled = { false },
    shouldUseSlideAnimation = { true },
    // The list moves up by the removed row's height to keep its header pinned.
    parentTranslationDelta = { -removedRowHeight }
  )

  private val holder = object : RecyclerView.ViewHolder(View(ApplicationProvider.getApplicationContext())) {}

  private fun remove(): Boolean {
    return animator.animateDisappearance(holder, RecyclerView.ItemAnimator.ItemHolderInfo(), null)
  }

  @Test
  fun `in a short list a removed message stays where it was on screen while it fades`() {
    assertTrue(remove())
    assertEquals(removedRowHeight, holder.itemView.translationY, 0f)
  }

  @Test
  fun `a fade ended before it starts gives the view back unmoved`() {
    remove()

    animator.endAnimation(holder)

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(1f, holder.itemView.alpha, 0f)
  }

  @Test
  fun `ending every animation before the fade starts gives the view back unmoved`() {
    remove()

    animator.endAnimations()

    assertEquals(0f, holder.itemView.translationY, 0f)
    assertEquals(1f, holder.itemView.alpha, 0f)
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
