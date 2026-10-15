/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.reactions.TellomiReactionPillAnimator.Change
import org.thoughtcrime.securesms.reactions.TellomiReactionPillAnimator.PillState

/** Tellomi (interaction audit A-07): which animation a reaction pill gets when the same message's reactions change. */
class TellomiReactionPillAnimatorTest {

  @Test
  fun `a reaction that had no pill pops in`() {
    assertEquals(Change.ADDED, TellomiReactionPillAnimator.changeFor(null, PillState(count = 1, userWasSender = true)))
  }

  @Test
  fun `the count rolls up or down`() {
    assertEquals(Change.COUNT_UP, TellomiReactionPillAnimator.changeFor(PillState(1, false), PillState(2, true)))
    assertEquals(Change.COUNT_DOWN, TellomiReactionPillAnimator.changeFor(PillState(3, true), PillState(2, false)))
  }

  @Test
  fun `only becoming or no longer being mine bumps the pill`() {
    assertEquals(Change.RESTYLED, TellomiReactionPillAnimator.changeFor(PillState(2, false), PillState(2, true)))
  }

  @Test
  fun `an unchanged pill does not move`() {
    assertEquals(Change.NONE, TellomiReactionPillAnimator.changeFor(PillState(2, true), PillState(2, true)))
  }
}
