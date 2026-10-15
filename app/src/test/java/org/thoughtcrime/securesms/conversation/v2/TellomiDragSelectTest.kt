/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (interaction audit A-15): dragging a selection to the list's edge scrolls it, faster the closer it gets. */
class TellomiDragSelectTest {

  @Test
  fun `away from the edges the list does not scroll`() {
    assertEquals(0, TellomiDragSelect.scrollStep(y = 500f, height = 1000f, edge = 100f, maxStep = 20f))
    assertEquals(0, TellomiDragSelect.scrollStep(y = 100f, height = 1000f, edge = 100f, maxStep = 20f))
    assertEquals(0, TellomiDragSelect.scrollStep(y = 900f, height = 1000f, edge = 100f, maxStep = 20f))
  }

  @Test
  fun `near the top it scrolls up and near the bottom it scrolls down`() {
    assertTrue(TellomiDragSelect.scrollStep(y = 50f, height = 1000f, edge = 100f, maxStep = 20f) < 0)
    assertTrue(TellomiDragSelect.scrollStep(y = 950f, height = 1000f, edge = 100f, maxStep = 20f) > 0)
  }

  @Test
  fun `it scrolls faster the closer the finger is to the edge, up to the maximum`() {
    assertEquals(-10, TellomiDragSelect.scrollStep(y = 50f, height = 1000f, edge = 100f, maxStep = 20f))
    assertEquals(-20, TellomiDragSelect.scrollStep(y = 0f, height = 1000f, edge = 100f, maxStep = 20f))
    assertEquals(-20, TellomiDragSelect.scrollStep(y = -40f, height = 1000f, edge = 100f, maxStep = 20f))
    assertEquals(20, TellomiDragSelect.scrollStep(y = 1040f, height = 1000f, edge = 100f, maxStep = 20f))
  }

  @Test
  fun `a list with no height never scrolls`() {
    assertEquals(0, TellomiDragSelect.scrollStep(y = 0f, height = 0f, edge = 100f, maxStep = 20f))
  }
}
