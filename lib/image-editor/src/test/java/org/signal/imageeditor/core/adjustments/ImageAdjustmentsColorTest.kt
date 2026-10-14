/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.imageeditor.core.model.ImageAdjustments
import kotlin.math.abs

/**
 * Tellomi（tellomi/tellomi#1592 E5-2、#1597 E5-7）：调色的逐像素数学（含曲线）、查找表和 CPU 兜底。GL 路径在 androidTest 的 `ImageAdjustmentsGlTest`。
 */
@RunWith(RobolectricTestRunner::class)
class ImageAdjustmentsColorTest {

  @Test
  fun defaultLeavesColorsAlone() {
    SAMPLE_COLORS.forEach { color ->
      assertArrayEquals(color, adjusted(color, ImageAdjustments.DEFAULT), 0f)
    }
  }

  /** 两端同一套数学：这几个值和 iOS `TellomiImageAdjustmentsRendererTests.testMatchesTheSharedReferenceValues` 同一组。 */
  @Test
  fun matchesTheSharedReferenceValues() {
    val cases = listOf(
      Triple(floatArrayOf(0.5f, 0.5f, 0.5f), ImageAdjustments(exposure = 100f), floatArrayOf(0.72602f, 0.72602f, 0.72602f)),
      Triple(floatArrayOf(0.5f, 0.5f, 0.5f), ImageAdjustments(exposure = -100f), floatArrayOf(0.30487f, 0.30487f, 0.30487f)),
      Triple(floatArrayOf(0.25f, 0.25f, 0.25f), ImageAdjustments(contrast = 60f), floatArrayOf(0.19407f, 0.19407f, 0.19407f)),
      Triple(floatArrayOf(0.6f, 0.4f, 0.4f), ImageAdjustments(saturation = 50f), floatArrayOf(0.67874f, 0.37874f, 0.37874f)),
      Triple(floatArrayOf(0.5f, 0.5f, 0.5f), ImageAdjustments(warmth = 100f), floatArrayOf(0.55356f, 0.49054f, 0.4152f)),
      Triple(floatArrayOf(0.2f, 0.5f, 0.8f), ImageAdjustments(exposure = 35f, contrast = 25f, saturation = -30f, warmth = 40f), floatArrayOf(0.34061f, 0.5699f, 0.73056f)),
      // E5-4
      Triple(floatArrayOf(0f, 0f, 0f), ImageAdjustments(fade = 100f), floatArrayOf(0.24f, 0.24f, 0.24f)),
      Triple(floatArrayOf(1f, 1f, 1f), ImageAdjustments(fade = 100f), floatArrayOf(0.9f, 0.9f, 0.9f)),
      Triple(floatArrayOf(0.2f, 0.2f, 0.2f), ImageAdjustments(shadows = 100f), floatArrayOf(0.27767f, 0.27767f, 0.27767f)),
      Triple(floatArrayOf(0.2f, 0.2f, 0.2f), ImageAdjustments(shadows = -100f), floatArrayOf(0.12233f, 0.12233f, 0.12233f)),
      Triple(floatArrayOf(0.8f, 0.8f, 0.8f), ImageAdjustments(highlights = 100f), floatArrayOf(0.93593f, 0.93593f, 0.93593f)),
      Triple(floatArrayOf(0.8f, 0.8f, 0.8f), ImageAdjustments(highlights = -100f), floatArrayOf(0.72233f, 0.72233f, 0.72233f)),
      Triple(floatArrayOf(0.3f, 0.5f, 0.7f), ImageAdjustments(exposure = 10f, fade = 40f, highlights = -50f, shadows = 60f), floatArrayOf(0.35348f, 0.52817f, 0.70425f)),
      // E5-6
      Triple(floatArrayOf(0.2f, 0.2f, 0.2f), ImageAdjustments(tintShadowsColor = 0xFFD14B3F.toInt()), floatArrayOf(0.2801f, 0.1792f, 0.17017f)),
      Triple(floatArrayOf(0.8f, 0.8f, 0.8f), ImageAdjustments(tintHighlightsColor = 0xFF8CB4FF.toInt()), floatArrayOf(0.77221f, 0.80233f, 0.8588f)),
      Triple(floatArrayOf(0.3f, 0.5f, 0.7f), ImageAdjustments(warmth = 20f, tintShadowsColor = 0xFF2E8FA8.toInt(), tintHighlightsColor = 0xFFFFB870.toInt()), floatArrayOf(0.3001f, 0.50469f, 0.67501f))
    )
    cases.forEach { (input, adjustments, expected) ->
      assertArrayEquals(expected, adjusted(input, adjustments), 1e-4f)
    }
  }

  /** 曝光：调亮更亮、调暗更暗；白仍是白、黑仍是黑；调亮后亮部还分得开。 */
  @Test
  fun exposure() {
    val up50 = adjusted(gray(0.5f), ImageAdjustments(exposure = 50f))[0]
    val up100 = adjusted(gray(0.5f), ImageAdjustments(exposure = 100f))[0]
    assertTrue(up50 > 0.5f)
    assertTrue(up100 > up50)
    assertTrue(adjusted(gray(0.5f), ImageAdjustments(exposure = -100f))[0] < 0.4f)
    assertEquals(1f, adjusted(gray(1f), ImageAdjustments(exposure = 100f))[0], 1e-4f)
    assertEquals(0f, adjusted(gray(0f), ImageAdjustments(exposure = 100f))[0], 1e-4f)
    val light = adjusted(gray(0.8f), ImageAdjustments(exposure = 100f))[0]
    val lighter = adjusted(gray(0.9f), ImageAdjustments(exposure = 100f))[0]
    assertTrue(light < lighter && lighter < 1f)
  }

  /** 对比度绕中灰；饱和度 −100 是灰；色温暖 = 红多蓝少。 */
  @Test
  fun contrastSaturationWarmth() {
    assertEquals(0.5f, adjusted(gray(0.5f), ImageAdjustments(contrast = 100f))[0], 1e-5f)
    assertTrue(adjusted(gray(0.25f), ImageAdjustments(contrast = 60f))[0] < 0.25f)
    assertTrue(adjusted(gray(0.75f), ImageAdjustments(contrast = -60f))[0] < 0.75f)

    val gray = adjusted(floatArrayOf(0.6f, 0.4f, 0.4f), ImageAdjustments(saturation = -100f))
    assertEquals(gray[0], gray[1], 1e-5f)
    assertEquals(gray[1], gray[2], 1e-5f)

    val warm = adjusted(gray(0.5f), ImageAdjustments(warmth = 100f))
    val cool = adjusted(gray(0.5f), ImageAdjustments(warmth = -100f))
    assertTrue(warm[0] > warm[2])
    assertTrue(cool[2] > cool[0])
  }

  /** 褪色抬黑压白、中灰几乎不动；阴影只动暗部、高光只动亮部，方向照 TG（正 = 提亮）；色相不变。 */
  @Test
  fun fadeHighlightsShadows() {
    val fadedMid = adjusted(gray(0.5f), ImageAdjustments(fade = 100f))[0]
    assertEquals(0.51f, fadedMid, 0.01f)

    val lifted = adjusted(gray(0.2f), ImageAdjustments(shadows = 60f))[0]
    assertTrue(lifted > 0.2f)
    assertEquals("阴影不动亮部", 0.9f, adjusted(gray(0.9f), ImageAdjustments(shadows = 100f))[0], 0.02f)
    assertTrue(adjusted(gray(0.8f), ImageAdjustments(highlights = -60f))[0] < 0.8f)
    assertEquals("高光不动暗部", 0.1f, adjusted(gray(0.1f), ImageAdjustments(highlights = 100f))[0], 0.02f)

    val reddish = adjusted(floatArrayOf(0.3f, 0.15f, 0.1f), ImageAdjustments(shadows = 80f))
    assertEquals("按比例缩放，色相不变", 0.3f / 0.15f, reddish[0] / reddish[1], 0.01f)

    // 亮度单调：高光拉满时更亮的输入不会变得更暗。
    var previous = 0f
    for (step in 0..100) {
      val output = adjusted(gray(step / 100f), ImageAdjustments(highlights = 100f, shadows = -100f))[0]
      assertTrue("step $step", output >= previous - 1e-6f)
      previous = output
    }
  }

  /** 色调：暗部色只动暗部、亮部色只动亮部，往那个颜色的色相偏；两个色板各 8 个，第一个是「不加」。 */
  @Test
  fun tint() {
    assertEquals(8, ImageAdjustments.TINT_SHADOWS_PALETTE.size)
    assertEquals(8, ImageAdjustments.TINT_HIGHLIGHTS_PALETTE.size)
    assertEquals(ImageAdjustments.NO_TINT, ImageAdjustments.TINT_SHADOWS_PALETTE[0])
    assertEquals(ImageAdjustments.NO_TINT, ImageAdjustments.TINT_HIGHLIGHTS_PALETTE[0])

    val red = 0xFFD14B3F.toInt()
    val darkened = adjusted(gray(0.15f), ImageAdjustments(tintShadowsColor = red))
    assertTrue("暗部偏红", darkened[0] > darkened[2])
    assertArrayEquals(gray(1f), adjusted(gray(1f), ImageAdjustments(tintShadowsColor = red)), 1e-5f)

    val blue = 0xFF8CB4FF.toInt()
    val brightened = adjusted(gray(0.85f), ImageAdjustments(tintHighlightsColor = blue))
    assertTrue("亮部偏蓝", brightened[2] > brightened[0])
    assertArrayEquals(gray(0f), adjusted(gray(0f), ImageAdjustments(tintHighlightsColor = blue)), 1e-5f)
  }

  /** 表的排布：第 (b·n + r, g) 格是 (r, g, b)/(n − 1) 调色后的值。 */
  @Test
  fun lutLayout() {
    val adjustments = ImageAdjustments(exposure = 40f, warmth = -20f)
    val n = ImageAdjustmentsColor.LUT_SIZE
    val lut = ImageAdjustmentsColor.buildLut(adjustments)
    assertEquals(n * n * n * 4, lut.size)
    for ((r, g, b) in listOf(Triple(0, 0, 0), Triple(n - 1, n - 1, n - 1), Triple(5, 20, 31), Triple(32, 0, 16))) {
      val expected = adjusted(floatArrayOf(r / (n - 1f), g / (n - 1f), b / (n - 1f)), adjustments)
      val index = g * n * n * 4 + (b * n + r) * 4
      for (channel in 0..2) {
        assertEquals(expected[channel], (lut[index + channel].toInt() and 0xFF) / 255f, 0.5f / 255f + 1e-6f)
      }
      assertEquals(0xFF, lut[index + 3].toInt() and 0xFF)
    }
  }

  /** 三线性查表和直接算差不过 2/255（表本身 8 位量化 + 插值）。 */
  @Test
  fun lookupMatchesTheMath() {
    val adjustments = ImageAdjustments(exposure = 35f, contrast = 25f, saturation = -30f, warmth = 40f)
    val lut = ImageAdjustmentsColor.buildLut(adjustments)
    var worst = 0f
    for (r in 0..16) {
      for (g in 0..16) {
        for (b in 0..16) {
          val input = floatArrayOf(r / 16f, g / 16f, b / 16f)
          val expected = adjusted(input, adjustments)
          val looked = input.copyOf().also { ImageAdjustmentsColor.lookup(lut, ImageAdjustmentsColor.LUT_SIZE, it) }
          for (channel in 0..2) worst = maxOf(worst, abs(expected[channel] - looked[channel]))
        }
      }
    }
    assertTrue("worst ${worst * 255}/255", worst <= 2f / 255f)
  }

  /**
   * E5-7 曲线：单调三次插值，和 iOS `testCurvesMatchTheSharedReferenceValues` 同一组参考值
   * （默认 / S 形 / 抬黑压白 / 中间平 / 反相 / 不单调）；不会冲过相邻两点。
   */
  @Test
  fun curvesMatchTheSharedReferenceValues() {
    val cases = listOf(
      floatArrayOf(0f, 25f, 50f, 75f, 100f) to listOf(0.1f to 0.1f, 0.33f to 0.33f, 0.5f to 0.5f, 0.9f to 0.9f),
      floatArrayOf(0f, 15f, 50f, 85f, 100f) to listOf(0.1f to 0.0504f, 0.2f to 0.1072f, 0.33f to 0.2472f, 0.6f to 0.6496f, 0.9f to 0.9496f),
      floatArrayOf(20f, 40f, 60f, 80f, 90f) to listOf(0f to 0.2f, 0.1f to 0.28f, 0.37f to 0.496f, 0.5f to 0.6f, 1f to 0.9f),
      floatArrayOf(0f, 50f, 50f, 50f, 100f) to listOf(0.1f to 0.248f, 0.3f to 0.5f, 0.6f to 0.5f, 0.8f to 0.536f),
      floatArrayOf(100f, 75f, 50f, 25f, 0f) to listOf(0.1f to 0.9f, 0.5f to 0.5f, 0.8f to 0.2f),
      floatArrayOf(0f, 40f, 30f, 80f, 100f) to listOf(0.2f to 0.3712f, 0.3f to 0.3896f, 0.45f to 0.3104f, 0.6f to 0.4424f)
    )
    cases.forEach { (levels, samples) ->
      val points = curvePoints(levels)
      samples.forEach { (x, expected) ->
        assertEquals("${levels.toList()} at $x", expected, ImageAdjustmentsColor.curveValue(points, x), 1e-4f)
      }
    }
    for (step in 25..75) {
      assertEquals(0.5f, ImageAdjustmentsColor.curveValue(curvePoints(floatArrayOf(0f, 50f, 50f, 50f, 100f)), step / 100f), 1e-6f)
    }
  }

  /** 曲线在调色的最前面：「全部」三个通道都过，红 / 绿 / 蓝只动自己那个通道。 */
  @Test
  fun curvesApplyPerChannel() {
    val s = curvePoints(floatArrayOf(0f, 15f, 50f, 85f, 100f))
    assertArrayEquals(floatArrayOf(0.1072f, 0.2472f, 0.6496f), adjusted(floatArrayOf(0.2f, 0.33f, 0.6f), ImageAdjustments(curves = ImageAdjustments.Curves(all = s))), 1e-4f)
    val red = curvePoints(floatArrayOf(20f, 40f, 60f, 80f, 90f))
    assertArrayEquals(floatArrayOf(0.6f, 0.5f, 0.5f), adjusted(gray(0.5f), ImageAdjustments(curves = ImageAdjustments.Curves(red = red))), 1e-4f)
    val both = ImageAdjustments(curves = ImageAdjustments.Curves(all = s, red = red, blue = curvePoints(floatArrayOf(100f, 75f, 50f, 25f, 0f))))
    assertArrayEquals(
      floatArrayOf(ImageAdjustmentsColor.curveValue(red, 0.1072f), 0.2472f, 1f - 0.6496f),
      adjusted(floatArrayOf(0.2f, 0.33f, 0.6f), both),
      1e-4f
    )
  }

  /** 查找表里的曲线和直接算的数学差不过 2/255。 */
  @Test
  fun lookupTableMatchesTheMathWithCurves() {
    val adjustments = ImageAdjustments(
      exposure = 10f,
      contrast = 10f,
      curves = ImageAdjustments.Curves(all = curvePoints(floatArrayOf(0f, 15f, 50f, 85f, 100f)), green = curvePoints(floatArrayOf(10f, 35f, 55f, 70f, 95f)))
    )
    val lut = ImageAdjustmentsColor.buildLut(adjustments)
    var worst = 0f
    for (r in 0..16) {
      for (g in 0..16) {
        for (b in 0..16) {
          val input = floatArrayOf(r / 16f, g / 16f, b / 16f)
          val expected = adjusted(input, adjustments)
          val looked = input.copyOf().also { ImageAdjustmentsColor.lookup(lut, ImageAdjustmentsColor.LUT_SIZE, it) }
          for (i in 0..2) worst = maxOf(worst, abs(expected[i] - looked[i]))
        }
      }
    }
    assertTrue("worst ${worst * 255}/255", worst <= 2f / 255f)
  }

  private fun curvePoints(levels: FloatArray) = ImageAdjustments.CurvePoints(levels[0], levels[1], levels[2], levels[3], levels[4])

  /** 全默认：原样返回传进来的那张位图。 */
  @Test
  fun defaultAdjustmentsSkipThePipeline() {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    assertSame(bitmap, ImageAdjustmentsRenderer.apply(bitmap, ImageAdjustments.DEFAULT))
  }

  /** CPU 兜底：每个像素都按表调了，alpha 不动，全透明的像素不碰。 */
  @Test
  fun cpuFallbackAppliesTheTable() {
    val adjustments = ImageAdjustments(exposure = 60f, saturation = 30f)
    val lut = ImageAdjustmentsColor.buildLut(adjustments)
    val source = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888).apply {
      setPixel(0, 0, Color.argb(255, 128, 64, 32))
      setPixel(1, 0, Color.argb(255, 200, 200, 200))
      setPixel(2, 0, Color.argb(0, 0, 0, 0))
    }

    val output = ImageAdjustmentsRenderer.applyOnCpu(source, lut, ImageAdjustmentsColor.LUT_SIZE)

    for (x in 0..1) {
      val input = source.getPixel(x, 0)
      val expected = adjusted(floatArrayOf(Color.red(input) / 255f, Color.green(input) / 255f, Color.blue(input) / 255f), adjustments)
      val actual = output.getPixel(x, 0)
      assertEquals(255, Color.alpha(actual))
      assertArrayEquals(expected, floatArrayOf(Color.red(actual) / 255f, Color.green(actual) / 255f, Color.blue(actual) / 255f), 2.5f / 255f)
    }
    assertEquals(0, output.getPixel(2, 0))
  }

  private fun adjusted(input: FloatArray, adjustments: ImageAdjustments): FloatArray {
    return input.copyOf().also { ImageAdjustmentsColor.adjust(it, adjustments) }
  }

  private fun gray(value: Float) = floatArrayOf(value, value, value)

  private fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
    for (i in expected.indices) {
      assertEquals("channel $i: expected ${expected.toList()} actual ${actual.toList()}", expected[i], actual[i], delta)
    }
  }

  private companion object {
    val SAMPLE_COLORS = listOf(
      floatArrayOf(0f, 0f, 0f),
      floatArrayOf(1f, 1f, 1f),
      floatArrayOf(0.5f, 0.5f, 0.5f),
      floatArrayOf(0.9f, 0.2f, 0.1f),
      floatArrayOf(0.1f, 0.7f, 0.3f)
    )
  }
}
