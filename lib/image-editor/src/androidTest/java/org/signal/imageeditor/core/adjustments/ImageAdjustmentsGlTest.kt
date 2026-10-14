/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.imageeditor.core.model.ImageAdjustments
import kotlin.math.abs

/**
 * Tellomi（tellomi/tellomi#1592 E5-2、#1595 E5-5）：调色的 GL 路径在真 GPU 上画出来的和 CPU 上同样的算法一致；分块、透明、耗时。
 * 跑法：`./gradlew :lib:image-editor:connectedDebugAndroidTest`（要有一台设备或模拟器）。
 */
@RunWith(AndroidJUnit4::class)
class ImageAdjustmentsGlTest {

  private lateinit var gl: ImageAdjustmentsGl

  private val adjustments = ImageAdjustments(exposure = 35f, contrast = 25f, saturation = -30f, warmth = 40f)
  private val lut = ImageAdjustmentsColor.buildLut(adjustments)
  private val colorPass = ImageAdjustmentsGl.Pass(lut, ImageAdjustmentsColor.LUT_SIZE, 0f, 0f, 0f, ImageAdjustmentsDetail.VignetteFrame.FULL_IMAGE)

  /** E5-5：调色加上锐化、颗粒、暗角，画框是斜的（裁剪 + 转过一点）。 */
  private val detailAdjustments = adjustments.copy(sharpen = 60f, grain = 50f, vignette = 70f)
  private val tiltedFrame = ImageAdjustmentsDetail.VignetteFrame(2.4f, 0.3f, -1.3f, -0.2f, 2f, -0.9f)

  @Before
  fun setUp() {
    gl = assertNotNull_("这台设备建不起 GL 上下文", ImageAdjustmentsGl.create())
  }

  @After
  fun tearDown() {
    gl.release()
  }

  /** GPU 和 CPU 查同一张表，差不过 3/255（GPU 双线性过滤的权重精度有限），平均不过 0.5/255。 */
  @Test
  fun gpuMatchesCpu() {
    val source = gradient(256, 256)
    val gpu = gl.render(source, colorPass)
    val cpu = ImageAdjustmentsRenderer.applyOnCpu(source, lut, ImageAdjustmentsColor.LUT_SIZE)
    assertEquals(source.width, gpu.width)
    assertEquals(source.height, gpu.height)
    assertMaxDifference(cpu, gpu, 3)
  }

  /** 比一块（2048）宽的图分块画，块的接缝处和别处一样。 */
  @Test
  fun largeImagesAreTiled() {
    val source = gradient(2048 + 300, 64)
    val gpu = gl.render(source, colorPass)
    val cpu = ImageAdjustmentsRenderer.applyOnCpu(source, lut, ImageAdjustmentsColor.LUT_SIZE)
    assertMaxDifference(cpu, gpu, 3)
  }

  /** E5-5：锐化、颗粒、暗角在 GPU 上和 CPU 上一样（锐化放大了纹理过滤的精度差，最大放宽到 4/255，平均仍不过 0.5/255）。 */
  @Test
  fun detailGpuMatchesCpu() {
    val pass = ImageAdjustmentsRenderer.pass(detailAdjustments, tiltedFrame, true)
    val source = gradient(256, 256)
    assertMaxDifference(ImageAdjustmentsRenderer.applyOnCpu(source, pass), gl.render(source, pass), 4)
  }

  /** E5-5：分块时锐化跨过接缝也一样（每块带重叠边），颗粒、暗角按整张图的位置算。 */
  @Test
  fun detailIsSeamlessAcrossTiles() {
    val pass = ImageAdjustmentsRenderer.pass(detailAdjustments.copy(sharpen = 100f), tiltedFrame, true)
    val source = gradient(2048 + 300, 96)
    assertMaxDifference(ImageAdjustmentsRenderer.applyOnCpu(source, pass), gl.render(source, pass), 4)
  }

  /** E5-8：模糊（径向、线性）在 GPU 上和 CPU 上一样；分块时也一样（小图整张一个纹理，按单位坐标取）。 */
  @Test
  fun blurGpuMatchesCpu() {
    val source = gradient(2048 + 300, 300)
    for (blur in listOf(
      ImageAdjustments.Blur(type = ImageAdjustments.BlurType.RADIAL, centerX = 0.45f, falloff = 0.1f, size = 0.3f),
      ImageAdjustments.Blur(type = ImageAdjustments.BlurType.LINEAR, centerY = 0.6f, falloff = 0.05f, size = 0.2f, angle = 1.2f)
    )) {
      val pass = ImageAdjustmentsRenderer.pass(detailAdjustments.copy(blur = blur), tiltedFrame, true, source)
      assertMaxDifference(ImageAdjustmentsRenderer.applyOnCpu(source, pass), gl.render(source, pass), 4)
    }
  }

  /** E5-9：增强在 GPU 上和 CPU 上一样（分块时也一样，表按整张图的位置查）。 */
  @Test
  fun enhanceGpuMatchesCpu() {
    val source = gradient(2048 + 300, 300)
    val pass = ImageAdjustmentsRenderer.pass(adjustments.copy(enhance = 80f, vignette = 30f), tiltedFrame, true, source)
    assertMaxDifference(ImageAdjustmentsRenderer.applyOnCpu(source, pass), gl.render(source, pass), 4)
  }

  /** 半透明的像素按没预乘的颜色查表，alpha 不变；颜色空间跟着原图。 */
  @Test
  fun keepsAlphaAndColorSpace() {
    val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
      setPixel(0, 0, Color.argb(128, 200, 100, 50))
      setPixel(1, 0, Color.argb(255, 200, 100, 50))
    }
    val gpu = gl.render(source, colorPass)
    assertEquals(128, Color.alpha(gpu.getPixel(0, 0)))
    assertChannelsClose(gpu.getPixel(1, 0), gpu.getPixel(0, 0), 3)
    assertEquals(source.colorSpace, gpu.colorSpace)
  }

  /** 12MP（4032 × 3024）原尺寸烘焙的耗时：记进 PR。 */
  @Test
  fun twelveMegapixelBakeTime() {
    val source = gradient(4032, 3024)
    gl.render(gradient(64, 64), colorPass)
    val start = SystemClock.elapsedRealtime()
    val output = gl.render(source, colorPass)
    val elapsed = SystemClock.elapsedRealtime() - start
    Log.i("TellomiE52", "TELLOMI_E52_12MP_BAKE_MS=$elapsed")
    println("TELLOMI_E52_12MP_BAKE_MS=$elapsed")
    assertEquals(4032, output.width)
    assertTrue("took $elapsed ms", elapsed < 10_000)

    // E5-5：锐化 + 颗粒 + 暗角都开（分块带重叠边）。
    val detailPass = ImageAdjustmentsRenderer.pass(detailAdjustments, tiltedFrame, true)
    val detailStart = SystemClock.elapsedRealtime()
    gl.render(source, detailPass)
    val detailElapsed = SystemClock.elapsedRealtime() - detailStart
    Log.i("TellomiE55", "TELLOMI_E55_12MP_BAKE_MS=$detailElapsed")
    println("TELLOMI_E55_12MP_BAKE_MS=$detailElapsed")
    assertTrue("took $detailElapsed ms", detailElapsed < 10_000)
  }

  /** 通过对外的入口走一遍（GL 线程 + 阻塞等结果）。 */
  @Test
  fun rendererFacadeUsesTheGlThread() {
    val source = gradient(128, 128)
    val output = ImageAdjustmentsRenderer.apply(source, adjustments)
    val cpu = ImageAdjustmentsRenderer.applyOnCpu(source, lut, ImageAdjustmentsColor.LUT_SIZE)
    assertMaxDifference(cpu, output, 3)
  }

  private fun gradient(width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height) { i ->
      val x = i % width
      val y = i / width
      Color.argb(255, x * 255 / maxOf(1, width - 1), y * 255 / maxOf(1, height - 1), (x + y) % 256)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private fun assertMaxDifference(expected: Bitmap, actual: Bitmap, tolerance: Int) {
    val width = expected.width
    val height = expected.height
    val a = IntArray(width * height).also { expected.getPixels(it, 0, width, 0, 0, width, height) }
    val b = IntArray(width * height).also { actual.getPixels(it, 0, width, 0, 0, width, height) }
    var worst = 0
    var total = 0L
    for (i in a.indices) {
      val dr = abs(Color.red(a[i]) - Color.red(b[i]))
      val dg = abs(Color.green(a[i]) - Color.green(b[i]))
      val db = abs(Color.blue(a[i]) - Color.blue(b[i]))
      worst = maxOf(worst, dr, dg, db)
      total += dr + dg + db
    }
    val mean = total.toDouble() / (a.size * 3)
    Log.i("TellomiE52", "difference worst=$worst/255 mean=$mean/255")
    assertTrue("worst difference $worst/255", worst <= tolerance)
    // 偏差是 GPU 纹理过滤的精度（权重只有几位），不是采样位置偏了：平均下来要很小。
    assertTrue("mean difference $mean/255", mean <= 0.5)
  }

  private fun assertChannelsClose(expected: Int, actual: Int, tolerance: Int) {
    assertTrue("$expected vs $actual", abs(Color.red(expected) - Color.red(actual)) <= tolerance)
    assertTrue("$expected vs $actual", abs(Color.green(expected) - Color.green(actual)) <= tolerance)
    assertTrue("$expected vs $actual", abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
  }

  private fun <T> assertNotNull_(message: String, value: T?): T {
    assertNotNull(message, value)
    return value!!
  }
}
