/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (interaction audit A-05): only a real removal fades out; a row pushed off screen does not. */
class ConversationItemAnimatorRemovalTest {

  @Test
  fun `a removed message with no position and no layout afterwards fades out`() {
    assertTrue(ConversationItemAnimator.isRemoval(postLayoutInfo = null, adapterPosition = RecyclerView.NO_POSITION))
  }

  @Test
  fun `a message pushed off screen still has a position and does not fade`() {
    assertFalse(ConversationItemAnimator.isRemoval(postLayoutInfo = null, adapterPosition = 12))
  }

  @Test
  fun `a message that still has a layout afterwards does not fade`() {
    assertFalse(ConversationItemAnimator.isRemoval(postLayoutInfo = RecyclerView.ItemAnimator.ItemHolderInfo(), adapterPosition = RecyclerView.NO_POSITION))
  }
}
