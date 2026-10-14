/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1522 E-1）：8 个比例预设，顺序和写法照 Telegram（`CropView.showAspectRatioDialog`），竖的框倒过来。
 */
class CropAspectRatioTest {

  @Test
  fun `The presets are Telegram's eight, in its order`() {
    assertEquals(8, CropAspectRatio.entries.size)
    assertNull(CropAspectRatio.ORIGINAL.sides(portrait = false))
    assertNull(CropAspectRatio.SQUARE.sides(portrait = true))
    assertEquals(
      listOf(3 to 2, 5 to 3, 4 to 3, 5 to 4, 7 to 5, 16 to 9),
      CropAspectRatio.entries.drop(2).map { it.sides(portrait = false) }
    )
    assertEquals(
      listOf(2 to 3, 3 to 5, 3 to 4, 4 to 5, 5 to 7, 9 to 16),
      CropAspectRatio.entries.drop(2).map { it.sides(portrait = true) }
    )
  }

  @Test
  fun `Each preset gives width over height for the crop's orientation`() {
    assertEquals(1.5f, CropAspectRatio.THREE_BY_TWO.widthToHeight(originalWidthToHeight = 0.5f, portrait = false), 0.0001f)
    assertEquals(2f / 3f, CropAspectRatio.THREE_BY_TWO.widthToHeight(originalWidthToHeight = 0.5f, portrait = true), 0.0001f)
    assertEquals(1f, CropAspectRatio.SQUARE.widthToHeight(originalWidthToHeight = 0.5f, portrait = true), 0.0001f)
    assertEquals(0.75f, CropAspectRatio.ORIGINAL.widthToHeight(originalWidthToHeight = 0.75f, portrait = false), 0.0001f)
  }
}
