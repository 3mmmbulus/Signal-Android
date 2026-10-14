/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.imageeditor.core.model.ImageAdjustments
import kotlin.math.sqrt

/**
 * Tellomi（tellomi/tellomi#1595 E5-5）：锐化、颗粒、暗角的定义和 CPU 实现。GPU 和 CPU 一致在 androidTest 的 `ImageAdjustmentsGlTest`。
 */
@RunWith(RobolectricTestRunner::class)
class ImageAdjustmentsDetailTest {

  /**
   * 两端同一套：噪声块的字节、「格」的大小、颗粒权重、暗角强度和压暗。和 iOS
   * `TellomiImageAdjustmentsRendererTests.testDetailMatchesTheSharedReferenceValues` 同一组。
   */
  @Test
  fun matchesTheSharedReferenceValues() {
    val noise = ImageAdjustmentsDetail.NOISE.map { it.toInt() and 0xFF }
    assertEquals(256 * 256, noise.size)
    assertEquals(listOf(225, 139, 100, 0, 242, 254, 138, 18, 150, 70, 19, 157, 142, 158, 38, 231), noise.take(16))
    assertEquals(8_380_824, noise.sum())
    assertEquals(185, noise[256])
    assertEquals(198, noise.last())

    assertEquals(1f, ImageAdjustmentsDetail.unit(1000), 0f)
    assertEquals(1f, ImageAdjustmentsDetail.unit(2048), 0f)
    assertEquals(1.96875f, ImageAdjustmentsDetail.unit(4032), 0f)

    assertEquals(1f, ImageAdjustmentsDetail.grainWeight(0.5f), 1e-6f)
    assertEquals(0.488f, ImageAdjustmentsDetail.grainWeight(0.9f), 1e-5f)
    assertEquals(0.875f, ImageAdjustmentsDetail.grainWeight(0.25f), 1e-6f)
    assertEquals(0f, ImageAdjustmentsDetail.grainWeight(0f), 1e-6f)

    assertEquals(1f, ImageAdjustmentsDetail.vignetteStrength(1f, 1f, 1f), 1e-5f)
    assertEquals(0.35355f, ImageAdjustmentsDetail.vignetteStrength(1f, 1f, 0f), 1e-5f)
    assertEquals(0.125f, ImageAdjustmentsDetail.vignetteStrength(1f, 0.5f, 0.5f), 1e-5f)
    assertEquals("画框外不再加深", 1f, ImageAdjustmentsDetail.vignetteStrength(1f, 2f, 2f), 1e-5f)
    assertEquals(0.14146f, ImageAdjustmentsDetail.vignette(0.5f, 1f), 1e-5f)
    assertEquals(0.65f, ImageAdjustmentsDetail.vignette(1f, 1f), 1e-5f)
    assertEquals(0.39784f, ImageAdjustmentsDetail.vignette(0.8f, 1f), 1e-5f)
    assertEquals(0.3265f, ImageAdjustmentsDetail.vignette(0.5f, 0.35355f), 1e-4f)
    assertEquals(0.06282f, ImageAdjustmentsDetail.vignette(0.2f, 0.5f), 1e-5f)
  }

  /** 噪声在格子中心就是那一格的值，四边循环。 */
  @Test
  fun noiseIsBilinearAndWraps() {
    fun cell(x: Int, y: Int) = ((ImageAdjustmentsDetail.NOISE[y * 256 + x].toInt() and 0xFF) - 127.5f) / 127.5f
    assertEquals(cell(0, 0), ImageAdjustmentsDetail.noiseAt(0.5f, 0.5f), 1e-6f)
    assertEquals(cell(3, 1), ImageAdjustmentsDetail.noiseAt(3.5f, 1.5f), 1e-6f)
    assertEquals((cell(3, 1) + cell(4, 1)) / 2f, ImageAdjustmentsDetail.noiseAt(4f, 1.5f), 1e-6f)
    assertEquals(ImageAdjustmentsDetail.noiseAt(3.25f, 7.75f), ImageAdjustmentsDetail.noiseAt(3.25f + 256f, 7.75f - 512f), 1e-5f)
  }

  /** 锐化：一条竖直的边两侧拉开（帐篷核、2.5 倍，参考值 0.275 / 0.725，同 iOS），平的地方不动，图边不被外面拉暗。 */
  @Test
  fun sharpenMatchesTheMath() {
    val source = image(16, 6) { x, _ -> if (x < 8) 0.4f else 0.6f }
    val output = render(source, ImageAdjustments(sharpen = 100f))
    assertEquals(0.275f, red(output, 7, 3), 1.5f / 255f)
    assertEquals(0.725f, red(output, 8, 3), 1.5f / 255f)
    assertEquals(red(output, 7, 3), red(output, 7, 0), 1f / 255f)
    assertEquals(0.4f, red(output, 2, 3), 1f / 255f)
    assertEquals(0.6f, red(output, 13, 3), 1f / 255f)
    assertEquals("图边不被外面拉暗", 0.4f, red(output, 0, 0), 1f / 255f)

    assertEquals(0.3375f, red(render(source, ImageAdjustments(sharpen = 50f)), 7, 3), 1.5f / 255f)
  }

  /** 颗粒：中灰上有起伏（标准差 0.01…0.04），平均不变，三个通道一样（单色）；纯黑不加；噪声固定，画两次一样。 */
  @Test
  fun grainAddsMonochromeMidtoneNoise() {
    val gray = image(128, 128) { _, _ -> 0.5f }
    val output = render(gray, ImageAdjustments(grain = 100f))
    val pixels = pixels(output)
    val values = pixels.map { Color.red(it) / 255f }
    val mean = values.average().toFloat()
    val deviation = sqrt(values.map { (it - mean) * (it - mean) }.average()).toFloat()
    assertEquals(0.5f, mean, 0.01f)
    assertTrue("deviation $deviation", deviation > 0.01f && deviation < 0.04f)
    assertTrue("单色颗粒", pixels.all { Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) })
    assertArrayEquals(pixels, pixels(render(gray, ImageAdjustments(grain = 100f))))

    val black = image(32, 32) { _, _ -> 0f }
    assertTrue(pixels(render(black, ImageAdjustments(grain = 100f))).all { it == Color.BLACK })
  }

  /** 暗角：中心不动、四角最暗，和数学一致（角上的中灰 ≈ 0.14）；画框换了，最暗的地方跟着画框走。 */
  @Test
  fun vignetteMatchesTheMathAndFollowsTheFrame() {
    val gray = image(200, 100) { _, _ -> 0.5f }
    val input = 128f / 255f
    val output = render(gray, ImageAdjustments(vignette = 100f))
    assertEquals(input, red(output, 100, 50), 1f / 255f)
    for ((x, y) in listOf(0 to 0, 199 to 0, 0 to 99, 199 to 99, 30 to 70)) {
      val dx = 2f * (x + 0.5f) / 200f - 1f
      val dy = 2f * (y + 0.5f) / 100f - 1f
      val expected = ImageAdjustmentsDetail.vignette(input, ImageAdjustmentsDetail.vignetteStrength(1f, dx, dy))
      assertEquals("($x, $y)", expected, red(output, x, y), 1f / 255f)
    }
    assertTrue(red(output, 0, 0) < 0.16f)

    // 只看中间一半（放大 2 倍的裁剪）：原图 (0.25, 0.25) 是画框的左上角。
    val zoomed = ImageAdjustmentsDetail.VignetteFrame(4f, 0f, -2f, 0f, 4f, -2f)
    val cropped = render(gray, ImageAdjustments(vignette = 100f), zoomed)
    assertTrue("裁剪框的角上最暗", red(cropped, 50, 25) < 0.16f)
    assertEquals(input, red(cropped, 100, 50), 1f / 255f)
  }

  /** 查找表只管逐像素的几项；打码用的小模糊图（detail = false）不锐化、不加颗粒，暗角照加。 */
  @Test
  fun passSeparatesColorFromDetail() {
    val frame = ImageAdjustmentsDetail.VignetteFrame.FULL_IMAGE
    val detailOnly = ImageAdjustmentsRenderer.pass(ImageAdjustments(sharpen = 40f, grain = 30f, vignette = 20f), frame, true)
    assertNull("只动了这三项不查表", detailOnly.lut)
    assertEquals(1f, detailOnly.sharpen, 1e-6f)
    assertEquals(0.018f, detailOnly.grain, 1e-6f)
    assertEquals(0.2f, detailOnly.vignette, 1e-6f)

    val withColor = ImageAdjustmentsRenderer.pass(ImageAdjustments(exposure = 10f, sharpen = 40f), frame, true)
    assertNotNull(withColor.lut)
    assertArrayEquals(ImageAdjustmentsColor.buildLut(ImageAdjustments(exposure = 10f)), withColor.lut)

    val blurred = ImageAdjustmentsRenderer.pass(ImageAdjustments(sharpen = 40f, grain = 30f, vignette = 20f), frame, false)
    assertEquals(0f, blurred.sharpen, 0f)
    assertEquals(0f, blurred.grain, 0f)
    assertEquals(0.2f, blurred.vignette, 1e-6f)
  }

  /** 分块要的重叠边：锐化取样最远 u 个像素，再加一个给线性插值。 */
  @Test
  fun apronCoversTheSharpenTaps() {
    assertEquals(2, ImageAdjustmentsGl.apron(1f))
    assertEquals(3, ImageAdjustmentsGl.apron(1.96875f))
  }

  private fun render(
    source: Bitmap,
    adjustments: ImageAdjustments,
    frame: ImageAdjustmentsDetail.VignetteFrame = ImageAdjustmentsDetail.VignetteFrame.FULL_IMAGE
  ): Bitmap {
    return ImageAdjustmentsRenderer.applyOnCpu(source, ImageAdjustmentsRenderer.pass(adjustments, frame, true))
  }

  private fun image(width: Int, height: Int, value: (Int, Int) -> Float): Bitmap {
    val pixels = IntArray(width * height) { i ->
      val v = (value(i % width, i / width) * 255f + 0.5f).toInt()
      Color.argb(255, v, v, v)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun pixels(bitmap: Bitmap): IntArray {
    return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
  }

  private fun red(bitmap: Bitmap, x: Int, y: Int): Float = Color.red(bitmap.getPixel(x, y)) / 255f
}
