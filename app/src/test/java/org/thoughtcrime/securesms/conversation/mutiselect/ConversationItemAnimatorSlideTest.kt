/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tellomi (interaction audit A-05): a row's slide starts where it was on screen. In a conversation too short to scroll the
 * list moves itself (header pinned under the toolbar) in the same frame as the rows' layout, so the start accounts for it.
 */
class ConversationItemAnimatorSlideTest {

  @Test
  fun `a list that fills the screen does not move itself, so a row slides by its layout change`() {
    assertEquals(40f, ConversationItemAnimator.slideStart(preTop = 100, postTop = 60, parentDelta = 0f), 0f)
  }

  @Test
  fun `in a short list a reaction pill pushes the rows below it down smoothly`() {
    // The reacted row grows by 56 px; rows below keep their layout, the list moves down 56 px to keep the header pinned.
    assertEquals(-56f, ConversationItemAnimator.slideStart(preTop = 1334, postTop = 1334, parentDelta = 56f), 0f)
  }

  @Test
  fun `in a short list the rows above the one that grew stay still`() {
    // They move up 56 px in layout and the list moves down 56 px: nothing changes on screen.
    assertEquals(0f, ConversationItemAnimator.slideStart(preTop = 500, postTop = 444, parentDelta = 56f), 0f)
  }

  @Test
  fun `in a short list a new message at the end leaves the others where they are`() {
    assertEquals(0f, ConversationItemAnimator.slideStart(preTop = 800, postTop = 700, parentDelta = 100f), 0f)
  }

  @Test
  fun `in a short list removing a message pulls the rows below it up`() {
    // The removed row was 120 px tall: rows below keep their layout, the list moves up 120 px.
    assertEquals(120f, ConversationItemAnimator.slideStart(preTop = 1400, postTop = 1400, parentDelta = -120f), 0f)
  }
}
