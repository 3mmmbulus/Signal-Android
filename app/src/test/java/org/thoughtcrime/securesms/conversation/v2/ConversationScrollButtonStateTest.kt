/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (chat-telegram-parity F02): the mention button carries the number of unread @mentions. */
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
  }
}
