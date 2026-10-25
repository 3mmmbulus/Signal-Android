/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tellomi (TF43 P1): which links get the long-press link menu, and its order (Open / Copy link / Share). */
class TellomiLinkMenuTest {

  @Test
  fun `web links get the link menu`() {
    assertTrue(TellomiLinkMenu.isWebLink("https://baidu.com"))
    assertTrue(TellomiLinkMenu.isWebLink("http://signal.org/a"))
    assertTrue(TellomiLinkMenu.isWebLink("HTTPS://X.COM"))
  }

  @Test
  fun `email and phone links keep their old long press`() {
    assertFalse(TellomiLinkMenu.isWebLink("mailto:a@b.com"))
    assertFalse(TellomiLinkMenu.isWebLink("tel:+8613800000000"))
    assertFalse(TellomiLinkMenu.isWebLink("sgnl://signal.group"))
  }

  @Test
  fun `menu order is open copy share`() {
    assertEquals(listOf(TellomiLinkMenu.Action.OPEN, TellomiLinkMenu.Action.COPY, TellomiLinkMenu.Action.SHARE), TellomiLinkMenu.ACTIONS)
  }
}
