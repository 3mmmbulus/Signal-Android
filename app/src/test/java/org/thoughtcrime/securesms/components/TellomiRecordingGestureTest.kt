/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import org.junit.Test

/** Tellomi (interaction audit A-12): how far the finger is towards cancelling or locking a voice note. */
class TellomiRecordingGestureTest {

  @Test
  fun `cancel progress grows as the finger moves towards the cancel line, left to right layout`() {
    // Pressed at 900 px, cancel line at 540 px.
    assertThat(TellomiRecordingGesture.cancelProgress(startX = 900f, x = 900f, cancelX = 540f)).isEqualTo(0f)
    assertThat(TellomiRecordingGesture.cancelProgress(startX = 900f, x = 720f, cancelX = 540f)).isCloseTo(0.5f, 0.001f)
    assertThat(TellomiRecordingGesture.cancelProgress(startX = 900f, x = 400f, cancelX = 540f)).isEqualTo(1f)
    // Moving the other way does not go below 0.
    assertThat(TellomiRecordingGesture.cancelProgress(startX = 900f, x = 1000f, cancelX = 540f)).isEqualTo(0f)
  }

  @Test
  fun `cancel progress works the same way in a right to left layout`() {
    // Pressed at 180 px, cancel line at 648 px (60 % of 1080).
    assertThat(TellomiRecordingGesture.cancelProgress(startX = 180f, x = 414f, cancelX = 648f)).isCloseTo(0.5f, 0.001f)
  }

  @Test
  fun `lock progress is the share of the lock distance already moved up`() {
    assertThat(TellomiRecordingGesture.lockProgress(offsetY = 0f, lockOffsetY = -150f)).isEqualTo(0f)
    assertThat(TellomiRecordingGesture.lockProgress(offsetY = -75f, lockOffsetY = -150f)).isCloseTo(0.5f, 0.001f)
    assertThat(TellomiRecordingGesture.lockProgress(offsetY = -200f, lockOffsetY = -150f)).isEqualTo(1f)
  }

  @Test
  fun `lerp goes from the first value to the second`() {
    assertThat(TellomiRecordingGesture.lerp(1f, 0.5f, 0f)).isEqualTo(1f)
    assertThat(TellomiRecordingGesture.lerp(1f, 0.5f, 1f)).isEqualTo(0.5f)
  }
}
