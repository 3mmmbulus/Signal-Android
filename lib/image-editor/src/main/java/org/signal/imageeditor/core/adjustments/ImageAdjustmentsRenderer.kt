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

/**
 * Tellomi（tellomi/tellomi#1592 E5-2、#1595 E5-5，Epic #1523）：把调色画进位图。
 *
 * 逐像素的工具合成一张查找表（[ImageAdjustmentsColor]），和位置有关的锐化、颗粒、暗角、模糊（[ImageAdjustmentsDetail]）一起，
 * 在一条专门的 GL 线程上用 GLES 2.0 一遍画完（[ImageAdjustmentsGl]）；那台机器建不起 GL 上下文、或这一次 GL 出错，
 * 就在 CPU 上走同样的算法（慢，但结果一样）。全在默认值时原样返回传进来的那张位图，整条管线不走。
 *
 * - `frame`：暗角跟着的画框（裁剪后的输出范围），由 `EditorModel` 算好经 `UriGlideRenderer` 传进来；只在暗角不是 0 时有用。
 * - `detail`：要不要锐化、颗粒和模糊（移轴）。打码用的小模糊图不要（锐化一张模糊图没意义，颗粒在小图上放大会成斑块），只调色和暗角。
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
  private var cachedBlur: Pair<Bitmap, Bitmap>? = null
  private var cachedEnhance: Pair<Bitmap, ByteArray>? = null

  fun interface Callback {
    /** 在主线程回调；出错时是 null。 */
    fun onRendered(result: Bitmap?)
  }

  /** 阻塞着画（发出时的原尺寸烘焙、打码的小模糊图）。不要在 GL 线程上调。 */
  @JvmStatic
  @JvmOverloads
  fun apply(
    source: Bitmap,
    adjustments: ImageAdjustments,
    frame: ImageAdjustmentsDetail.VignetteFrame = ImageAdjustmentsDetail.VignetteFrame.FULL_IMAGE,
    detail: Boolean = true
  ): Bitmap {
    if (adjustments.isDefault) return source
    val task = FutureTask { render(source, adjustments, frame, detail) }
    glHandler.post(task)
    return try {
      task.get()
    } catch (e: ExecutionException) {
      throw RuntimeException(e.cause ?: e)
    }
  }

  /** 异步画（拖滑杆时的预览），画好在主线程回调。 */
  @JvmStatic
  fun applyAsync(source: Bitmap, adjustments: ImageAdjustments, frame: ImageAdjustmentsDetail.VignetteFrame, callback: Callback) {
    if (adjustments.isDefault) {
      mainHandler.post { callback.onRendered(source) }
      return
    }
    glHandler.post {
      val result = try {
        render(source, adjustments, frame, true)
      } catch (e: Throwable) {
        Log.w(TAG, "Adjustments render failed", e)
        null
      }
      mainHandler.post { callback.onRendered(result) }
    }
  }

  /** GL 线程上。 */
  private fun render(source: Bitmap, adjustments: ImageAdjustments, frame: ImageAdjustmentsDetail.VignetteFrame, detail: Boolean): Bitmap {
    val input = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
    val pass = pass(adjustments, frame, detail, input)
    val renderer = glRenderer()
    if (renderer != null) {
      try {
        return renderer.render(input, pass)
      } catch (e: Throwable) {
        Log.w(TAG, "GL adjustments failed, using the CPU", e)
      }
    }
    return applyOnCpu(input, pass)
  }

  /**
   * 一组调色怎么画：查找表只管逐像素的几项（只动了锐化、颗粒、暗角、模糊时不查表，拖它们的滑杆也不重算表）。
   * 模糊要 [source]：从它缩出来的模糊小图，同一张原图只算一次。
   */
  @VisibleForTesting
  internal fun pass(adjustments: ImageAdjustments, frame: ImageAdjustmentsDetail.VignetteFrame, detail: Boolean, source: Bitmap? = null): ImageAdjustmentsGl.Pass {
    val colorOnly = ImageAdjustmentsDetail.colorOnly(adjustments)
    val blur = adjustments.blur
    val blurPass = if (detail && source != null && blur.type != ImageAdjustments.BlurType.OFF) {
      val (inner, outer) = ImageAdjustmentsDetail.blurRadii(blur)
      val (normalX, normalY) = ImageAdjustmentsDetail.blurNormal(blur)
      ImageAdjustmentsGl.BlurPass(
        small = blurredSmall(source),
        kind = if (blur.type == ImageAdjustments.BlurType.RADIAL) 1 else 2,
        centerX = blur.centerX,
        centerY = blur.centerY,
        inner = inner,
        outer = outer,
        normalX = normalX,
        normalY = normalY
      )
    } else {
      null
    }
    return ImageAdjustmentsGl.Pass(
      lut = if (colorOnly.isDefault) null else lut(colorOnly),
      lutSize = ImageAdjustmentsColor.LUT_SIZE,
      sharpen = if (detail) ImageAdjustmentsDetail.SHARPEN_AMOUNT * adjustments.sharpen / 100f else 0f,
      grain = if (detail) ImageAdjustmentsDetail.GRAIN_AMPLITUDE * adjustments.grain / 100f else 0f,
      vignette = adjustments.vignette / 100f,
      frame = frame,
      blur = blurPass,
      enhance = if (detail) ImageAdjustmentsDetail.ENHANCE_STRENGTH * adjustments.enhance / 100f else 0f,
      enhanceLuts = if (detail && source != null && adjustments.enhance > 0f) enhanceLuts(source) else null
    )
  }

  private fun enhanceLuts(source: Bitmap): ByteArray {
    cachedEnhance?.let { (cachedSource, luts) -> if (cachedSource === source) return luts }
    return ImageAdjustmentsDetail.enhanceLuts(source).also { cachedEnhance = source to it }
  }

  private fun blurredSmall(source: Bitmap): Bitmap {
    cachedBlur?.let { (cachedSource, small) -> if (cachedSource === source && !small.isRecycled) return small }
    return ImageAdjustmentsDetail.blurredSmall(source).also { cachedBlur = source to it }
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

  /** CPU 上走一遍（[ImageAdjustmentsDetail.applyOnCpu]）：`getPixels` 给的是没预乘的颜色，透明的像素不动。 */
  @VisibleForTesting
  internal fun applyOnCpu(source: Bitmap, pass: ImageAdjustmentsGl.Pass): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    ImageAdjustmentsDetail.applyOnCpu(pixels, width, height, pass.lut, pass.lutSize, pass.sharpen, pass.grain, pass.vignette, pass.frame, pass.blur, pass.enhance, pass.enhanceLuts)
    return createOutput(source).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
  }

  /** 只查表（测试用）。 */
  @VisibleForTesting
  internal fun applyOnCpu(source: Bitmap, lut: ByteArray, size: Int): Bitmap {
    return applyOnCpu(source, ImageAdjustmentsGl.Pass(lut, size, 0f, 0f, 0f, ImageAdjustmentsDetail.VignetteFrame.FULL_IMAGE))
  }

  /** 和 [source] 同颜色空间（API 26+）的空位图，默认同尺寸。 */
  internal fun createOutput(source: Bitmap, width: Int = source.width, height: Int = source.height): Bitmap {
    val colorSpace = if (Build.VERSION.SDK_INT >= 26) source.colorSpace else null
    val output = if (Build.VERSION.SDK_INT >= 26 && colorSpace != null) {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, source.hasAlpha(), colorSpace)
    } else {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
    output.setHasAlpha(source.hasAlpha())
    return output
  }
}
