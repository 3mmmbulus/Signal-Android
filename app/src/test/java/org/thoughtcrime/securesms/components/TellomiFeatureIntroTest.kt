/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新功能提示卡（docs/product/specs/whats-new-cards.md）：谁看得到、一次只弹一张、弹过不再弹；同 iOS `TellomiFeatureIntroTests`。 */
class TellomiFeatureIntroTest {

  private val before = 1_792_166_400_000L - 86_400_000L
  private val after = 1_792_166_400_000L + 60_000L

  @Test
  fun `only accounts registered before the card see it, and only once`() {
    for (intro in TellomiFeatureIntro.entries) {
      assertTrue(intro.id, TellomiFeatureIntro.shouldShow(intro, before, hasSeen = false))
      assertFalse("${intro.id}：之后注册的新用户", TellomiFeatureIntro.shouldShow(intro, after, hasSeen = false))
      assertFalse("${intro.id}：还没注册", TellomiFeatureIntro.shouldShow(intro, -1L, hasSeen = false))
      assertFalse("${intro.id}：读过了", TellomiFeatureIntro.shouldShow(intro, before, hasSeen = true))
    }
  }

  @Test
  fun `feature ids match the spec and the first-batch card 1 is iOS only for now`() {
    assertEquals(listOf("photo_picker_v2", "forward_grid_v1"), TellomiFeatureIntro.entries.map { it.id })
    assertNull(TellomiFeatureIntro.fromId("attachment_sheet_v2"))
  }

  @Test
  fun `first due picks candidates in order and skips the seen ones`() {
    val seen = mutableSetOf<TellomiFeatureIntro>()
    val candidates = listOf(TellomiFeatureIntro.PHOTO_PICKER, TellomiFeatureIntro.FORWARD_GRID)

    assertEquals(TellomiFeatureIntro.PHOTO_PICKER, TellomiFeatureIntro.firstDue(candidates, before) { it in seen })
    seen += TellomiFeatureIntro.PHOTO_PICKER
    assertEquals(TellomiFeatureIntro.FORWARD_GRID, TellomiFeatureIntro.firstDue(candidates, before) { it in seen })
    seen += TellomiFeatureIntro.FORWARD_GRID
    assertNull(TellomiFeatureIntro.firstDue(candidates, before) { it in seen })
    assertNull("新用户一张都不弹", TellomiFeatureIntro.firstDue(candidates, after) { false })
  }

  @Test
  fun `card contents match the spec`() {
    assertEquals(3, TellomiFeatureIntro.PHOTO_PICKER.items.size)
    assertEquals(1, TellomiFeatureIntro.FORWARD_GRID.items.size)
  }
}
