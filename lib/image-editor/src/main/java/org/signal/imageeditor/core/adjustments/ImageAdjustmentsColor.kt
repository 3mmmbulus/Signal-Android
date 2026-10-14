/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.adjustments

import org.signal.imageeditor.core.model.ImageAdjustments
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1592 E5-2、#1594 E5-4，Epic #1523）：调色里逐像素的那几项（曝光、对比度、高光、阴影、饱和度、色温、褪色）的数学，
 * 以及把它们合成的 3D 查找表。
 *
 * 和 iOS `TellomiImageAdjustmentsRenderer.adjust` 是同一套公式（`docs/product/specs/media-editor-adjustments-e5.md`
 * 第 4 节 E5-2），两端发出的图看起来一样。每一项的数学是自己定的，Telegram 只用来对照工具、范围和方向，它的着色器一行不搬：
 * - 曝光：线性光里乘增益，+100 = +2 档、−100 = −1.5 档；调亮时用一个让原来的白仍落在白上的肩，亮部不会一下顶死。
 * - 对比度：绕中灰拉开 / 收拢，系数 1.4^(值/100)。
 * - 饱和度：和同亮度的灰按比例混（Rec. 709 亮度），0…2 倍。
 * - 色温：线性光里红、蓝反向增益（暖 = 红多蓝少），再把亮度拉回原来。
 * - 高光 / 阴影：只改亮度（按比例缩放三个通道，色相不变）；阴影在亮度 0.25 附近、高光在 0.75 附近各一个鼓包，
 *   y' = y + k·(值/100)·b(y)，b_阴影(y) = y(1−y)³·256/27，b_高光(y) = b_阴影(1−y)；k = 0.08，提亮高光时 0.14（TG 提亮高光推向白更狠）。
 * - 褪色：往一条「哑光」曲线 t(c) = 0.24 + 0.42c + 0.24c² 混（黑抬到 0.24、白压到 0.9、中灰几乎不动），混的比例 = 值/100。
 *
 * 后面的逐像素工具（色调、曲线）往 [adjust] 里加；暗角、颗粒、锐化、模糊这些和位置有关的另走 pass。
 */
object ImageAdjustmentsColor {

  /** 查找表每边的格数（.cube 常用的大小）。 */
  const val LUT_SIZE = 33

  private const val LUMA_R = 0.2126f
  private const val LUMA_G = 0.7152f
  private const val LUMA_B = 0.0722f

  /**
   * 一个编码值（0…1，sRGB / Display P3 用同一条传递曲线）的颜色调色后的样子，写回 [rgb]。
   * 顺序：曝光 → 对比度 → 高光 / 阴影 → 饱和度 → 色温 → 褪色，每一步后夹回 0…1。
   */
  fun adjust(rgb: FloatArray, adjustments: ImageAdjustments) {
    if (adjustments.exposure != 0f) {
      val amount = adjustments.exposure / 100f
      for (i in 0..2) {
        var linear = toLinear(rgb[i])
        if (amount > 0f) {
          val gain = 2f.pow(2f * amount)
          val boosted = linear * gain
          // 原来的白（1）乘上增益后正好压回 1：x · (1 + x / gain²) / (1 + x)
          linear = boosted * (1f + boosted / (gain * gain)) / (1f + boosted)
        } else {
          linear *= 2f.pow(1.5f * amount)
        }
        rgb[i] = clamp01(toEncoded(clamp01(linear)))
      }
    }

    if (adjustments.contrast != 0f) {
      val factor = 1.4f.pow(adjustments.contrast / 100f)
      for (i in 0..2) {
        rgb[i] = clamp01((rgb[i] - 0.5f) * factor + 0.5f)
      }
    }

    if (adjustments.highlights != 0f || adjustments.shadows != 0f) {
      val y = luma(rgb[0], rgb[1], rgb[2])
      val shadows = adjustments.shadows / 100f
      val highlights = adjustments.highlights / 100f
      val target = clamp01(y + 0.08f * shadows * shadowBump(y) + (if (highlights > 0f) 0.14f else 0.08f) * highlights * shadowBump(1f - y))
      for (i in 0..2) {
        rgb[i] = clamp01(if (y > 1e-4f) rgb[i] * target / y else rgb[i] + (target - y))
      }
    }

    if (adjustments.saturation != 0f) {
      val factor = 1f + adjustments.saturation / 100f
      val gray = luma(rgb[0], rgb[1], rgb[2])
      for (i in 0..2) {
        rgb[i] = clamp01(gray + (rgb[i] - gray) * factor)
      }
    }

    if (adjustments.warmth != 0f) {
      val amount = adjustments.warmth / 100f
      var r = toLinear(rgb[0])
      var g = toLinear(rgb[1])
      var b = toLinear(rgb[2])
      val before = luma(r, g, b)
      r *= 1f + 0.3f * amount
      b *= 1f - 0.3f * amount
      val after = luma(r, g, b)
      if (after > 0f) {
        val scale = before / after
        r *= scale
        g *= scale
        b *= scale
      }
      rgb[0] = clamp01(toEncoded(clamp01(r)))
      rgb[1] = clamp01(toEncoded(clamp01(g)))
      rgb[2] = clamp01(toEncoded(clamp01(b)))
    }

    if (adjustments.fade != 0f) {
      val amount = adjustments.fade / 100f
      for (i in 0..2) {
        val c = rgb[i]
        rgb[i] = clamp01(c + (0.24f + 0.42f * c + 0.24f * c * c - c) * amount)
      }
    }
  }

  /** 在亮度 0.25 处最高（= 1）的鼓包，0 和 1 处为 0。 */
  private fun shadowBump(y: Float): Float {
    val inverse = 1f - y
    return y * inverse * inverse * inverse * (256f / 27f)
  }

  /**
   * 查找表，RGBA8，排成一张 2D 图：宽 = n × n（蓝色分 n 片，每片里红从左到右），高 = n（绿从上到下）；
   * 第 (b·n + r, g) 格是颜色 (r, g, b) / (n − 1) 调色后的值。GLES 2.0 没有 3D 纹理，着色器在两片之间自己插值。
   */
  fun buildLut(adjustments: ImageAdjustments, size: Int = LUT_SIZE): ByteArray {
    val bytes = ByteArray(size * size * size * 4)
    val rgb = FloatArray(3)
    val scale = 1f / (size - 1)
    val rowStride = size * size * 4
    for (blue in 0 until size) {
      for (green in 0 until size) {
        for (red in 0 until size) {
          rgb[0] = red * scale
          rgb[1] = green * scale
          rgb[2] = blue * scale
          adjust(rgb, adjustments)
          val index = green * rowStride + (blue * size + red) * 4
          bytes[index] = toByte(rgb[0])
          bytes[index + 1] = toByte(rgb[1])
          bytes[index + 2] = toByte(rgb[2])
          bytes[index + 3] = 0xFF.toByte()
        }
      }
    }
    return bytes
  }

  /**
   * 在 [buildLut] 的表里三线性查一个颜色（0…1），写回 [rgb]。CPU 兜底和测试用；GPU 上同样的查法在着色器里。
   */
  fun lookup(lut: ByteArray, size: Int, rgb: FloatArray) {
    val max = (size - 1).toFloat()
    val r = rgb[0].coerceIn(0f, 1f) * max
    val g = rgb[1].coerceIn(0f, 1f) * max
    val b = rgb[2].coerceIn(0f, 1f) * max
    val r0 = floor(r).toInt().coerceAtMost(size - 2)
    val g0 = floor(g).toInt().coerceAtMost(size - 2)
    val b0 = floor(b).toInt().coerceAtMost(size - 2)
    val fr = r - r0
    val fg = g - g0
    val fb = b - b0
    for (channel in 0..2) {
      fun at(ri: Int, gi: Int, bi: Int): Float = (lut[gi * size * size * 4 + (bi * size + ri) * 4 + channel].toInt() and 0xFF) / 255f
      val c00 = at(r0, g0, b0) * (1 - fr) + at(r0 + 1, g0, b0) * fr
      val c10 = at(r0, g0 + 1, b0) * (1 - fr) + at(r0 + 1, g0 + 1, b0) * fr
      val c01 = at(r0, g0, b0 + 1) * (1 - fr) + at(r0 + 1, g0, b0 + 1) * fr
      val c11 = at(r0, g0 + 1, b0 + 1) * (1 - fr) + at(r0 + 1, g0 + 1, b0 + 1) * fr
      val low = c00 * (1 - fg) + c10 * fg
      val high = c01 * (1 - fg) + c11 * fg
      rgb[channel] = low * (1 - fb) + high * fb
    }
  }

  private fun luma(r: Float, g: Float, b: Float): Float = r * LUMA_R + g * LUMA_G + b * LUMA_B

  private fun toLinear(value: Float): Float = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

  private fun toEncoded(value: Float): Float = if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f

  private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

  private fun toByte(value: Float): Byte = (value * 255f).roundToInt().coerceIn(0, 255).toByte()
}
