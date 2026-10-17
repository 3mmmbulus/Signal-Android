/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Tellomi（交互审计 A-07，owner 2026-10-17）：回应快落到胶囊上的「爆开」，和 iOS（Signal-iOS#280）同一组数。 */
class TellomiReactionBurstTest {

  @Test
  fun `ten particles spread deterministically within range`() {
    val particles = TellomiReactionBurst.particles(emojiSize = 42f)
    assertThat(particles.size).isEqualTo(10)
    assertThat(particles).isEqualTo(TellomiReactionBurst.particles(emojiSize = 42f))
    val reach = 42f * TellomiReactionBurst.REACH_FACTOR
    particles.forEach {
      assertThat(it.distance >= reach * 0.8f - 1e-3f && it.distance <= reach * 1.2f + 1e-3f).isTrue()
      assertThat(it.scale >= 0.35f && it.scale <= 0.5f).isTrue()
      assertThat(it.delay).isLessThanOrEqualTo(0.04f + 1e-6f)
    }
    val directions = particles.map { (atan2(sin(it.angle), cos(it.angle)) * 100).roundToInt() }.toSet()
    assertThat(directions.size).isEqualTo(10)
  }

  @Test
  fun `the burst starts once 70 percent of the flight is covered`() {
    assertThat(TellomiReactionBurst.shouldTrigger(startDistance = 300f, remainingDistance = 100f)).isFalse()
    assertThat(TellomiReactionBurst.shouldTrigger(startDistance = 300f, remainingDistance = 90f)).isTrue()
    assertThat(TellomiReactionBurst.shouldTrigger(startDistance = 0f, remainingDistance = 0f)).isTrue()
    assertThat(TellomiReactionBurst.DURATION_MS).isEqualTo(700L)
    assertThat(TellomiReactionBurst.RING_DURATION_MS).isEqualTo(350L)
  }

  @Test
  fun `keyframes interpolate between their stops`() {
    val times = floatArrayOf(0f, 0.1f, 0.55f, 1f)
    val values = floatArrayOf(0f, 1f, 1f, 0f)
    assertThat(TellomiReactionBurst.keyframe(0f, times, values)).isEqualTo(0f)
    assertThat(TellomiReactionBurst.keyframe(0.05f, times, values)).isEqualTo(0.5f)
    assertThat(TellomiReactionBurst.keyframe(0.3f, times, values)).isEqualTo(1f)
    assertThat(TellomiReactionBurst.keyframe(1f, times, values)).isEqualTo(0f)
  }
}
