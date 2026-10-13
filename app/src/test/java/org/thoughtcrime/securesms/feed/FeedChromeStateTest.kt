/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedChromeStateTest {

  private fun chrome() = FeedChromeState(hideAfterPx = 48f, showAfterPx = 12f).apply { onNearTopChanged(false) }

  @Test
  fun `hides after 48 toward older content, shows after 12 back`() {
    val chrome = chrome()
    chrome.onScroll(30f)
    assertThat(chrome.visible).isTrue()
    chrome.onScroll(18f)
    assertThat(chrome.visible).isFalse()

    chrome.onScroll(-8f)
    assertThat(chrome.visible).isFalse()
    chrome.onScroll(-4f)
    assertThat(chrome.visible).isTrue()
  }

  @Test
  fun `changing direction starts the count over`() {
    val chrome = chrome()
    chrome.onScroll(40f)
    chrome.onScroll(-1f)
    chrome.onScroll(40f)
    assertThat(chrome.visible).isTrue()
  }

  @Test
  fun `near the top it always shows`() {
    val chrome = chrome()
    chrome.onScroll(100f)
    assertThat(chrome.visible).isFalse()
    chrome.onNearTopChanged(true)
    assertThat(chrome.visible).isTrue()
    chrome.onScroll(100f)
    assertThat(chrome.visible).isTrue()
  }

  @Test
  fun `reset brings it back`() {
    val chrome = chrome()
    chrome.onScroll(100f)
    chrome.reset()
    assertThat(chrome.visible).isTrue()
  }
}
