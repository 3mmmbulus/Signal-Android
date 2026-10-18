/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (chat-telegram-parity F01): the record of my messages with reactions not yet seen in the chat. */
class TellomiUnseenReactionsTest {

  @Test
  fun `encodes and decodes threads in order`() {
    val map = linkedMapOf(3L to linkedSetOf(30L, 31L), 1L to linkedSetOf(10L))

    val encoded = TellomiUnseenReactions.encode(map)

    assertEquals("3:30,31;1:10", encoded)
    assertEquals(map, TellomiUnseenReactions.decode(encoded))
  }

  @Test
  fun `decoding skips what it cannot read`() {
    val decoded = TellomiUnseenReactions.decode("x:1;2:;4:40,y,41;:5;6")

    assertEquals(linkedMapOf(4L to linkedSetOf(40L, 41L)), decoded)
    assertTrue(TellomiUnseenReactions.decode("").isEmpty())
  }

  @Test
  fun `adding twice records once`() {
    val map = linkedMapOf<Long, LinkedHashSet<Long>>()

    assertTrue(TellomiUnseenReactions.add(map, 1L, 10L, max = 5))
    assertFalse(TellomiUnseenReactions.add(map, 1L, 10L, max = 5))
    assertEquals(linkedSetOf(10L), map[1L])
  }

  @Test
  fun `over the cap drops the earliest recorded`() {
    val map = linkedMapOf<Long, LinkedHashSet<Long>>()

    listOf(10L, 11L, 12L, 13L).forEach { TellomiUnseenReactions.add(map, 1L, it, max = 3) }

    assertEquals(listOf(11L, 12L, 13L), map[1L]!!.toList())
  }

  @Test
  fun `removing the last one drops the thread`() {
    val map = linkedMapOf(1L to linkedSetOf(10L, 11L), 2L to linkedSetOf(20L))

    assertTrue(TellomiUnseenReactions.remove(map, 1L, listOf(10L)))
    assertEquals(linkedSetOf(11L), map[1L])

    assertTrue(TellomiUnseenReactions.remove(map, 1L, listOf(11L, 99L)))
    assertFalse(map.containsKey(1L))
    assertEquals(linkedSetOf(20L), map[2L])
  }

  @Test
  fun `removing what was never recorded changes nothing`() {
    val map = linkedMapOf(2L to linkedSetOf(20L))

    assertFalse(TellomiUnseenReactions.remove(map, 1L, listOf(10L)))
    assertFalse(TellomiUnseenReactions.remove(map, 2L, listOf(21L)))
    assertEquals(linkedMapOf(2L to linkedSetOf(20L)), map)
  }
}
