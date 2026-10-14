/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import org.signal.imageeditor.core.model.ImageAdjustments
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tellomi（tellomi/tellomi#1595 E5-5，Epic #1523）：调色里和位置有关的三项——锐化、颗粒、暗角——的定义和 CPU 实现。
 * GPU 上同样的算法在 [ImageAdjustmentsGl] 的着色器里；和 iOS `TellomiImageAdjustments.metal` 是同一套（自己定的，Telegram 的着色器一行不搬）。
 *
 * 都在编码值（0…1）上算；顺序照两端 Telegram：锐化 → 查找表（[ImageAdjustmentsColor]）→ 颗粒 → 暗角。
 * 尺寸用一个「格」u = max(1, 长边 / 2048) 像素量：预览图（长边 ≤ 2048）上 u = 1，原尺寸图上按比例放大，预览和发出的图看起来一样。
 * - 锐化：c' = c + 2.5·(值/100)·(c − 模糊)，模糊是以 u 为一格的 3×3 帐篷核（1 2 1 / 2 4 2 / 1 2 1，除以 16），图边外取最靠边的像素。
 * - 颗粒：[NOISE]（256 × 256，四边循环）按 2u 像素一格双线性插值，c' = c + 0.06·(值/100)·w(y)·n，w(y) = 1 − |2y − 1|³，单色。只用于照片。
 * - 暗角：跟着画框（裁剪后的输出范围）走；[VignetteFrame] 把图上的单位坐标映到画框坐标 d（中心 0，四边 ±1），
 *   ρ = min(|d|/√2, 1)，k = (值/100)·ρ³，c' = c^(1 + 1.2k)·(1 − 0.35k)。
 */
object ImageAdjustmentsDetail {

  private const val LUMA_R = 0.2126f
  private const val LUMA_G = 0.7152f
  private const val LUMA_B = 0.0722f

  /** 锐化的倍数（值 = 100 时）。 */
  const val SHARPEN_AMOUNT = 2.5f

  /** 颗粒的振幅（值 = 100 时）。 */
  const val GRAIN_AMPLITUDE = 0.06f

  /** 颗粒的噪声一格是几个 u。 */
  const val GRAIN_CELL = 2f

  const val NOISE_SIZE = 256

  /**
   * 暗角跟着的画框：图上的单位坐标（左上为原点，0…1）→ 画框坐标 d（中心 0，四边 ±1）。
   * dx = xx·ux + xy·uy + x0，dy = yx·ux + yy·uy + y0。
   */
  data class VignetteFrame(val xx: Float, val xy: Float, val x0: Float, val yx: Float, val yy: Float, val y0: Float) {
    companion object {
      /** 整张图就是画框（没裁剪）。 */
      @JvmField
      val FULL_IMAGE = VignetteFrame(2f, 0f, -1f, 0f, 2f, -1f)
    }
  }

  /** 颗粒的噪声块：xorshift32（种子 0x2545F491），每步取高 8 位，256 × 256，第 0 行在上。iOS `TellomiImageAdjustmentsRenderer.noiseTile` 同一组字节。 */
  val NOISE: ByteArray by lazy {
    var state = 0x2545F491
    ByteArray(NOISE_SIZE * NOISE_SIZE) {
      state = state xor (state shl 13)
      state = state xor (state ushr 17)
      state = state xor (state shl 5)
      (state ushr 24).toByte()
    }
  }

  /** 锐化、颗粒量尺寸的「格」：预览图（长边 ≤ 2048）是 1 像素，更大的图按长边 / 2048 放大。 */
  @JvmStatic
  fun unit(longSide: Int): Float = max(1f, longSide / 2048f)

  /** 颗粒加的量按亮度的权重：中间调 1，纯黑纯白 0。 */
  @JvmStatic
  fun grainWeight(luma: Float): Float {
    val t = abs(2f * luma - 1f)
    return 1f - t * t * t
  }

  /** 画框坐标 (dx, dy) 处的暗角强度 k（[vignette] 是 值/100）。 */
  @JvmStatic
  fun vignetteStrength(vignette: Float, dx: Float, dy: Float): Float {
    val rho = min(sqrt(dx * dx + dy * dy) / sqrt(2f), 1f)
    return vignette * rho * rho * rho
  }

  /** 暗角压一个编码值：c^(1 + 1.2k)·(1 − 0.35k)。 */
  @JvmStatic
  fun vignette(value: Float, k: Float): Float = value.pow(1f + 1.2f * k) * (1f - 0.35f * k)

  /** 查找表只管逐像素的几项：把和位置有关的几项放回默认。只动了它们时不用查表，拖它们的滑杆也不用重算表。 */
  @JvmStatic
  fun colorOnly(adjustments: ImageAdjustments): ImageAdjustments {
    return adjustments.copy(sharpen = 0f, grain = 0f, vignette = 0f, blur = ImageAdjustments.Blur())
  }

  /** 噪声在 (x, y)（以格为单位，格子中心在 整数 + 0.5）处双线性插值的值，−1…1，四边循环。 */
  fun noiseAt(x: Float, y: Float): Float {
    val qx = x - 0.5f
    val qy = y - 0.5f
    val cx = floor(qx)
    val cy = floor(qy)
    val fx = qx - cx
    val fy = qy - cy
    val x0 = Math.floorMod(cx.toInt(), NOISE_SIZE)
    val y0 = Math.floorMod(cy.toInt(), NOISE_SIZE)
    val x1 = (x0 + 1) % NOISE_SIZE
    val y1 = (y0 + 1) % NOISE_SIZE
    val top = cell(x0, y0) * (1f - fx) + cell(x1, y0) * fx
    val bottom = cell(x0, y1) * (1f - fx) + cell(x1, y1) * fx
    return top * (1f - fy) + bottom * fy
  }

  private fun cell(x: Int, y: Int): Float = ((NOISE[y * NOISE_SIZE + x].toInt() and 0xFF) - 127.5f) / 127.5f

  /**
   * CPU 上整条走一遍（GL 不可用时的兜底；结果和着色器一样）：[pixels] 是 `getPixels` 给的没预乘的 ARGB，原地改。
   * 参数同 [ImageAdjustmentsGl.Pass]：[lut] 为 null 表示不查表；[sharpen] / [grain] / [vignetteAmount] 是倍数、振幅、值/100，0 = 不做。
   * 透明的像素不动。
   */
  fun applyOnCpu(
    pixels: IntArray,
    width: Int,
    height: Int,
    lut: ByteArray?,
    lutSize: Int,
    sharpen: Float,
    grain: Float,
    vignetteAmount: Float,
    frame: VignetteFrame
  ) {
    val u = unit(max(width, height))
    val source = if (sharpen > 0f) pixels.copyOf() else null
    val cellSize = GRAIN_CELL * u
    val rgb = FloatArray(3)
    val blur = FloatArray(3)

    for (y in 0 until height) {
      for (x in 0 until width) {
        val index = y * width + x
        val color = pixels[index]
        val alpha = color ushr 24
        if (alpha == 0) continue
        rgb[0] = ((color shr 16) and 0xFF) / 255f
        rgb[1] = ((color shr 8) and 0xFF) / 255f
        rgb[2] = (color and 0xFF) / 255f

        if (source != null) {
          tentBlur(source, width, height, x + 0.5f, y + 0.5f, u, blur)
          for (i in 0..2) {
            rgb[i] = (rgb[i] + sharpen * (rgb[i] - blur[i])).coerceIn(0f, 1f)
          }
        }

        if (lut != null) {
          ImageAdjustmentsColor.lookup(lut, lutSize, rgb)
        }

        if (grain > 0f) {
          val n = noiseAt((x + 0.5f) / cellSize, (y + 0.5f) / cellSize)
          val add = grain * grainWeight(rgb[0] * LUMA_R + rgb[1] * LUMA_G + rgb[2] * LUMA_B) * n
          for (i in 0..2) {
            rgb[i] = (rgb[i] + add).coerceIn(0f, 1f)
          }
        }

        if (vignetteAmount > 0f) {
          val ux = (x + 0.5f) / width
          val uy = (y + 0.5f) / height
          val k = vignetteStrength(vignetteAmount, frame.xx * ux + frame.xy * uy + frame.x0, frame.yx * ux + frame.yy * uy + frame.y0)
          for (i in 0..2) {
            rgb[i] = vignette(rgb[i], k)
          }
        }

        pixels[index] = (alpha shl 24) or (toByte(rgb[0]) shl 16) or (toByte(rgb[1]) shl 8) or toByte(rgb[2])
      }
    }
  }

  /** 以 [step] 像素为一格的 3×3 帐篷核，在 (px, py)（像素中心是 整数 + 0.5）处双线性取样，图边外取最靠边的像素。 */
  private fun tentBlur(pixels: IntArray, width: Int, height: Int, px: Float, py: Float, step: Float, out: FloatArray) {
    out.fill(0f)
    val sample = FloatArray(3)
    for (j in -1..1) {
      for (i in -1..1) {
        val weight = (if (i == 0) 2f else 1f) * (if (j == 0) 2f else 1f) / 16f
        bilinear(pixels, width, height, px + i * step, py + j * step, sample)
        for (c in 0..2) {
          out[c] += sample[c] * weight
        }
      }
    }
  }

  private fun bilinear(pixels: IntArray, width: Int, height: Int, px: Float, py: Float, out: FloatArray) {
    val qx = px - 0.5f
    val qy = py - 0.5f
    val cx = floor(qx)
    val cy = floor(qy)
    val fx = qx - cx
    val fy = qy - cy
    val x0 = cx.toInt().coerceIn(0, width - 1)
    val y0 = cy.toInt().coerceIn(0, height - 1)
    val x1 = (cx.toInt() + 1).coerceIn(0, width - 1)
    val y1 = (cy.toInt() + 1).coerceIn(0, height - 1)
    for (c in 0..2) {
      val shift = 16 - 8 * c
      fun at(x: Int, y: Int): Float = ((pixels[y * width + x] shr shift) and 0xFF) / 255f
      val top = at(x0, y0) * (1f - fx) + at(x1, y0) * fx
      val bottom = at(x0, y1) * (1f - fx) + at(x1, y1) * fx
      out[c] = top * (1f - fy) + bottom * fy
    }
  }

  private fun toByte(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
}
