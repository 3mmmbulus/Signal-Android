/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui.motion

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.core.ui.motion.TellomiMotion.DeviceSignals
import org.signal.core.ui.motion.TellomiMotion.SpringToken
import org.signal.core.ui.motion.TellomiMotion.Tier
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

class TellomiMotionTest {

  private val goodPhone = DeviceSignals(animatorsEnabled = true, powerSave = false, lowRamDevice = false, memoryClassMb = 256)

  /** 数值必须等于标准第三节：Material 3 MotionScheme 的官方 token（Standard / Expressive），Move 的阻尼按 owner 决定对齐 iOS。 */
  @Test
  fun `tokens match the Material 3 motion scheme values the standard names`() {
    assertEquals(SpringToken(1400f, 0.9f), TellomiMotion.Press) // Standard 空间快
    assertEquals(SpringToken(3800f, 1f), TellomiMotion.PressHighlight) // Effects 快
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Release) // Expressive 空间快
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Snap)
    assertEquals(SpringToken(380f, 0.7f), TellomiMotion.Move) // Expressive 空间默认的刚度，阻尼对齐 iOS move
    assertEquals(SpringToken(200f, 0.8f), TellomiMotion.Large) // Expressive 空间慢
    assertEquals(SpringToken(800f, 0.6f), TellomiMotion.Emphasis)
    assertEquals(SpringToken(1600f, 1f), TellomiMotion.Fade) // Effects 默认
  }

  /** 按下、滚动位置、透明度这三类不许有看得见的回弹（标准第三节）。 */
  @Test
  fun `press scroll and fade have no visible overshoot`() {
    assertTrue(overshoot(TellomiMotion.Press) < 0.002)
    assertEquals(0.0, overshoot(TellomiMotion.Scroll), 0.0)
    assertEquals(0.0, overshoot(TellomiMotion.PressHighlight), 0.0)
    assertEquals(0.0, overshoot(TellomiMotion.Fade), 0.0)
  }

  /**
   * owner 2026-09-26 选了「活泼」，又定了「明显回弹，两端一致」：菜单 / Sheet / 共享元素回弹约 5%，查看器约 1.5%，
   * 小元素与回应落定更弹。与 iOS 的 `TellomiMotionTest` 用同一个过冲公式、同一组区间。
   */
  @Test
  fun `lively tokens do bounce`() {
    assertEquals(0.046, overshoot(TellomiMotion.Move), 0.005)
    assertEquals(0.015, overshoot(TellomiMotion.Large), 0.002)
    assertTrue(overshoot(TellomiMotion.Snap) > 0.04)
    assertTrue(overshoot(TellomiMotion.Release) > 0.04)
    assertTrue(overshoot(TellomiMotion.Emphasis) > 0.09)
  }

  /** 与 iOS 同一个回弹：iOS move (0.4 s, bounce 0.3)、large (0.45 s, bounce 0.2)。 */
  @Test
  fun `move and large bounce the same as on iOS`() {
    assertEquals(0.3f, TellomiMotion.Move.bounce, 1e-6f)
    assertEquals(0.2f, TellomiMotion.Large.bounce, 1e-6f)
  }

  @Test
  fun `perceptual duration converts the way iOS duration and bounce do`() {
    assertEquals(2 * PI / sqrt(380.0), TellomiMotion.Move.perceptualDurationSeconds, 1e-9)
    assertEquals(0.322, TellomiMotion.Move.perceptualDurationSeconds, 0.001)
    assertEquals(0.3f, TellomiMotion.Move.bounce, 1e-6f)
  }

  @Test
  fun `compose spec carries the token unchanged`() {
    val spec: SpringSpec<Float> = TellomiMotion.spec(TellomiMotion.Move)
    assertEquals(380f, spec.stiffness, 0f)
    assertEquals(0.7f, spec.dampingRatio, 0f)
  }

  @Test
  fun `a capable phone gets the full tier`() {
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone))
    assertTrue(TellomiMotion.allowsLiveBlur(Tier.FULL, sdkInt = 34))
    assertTrue(TellomiMotion.allowsFlourish(Tier.FULL))
  }

  /** owner 2026-09-26：Android 11 及以下按完整档，飞入照放，只是没有实时模糊（RenderEffect 要 API 31）。 */
  @Test
  fun `before android 12 the full tier plays everything but blur`() {
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone))
    assertFalse(TellomiMotion.allowsLiveBlur(Tier.FULL, sdkInt = 30))
    assertTrue(TellomiMotion.allowsLiveBlur(Tier.FULL, sdkInt = 31))
    assertTrue(TellomiMotion.allowsFlourish(Tier.FULL))
  }

  @Test
  fun `animations turned off wins over everything`() {
    assertEquals(Tier.MINIMAL, TellomiMotion.tierFor(goodPhone.copy(animatorsEnabled = false)))
    assertEquals(Tier.MINIMAL, TellomiMotion.tierFor(goodPhone.copy(animatorsEnabled = false, powerSave = true, lowRamDevice = true)))
  }

  @Test
  fun `battery saver low ram and small heap phones are simplified`() {
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(powerSave = true)))
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(lowRamDevice = true)))
    assertEquals(Tier.SIMPLIFIED, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = TellomiMotion.LOW_MEMORY_CLASS_MB)))
  }

  /** memoryClass 拿不到（0）时不因此降档，由其它信号决定。 */
  @Test
  fun `an unknown memory class does not demote the phone`() {
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = 0)))
    assertEquals(Tier.FULL, TellomiMotion.tierFor(goodPhone.copy(memoryClassMb = TellomiMotion.LOW_MEMORY_CLASS_MB + 1)))
  }

  @Test
  fun `only the full tier blurs or plays flourishes`() {
    assertFalse(TellomiMotion.allowsLiveBlur(Tier.SIMPLIFIED, sdkInt = 34))
    assertFalse(TellomiMotion.allowsLiveBlur(Tier.MINIMAL, sdkInt = 34))
    assertFalse(TellomiMotion.allowsFlourish(Tier.SIMPLIFIED))
    assertFalse(TellomiMotion.allowsFlourish(Tier.MINIMAL))
  }

  /** 欠阻尼弹簧从 0 到 1 的最大过冲比例：exp(−πζ / √(1 − ζ²))；ζ ≥ 1 时为 0。 */
  private fun overshoot(token: SpringToken): Double {
    val z = token.dampingRatio.toDouble()
    return if (z >= 1.0) 0.0 else exp(-PI * z / sqrt(1 - z * z))
  }

  /** 发送动画的数：时长是 owner 选的 280 ms；曲线、淡入照 Telegram 两端；和 iOS `TellomiMotion.sendFlight` 同一组。 */
  @Test
  fun `send flight numbers`() {
    assertEquals(280L, TellomiMotion.SendFlight.DURATION_MS)
    assertEquals(120L, TellomiMotion.SendFlight.BUBBLE_FADE_IN_MS)
    assertEquals(listOf(0.23f, 1f, 0.32f, 1f), TellomiMotion.SendFlight.X_CURVE.toList())
    assertEquals(listOf(0.19919f, 0.01064f, 0.27921f, 0.91025f), TellomiMotion.SendFlight.Y_CURVE.toList())
    assertEquals(150L, TellomiMotion.TailHandover.DURATION_MS)
    assertEquals(listOf(0f, 0f, 0.2f, 1f), TellomiMotion.TailHandover.CURVE.toList())
  }

  /** 省电模式照飞（设计规范第 10 节），关了动画、低内存机不飞。 */
  @Test
  fun `send flight plays in power save but not without animations or on low memory phones`() {
    assertTrue(TellomiMotion.allowsSendFlight(goodPhone))
    assertTrue(TellomiMotion.allowsSendFlight(goodPhone.copy(powerSave = true)))
    assertFalse(TellomiMotion.allowsSendFlight(goodPhone.copy(animatorsEnabled = false)))
    assertFalse(TellomiMotion.allowsSendFlight(goodPhone.copy(lowRamDevice = true)))
    assertFalse(TellomiMotion.allowsSendFlight(goodPhone.copy(memoryClassMb = 96)))
    assertTrue(TellomiMotion.allowsSendFlight(goodPhone.copy(memoryClassMb = 0)))
  }

  /** 传完的图从占位上淡入：照 Telegram 两端 150 ms，和 iOS `TellomiMotion.fadeOutDuration` 同一个数（交互审计 A-13）。 */
  @Test
  fun `a finished image fades in over its placeholder in 150 ms like Telegram`() {
    assertEquals(150, TellomiMotion.THUMBNAIL_CROSSFADE_MS)
  }
}
