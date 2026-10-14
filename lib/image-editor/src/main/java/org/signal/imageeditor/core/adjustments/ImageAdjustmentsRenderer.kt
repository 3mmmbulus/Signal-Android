/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.VisibleForTesting
import org.signal.core.util.logging.Log
import org.signal.imageeditor.core.model.ImageAdjustments
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1592 E5-2，Epic #1523）：把调色画进位图。
 *
 * 逐像素的工具合成一张查找表（[ImageAdjustmentsColor]），在一条专门的 GL 线程上用 GLES 2.0 套上去（[ImageAdjustmentsGl]）；
 * 那台机器建不起 GL 上下文、或这一次 GL 出错，就在 CPU 上查同一张表（慢，但结果一样）。
 * 全在默认值时原样返回传进来的那张位图，整条管线不走。
 */
object ImageAdjustmentsRenderer {

  private val TAG = Log.tag(ImageAdjustmentsRenderer::class.java)

  private val glThread: HandlerThread by lazy { HandlerThread("tellomi-adjustments-gl").apply { start() } }
  private val glHandler: Handler by lazy { Handler(glThread.looper) }
  private val mainHandler = Handler(Looper.getMainLooper())

  // 下面这几个只在 GL 线程上碰。
  private var gl: ImageAdjustmentsGl? = null
  private var glTried = false
  private var cachedLut: Pair<ImageAdjustments, ByteArray>? = null

  fun interface Callback {
    /** 在主线程回调；出错时是 null。 */
    fun onRendered(result: Bitmap?)
  }

  /** 阻塞着画（发出时的原尺寸烘焙、打码的小模糊图）。不要在 GL 线程上调。 */
  @JvmStatic
  fun apply(source: Bitmap, adjustments: ImageAdjustments): Bitmap {
    if (adjustments.isDefault) return source
    val task = FutureTask { render(source, adjustments) }
    glHandler.post(task)
    return try {
      task.get()
    } catch (e: ExecutionException) {
      throw RuntimeException(e.cause ?: e)
    }
  }

  /** 异步画（拖滑杆时的预览），画好在主线程回调。 */
  @JvmStatic
  fun applyAsync(source: Bitmap, adjustments: ImageAdjustments, callback: Callback) {
    if (adjustments.isDefault) {
      mainHandler.post { callback.onRendered(source) }
      return
    }
    glHandler.post {
      val result = try {
        render(source, adjustments)
      } catch (e: Throwable) {
        Log.w(TAG, "Adjustments render failed", e)
        null
      }
      mainHandler.post { callback.onRendered(result) }
    }
  }

  /** GL 线程上。 */
  private fun render(source: Bitmap, adjustments: ImageAdjustments): Bitmap {
    val input = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    val lut = lut(adjustments)
    val renderer = glRenderer()
    if (renderer != null) {
      try {
        return renderer.render(input, lut, ImageAdjustmentsColor.LUT_SIZE)
      } catch (e: Throwable) {
        Log.w(TAG, "GL adjustments failed, using the CPU", e)
      }
    }
    return applyOnCpu(input, lut, ImageAdjustmentsColor.LUT_SIZE)
  }

  private fun glRenderer(): ImageAdjustmentsGl? {
    if (!glTried) {
      glTried = true
      gl = ImageAdjustmentsGl.create()
    }
    return gl
  }

  private fun lut(adjustments: ImageAdjustments): ByteArray {
    cachedLut?.let { (cachedAdjustments, lut) -> if (cachedAdjustments == adjustments) return lut }
    return ImageAdjustmentsColor.buildLut(adjustments).also { cachedLut = adjustments to it }
  }

  /** CPU 上查表：`getPixels` 给的是没预乘的颜色，透明的像素不动。 */
  @VisibleForTesting
  internal fun applyOnCpu(source: Bitmap, lut: ByteArray, size: Int): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val rgb = FloatArray(3)
    for (i in pixels.indices) {
      val color = pixels[i]
      val alpha = color ushr 24
      if (alpha == 0) continue
      rgb[0] = ((color shr 16) and 0xFF) / 255f
      rgb[1] = ((color shr 8) and 0xFF) / 255f
      rgb[2] = (color and 0xFF) / 255f
      ImageAdjustmentsColor.lookup(lut, size, rgb)
      pixels[i] = (alpha shl 24) or (toByte(rgb[0]) shl 16) or (toByte(rgb[1]) shl 8) or toByte(rgb[2])
    }
    return createOutput(source).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
  }

  /** 和 [source] 同尺寸、同颜色空间（API 26+）的空位图。 */
  internal fun createOutput(source: Bitmap): Bitmap {
    val colorSpace = if (Build.VERSION.SDK_INT >= 26) source.colorSpace else null
    val output = if (Build.VERSION.SDK_INT >= 26 && colorSpace != null) {
      Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888, source.hasAlpha(), colorSpace)
    } else {
      Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    }
    output.setHasAlpha(source.hasAlpha())
    return output
  }

  private fun toByte(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
}
