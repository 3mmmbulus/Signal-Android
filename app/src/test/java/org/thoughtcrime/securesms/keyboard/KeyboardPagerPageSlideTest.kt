/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thoughtcrime.securesms.R

/** Tellomi (interaction audit A-35): media keyboard pages slide past each other in the order of their tabs. */
class KeyboardPagerPageSlideTest {

  private val forward = R.anim.tellomi_keyboard_page_from_end to R.anim.tellomi_keyboard_page_to_start
  private val back = R.anim.tellomi_keyboard_page_from_start to R.anim.tellomi_keyboard_page_to_end

  @Test
  fun `a later tab slides in from the end`() {
    assertEquals(forward, KeyboardPagerFragment.pageSlide(from = KeyboardPage.EMOJI, to = KeyboardPage.GIF))
    assertEquals(forward, KeyboardPagerFragment.pageSlide(from = KeyboardPage.EMOJI, to = KeyboardPage.STICKER))
    assertEquals(forward, KeyboardPagerFragment.pageSlide(from = KeyboardPage.STICKER, to = KeyboardPage.GIF))
  }

  @Test
  fun `an earlier tab slides in from the start`() {
    assertEquals(back, KeyboardPagerFragment.pageSlide(from = KeyboardPage.GIF, to = KeyboardPage.EMOJI))
    assertEquals(back, KeyboardPagerFragment.pageSlide(from = KeyboardPage.GIF, to = KeyboardPage.STICKER))
  }

  @Test
  fun `the first page shown and the same page again do not slide`() {
    assertNull(KeyboardPagerFragment.pageSlide(from = null, to = KeyboardPage.EMOJI))
    assertNull(KeyboardPagerFragment.pageSlide(from = KeyboardPage.GIF, to = KeyboardPage.GIF))
  }
}
