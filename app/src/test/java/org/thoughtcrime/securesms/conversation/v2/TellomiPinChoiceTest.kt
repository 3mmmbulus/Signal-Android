/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thoughtcrime.securesms.conversation.v2.TellomiPinChoice.FOR_BOTH
import org.thoughtcrime.securesms.conversation.v2.TellomiPinChoice.FOR_EVERYONE
import org.thoughtcrime.securesms.conversation.v2.TellomiPinChoice.FOR_EVERYONE_SILENT
import org.thoughtcrime.securesms.conversation.v2.TellomiPinChoice.FOR_ME

/** Tellomi (ADR-0081 §4.6, pinned-messages.md 3.1): what the pin prompt offers where. */
class TellomiPinChoiceTest {

  @Test
  fun `a 1-1 chat offers both or just me, with no default`() {
    assertEquals(listOf(FOR_BOTH, FOR_ME), TellomiPinChoice.choicesFor(isNoteToSelf = false, isGroup = false, canPinForEveryone = true))
    assertEquals("pinning in a 1:1 chat needs no permission", listOf(FOR_BOTH, FOR_ME), TellomiPinChoice.choicesFor(isNoteToSelf = false, isGroup = false, canPinForEveryone = false))
  }

  @Test
  fun `a group offers notify, silent and just me to those allowed to pin`() {
    assertEquals(listOf(FOR_EVERYONE, FOR_EVERYONE_SILENT, FOR_ME), TellomiPinChoice.choicesFor(isNoteToSelf = false, isGroup = true, canPinForEveryone = true))
  }

  @Test
  fun `without permission a group member can still pin for themselves`() {
    assertEquals(listOf(FOR_ME), TellomiPinChoice.choicesFor(isNoteToSelf = false, isGroup = true, canPinForEveryone = false))
  }

  @Test
  fun `saved messages pin without asking`() {
    assertNull(TellomiPinChoice.choicesFor(isNoteToSelf = true, isGroup = false, canPinForEveryone = true))
  }

  @Test
  fun `a pin for me only can always be taken back`() {
    assertTrue(TellomiPinChoice.canUnpin(isPinnedForMe = true, canPinForEveryone = false, isInactiveGroup = false))
    assertTrue("even after leaving the group", TellomiPinChoice.canUnpin(isPinnedForMe = true, canPinForEveryone = false, isInactiveGroup = true))
  }

  @Test
  fun `other pins still need permission in a group that is still there`() {
    assertTrue(TellomiPinChoice.canUnpin(isPinnedForMe = false, canPinForEveryone = true, isInactiveGroup = false))
    assertFalse(TellomiPinChoice.canUnpin(isPinnedForMe = false, canPinForEveryone = false, isInactiveGroup = false))
    assertFalse(TellomiPinChoice.canUnpin(isPinnedForMe = false, canPinForEveryone = true, isInactiveGroup = true))
  }
}
