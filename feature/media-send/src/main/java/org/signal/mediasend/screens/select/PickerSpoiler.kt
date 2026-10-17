/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.select

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tellomi（tellomi/tellomi#1263 spec P-6，照 Telegram `PhotoAttachPhotoCell` / iOS `SpoilerOverlayNode` 的机制，代码是自己写的）：
 * 选图面板里罩住的格子。图本身模糊（[spoilerBlur]，API 31 起才有；更早的版本压暗加重，看不清内容），上面一层压暗和缓慢飘动、
 * 闪烁的白色细粒子；0.2 秒淡入淡出（spec P-6）。编号勾由调用方画在它上面；这一层不接手势（选图面板里不能揭示）。
 */
@Composable
internal fun PickerSpoilerOverlay(visible: Boolean, modifier: Modifier = Modifier) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(PickerSpoiler.FADE_MILLIS)),
    exit = fadeOut(tween(PickerSpoiler.FADE_MILLIS)),
    modifier = modifier
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = PickerSpoiler.scrimAlpha()))
        .testTag(PickerSpoiler.TEST_TAG)
    ) {
      SpoilerParticles(Modifier.fillMaxSize())
    }
  }
}

/** 罩住时图本身模糊（API 31 起）。 */
internal fun Modifier.spoilerBlur(covered: Boolean): Modifier {
  return if (covered && Build.VERSION.SDK_INT >= 31) this.blur(PickerSpoiler.BLUR_RADIUS.dp) else this
}

internal object PickerSpoiler {
  const val FADE_MILLIS = 200
  const val BLUR_RADIUS = 14
  const val TEST_TAG = "picker-spoiler"

  /** 没有模糊的老版本压暗得更重，内容看不出来。 */
  fun scrimAlpha(sdk: Int = Build.VERSION.SDK_INT): Float = if (sdk >= 31) 0.2f else 0.62f

  /** 粒子数跟面积走（约每 14 × 14 dp 一个），格子小也有一些。 */
  fun particleCount(widthDp: Float, heightDp: Float): Int = ((widthDp * heightDp) / (14f * 14f)).toInt().coerceIn(24, 420)
}

private class Particle(val x: Float, val y: Float, val angle: Float, val phase: Float, val radius: Float)

@Composable
private fun SpoilerParticles(modifier: Modifier) {
  val transition = rememberInfiniteTransition(label = "picker-spoiler")
  val t by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(durationMillis = 4000, easing = LinearEasing)),
    label = "picker-spoiler-t"
  )
  val random = remember { Random(System.nanoTime()) }
  val particles = remember { mutableMapOf<Int, List<Particle>>() }

  Canvas(modifier) {
    val count = PickerSpoiler.particleCount(size.width / density, size.height / density)
    val list = particles.getOrPut(count) {
      List(count) {
        Particle(
          x = random.nextFloat(),
          y = random.nextFloat(),
          angle = random.nextFloat() * 2f * PI.toFloat(),
          phase = random.nextFloat(),
          radius = 0.6f + random.nextFloat() * 0.6f
        )
      }
    }
    val drift = 6.dp.toPx()
    for (p in list) {
      val phase = (t + p.phase) % 1f
      val alpha = 0.25f + 0.75f * abs(sin(phase * PI.toFloat()))
      val turn = p.angle + t * 2f * PI.toFloat()
      val x = p.x * size.width + drift * cos(turn)
      val y = p.y * size.height + drift * sin(turn)
      drawCircle(color = Color.White.copy(alpha = alpha), radius = p.radius.dp.toPx(), center = Offset(x, y))
    }
  }
}
