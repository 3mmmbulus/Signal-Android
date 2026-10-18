/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (chat-telegram-parity F01/F02): the mention and reaction buttons, with their numbers. */
class ConversationScrollButtonStateTest {

  @Test
  fun `unread mentions show the button with their number`() {
    val state = ConversationScrollButtonState(mentionCount = 3)

    assertTrue(state.hasMentions)
    assertEquals(3, state.mentionCount)
  }

  @Test
  fun `no unread mentions hide the button`() {
    assertFalse(ConversationScrollButtonState(mentionCount = 0).hasMentions)
    assertFalse(ConversationScrollButtonState(mentionCount = 0).showMentionButton)
  }

  @Test
  fun `the mention button shows at the bottom of the chat too`() {
    val state = ConversationScrollButtonState(mentionCount = 1, showScrollButtonsForScrollPosition = false, willScrollToBottomOnNewMessage = true)

    assertFalse(state.showScrollButtons)
    assertTrue(state.showMentionButton)
  }

  @Test
  fun `unseen reactions show their button with the number of messages`() {
    val state = ConversationScrollButtonState(unseenReactionMessageIds = listOf(4L, 9L))

    assertTrue(state.showReactionButton)
    assertEquals(2, state.unseenReactionCount)
    assertFalse(ConversationScrollButtonState().showReactionButton)
  }

  @Test
  fun `the reaction overlay hides the mention and reaction buttons`() {
    val state = ConversationScrollButtonState(hideScrollButtonsForReactionOverlay = true, mentionCount = 1, unseenReactionMessageIds = listOf(4L))

    assertFalse(state.showMentionButton)
    assertFalse(state.showReactionButton)
  }
}
