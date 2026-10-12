/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.video

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class TellomiVideoTrimTest {

  @Test
  fun `A trimmed clip is at least one second long`() {
    assertThat(TellomiVideoTrim.MINIMUM_RANGE_US).isEqualTo(1_000_000L)
  }

  @Test
  fun `Only videos of at least one and a half seconds can be trimmed`() {
    assertThat(TellomiVideoTrim.isTrimmable(0L)).isFalse()
    assertThat(TellomiVideoTrim.isTrimmable(1_499_999L)).isFalse()
    assertThat(TellomiVideoTrim.isTrimmable(1_500_000L)).isTrue()
    assertThat(TellomiVideoTrim.isTrimmable(60_000_000L)).isTrue()
  }
}
