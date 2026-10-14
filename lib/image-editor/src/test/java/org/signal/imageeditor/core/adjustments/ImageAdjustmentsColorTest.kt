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
 * Tellomi（tellomi/tellomi#1592 E5-2）：调色的逐像素数学、查找表和 CPU 兜底。GL 路径在 androidTest 的 `ImageAdjustmentsGlTest`。
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
      Triple(floatArrayOf(0.2f, 0.5f, 0.8f), ImageAdjustments(exposure = 35f, contrast = 25f, saturation = -30f, warmth = 40f), floatArrayOf(0.34061f, 0.5699f, 0.73056f))
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
