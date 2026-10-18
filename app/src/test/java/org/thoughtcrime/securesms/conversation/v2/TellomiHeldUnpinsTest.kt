/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tellomi (pinned-messages.md 3.5 / 3.6): unpins wait out their "Undo", then go out once, and stay off the bar until they're done. */
class TellomiHeldUnpinsTest {

  @Test
  fun `undo puts them back and nothing goes out`() {
    val unpins = TellomiHeldUnpins()
    unpins.hold(listOf(1L, 2L))
    assertEquals(setOf(1L, 2L), unpins.held)

    assertEquals(setOf(1L, 2L), unpins.undo(listOf(1L, 2L)))
    assertEquals(emptySet<Long>(), unpins.held)
    assertEquals("too late to send", emptySet<Long>(), unpins.commit(listOf(1L, 2L)))
  }

  @Test
  fun `after the wait they go out once and stay off the bar until the pins are gone`() {
    val unpins = TellomiHeldUnpins()
    unpins.hold(listOf(1L, 2L))

    assertEquals(setOf(1L, 2L), unpins.commit(listOf(1L, 2L)))
    assertEquals("not twice", emptySet<Long>(), unpins.commit(listOf(1L, 2L)))
    assertEquals("too late to undo", emptySet<Long>(), unpins.undo(listOf(1L)))
    assertEquals("still sending: still off the bar", setOf(1L, 2L), unpins.held)

    unpins.prune(pinnedMessageIds = setOf(2L, 7L))
    assertEquals("1 is gone, 2 not yet", setOf(2L), unpins.held)
    unpins.prune(pinnedMessageIds = setOf(7L))
    assertEquals(emptySet<Long>(), unpins.held)
  }

  @Test
  fun `a pin that couldn't be unpinned comes back`() {
    val unpins = TellomiHeldUnpins()
    unpins.hold(listOf(1L, 2L))
    unpins.commit(listOf(1L, 2L))

    unpins.failed(listOf(2L))
    assertEquals(setOf(1L), unpins.held)
  }

  @Test
  fun `a pin still waiting on undo stays off the bar while the pins change, and goes out when the chat closes`() {
    val unpins = TellomiHeldUnpins()
    unpins.hold(listOf(1L))
    unpins.prune(pinnedMessageIds = emptySet())
    assertEquals(setOf(1L), unpins.held)

    unpins.hold(listOf(5L))
    unpins.commit(listOf(5L))
    assertEquals("only what's still waiting", setOf(1L), unpins.commitAll())
    assertEquals(emptySet<Long>(), unpins.commitAll())
  }
}
