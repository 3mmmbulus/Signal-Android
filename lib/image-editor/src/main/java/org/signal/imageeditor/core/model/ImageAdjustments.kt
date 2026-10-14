/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.model

import android.os.Parcelable
import androidx.annotation.ColorInt
import kotlinx.parcelize.Parcelize
import kotlin.math.PI

/**
 * Tellomi（tellomi/tellomi#1591 E5-1，Epic #1523）：一张照片的调色参数（照 Telegram 安卓的「调整」15 项）。
 *
 * 只是参数，不带渲染（渲染是 E5-2）。发出去的是烘焙后的普通图片，参数不进协议。
 * 存在主图的 [org.signal.imageeditor.core.renderers.UriGlideRenderer] 里，是元素树的一部分：撤销 / 重做、
 * [EditorModel.isChanged]（发出时重新渲染）、[EditorModel.createSnapshot] 都自动带上。
 *
 * 范围、默认值照 Telegram 安卓 `PhotoFilterView`（只读，代码是自己写的；`docs/product/specs/media-editor-adjustments-e5.md` 第 1 节）：
 * - 双极 −100…100（0 不变）：曝光、对比度、饱和度、色温、高光、阴影；
 * - 单极 0…100（0 不变）：美肤、增强、褪色、暗角、颗粒、锐化；
 * - 色调：暗部、亮部各一个颜色（[NO_TINT] = 不加），强度固定 50%（TG 安卓 `getTint*IntensityValue` 写死）；
 * - 曲线：全部 / 红 / 绿 / 蓝 4 条，每条 5 个点（`CurvesValue` 默认 0 / 25 / 50 / 75 / 100）；
 * - 模糊：关 / 径向 / 线性，中心 (0.5, 0.5)、大小 0.35、过渡 0.15、角度 π/2（TG 安卓的默认；iOS 照 TG iOS 是 0.24 / 0.12 / 0）。
 *
 * 数值用 [with] 写进来（夹到范围里）；判断「调过没有」用 [isDefault]，不要拿 `== DEFAULT`（data class 的相等把 −0 和 0 当两个数）。
 */
@Parcelize
data class ImageAdjustments(
  val skin: Float = 0f,
  val enhance: Float = 0f,
  val exposure: Float = 0f,
  val contrast: Float = 0f,
  val saturation: Float = 0f,
  val warmth: Float = 0f,
  val fade: Float = 0f,
  val highlights: Float = 0f,
  val shadows: Float = 0f,
  val vignette: Float = 0f,
  val grain: Float = 0f,
  val sharpen: Float = 0f,
  @ColorInt val tintShadowsColor: Int = NO_TINT,
  @ColorInt val tintHighlightsColor: Int = NO_TINT,
  val curves: Curves = Curves(),
  val blur: Blur = Blur()
) : Parcelable {

  /** 全在默认值：渲染整条跳过（同 TG 安卓各工具的 `shouldBeSkipped`）。模糊关着时它的中心、大小等不算。 */
  val isDefault: Boolean
    get() = Tool.entries.all { get(it) == 0f } &&
      tintShadowsColor == NO_TINT &&
      tintHighlightsColor == NO_TINT &&
      curves.isDefault &&
      blur.isDefault

  operator fun get(tool: Tool): Float {
    return when (tool) {
      Tool.SKIN -> skin
      Tool.ENHANCE -> enhance
      Tool.EXPOSURE -> exposure
      Tool.CONTRAST -> contrast
      Tool.SATURATION -> saturation
      Tool.WARMTH -> warmth
      Tool.FADE -> fade
      Tool.HIGHLIGHTS -> highlights
      Tool.SHADOWS -> shadows
      Tool.VIGNETTE -> vignette
      Tool.GRAIN -> grain
      Tool.SHARPEN -> sharpen
    }
  }

  /** 换一项的值，夹到这一项的范围里（NaN 当 0）。 */
  fun with(tool: Tool, value: Float): ImageAdjustments {
    // + 0f 把 −0 变成 0
    val clamped = if (value.isNaN()) 0f else value.coerceIn(tool.range) + 0f
    return when (tool) {
      Tool.SKIN -> copy(skin = clamped)
      Tool.ENHANCE -> copy(enhance = clamped)
      Tool.EXPOSURE -> copy(exposure = clamped)
      Tool.CONTRAST -> copy(contrast = clamped)
      Tool.SATURATION -> copy(saturation = clamped)
      Tool.WARMTH -> copy(warmth = clamped)
      Tool.FADE -> copy(fade = clamped)
      Tool.HIGHLIGHTS -> copy(highlights = clamped)
      Tool.SHADOWS -> copy(shadows = clamped)
      Tool.VIGNETTE -> copy(vignette = clamped)
      Tool.GRAIN -> copy(grain = clamped)
      Tool.SHARPEN -> copy(sharpen = clamped)
    }
  }

  /** 12 个滑杆项。双极（−100…100）还是单极（0…100）。 */
  enum class Tool(val isBipolar: Boolean) {
    SKIN(false),
    ENHANCE(false),
    EXPOSURE(true),
    CONTRAST(true),
    SATURATION(true),
    WARMTH(true),
    FADE(false),
    HIGHLIGHTS(true),
    SHADOWS(true),
    VIGNETTE(false),
    GRAIN(false),
    SHARPEN(false);

    val range: ClosedFloatingPointRange<Float>
      get() = if (isBipolar) -100f..100f else 0f..100f
  }

  /** 一条曲线的 5 个控制点（输出值 0…100；输入固定在 0 / 25 / 50 / 75 / 100）。 */
  @Parcelize
  data class CurvePoints(
    val blacks: Float = 0f,
    val shadows: Float = 25f,
    val midtones: Float = 50f,
    val highlights: Float = 75f,
    val whites: Float = 100f
  ) : Parcelable {
    val values: List<Float>
      get() = listOf(blacks, shadows, midtones, highlights, whites)

    val isDefault: Boolean
      get() = blacks == 0f && shadows == 25f && midtones == 50f && highlights == 75f && whites == 100f
  }

  /** 全部 / 红 / 绿 / 蓝 4 条曲线。 */
  @Parcelize
  data class Curves(
    val all: CurvePoints = CurvePoints(),
    val red: CurvePoints = CurvePoints(),
    val green: CurvePoints = CurvePoints(),
    val blue: CurvePoints = CurvePoints()
  ) : Parcelable {
    val isDefault: Boolean
      get() = all.isDefault && red.isDefault && green.isDefault && blue.isDefault
  }

  enum class BlurType {
    OFF,
    RADIAL,
    LINEAR
  }

  /** 模糊（移轴）。中心是相对图宽高的 0…1 坐标；大小、过渡是相对图的比例（怎么换算成像素在 E5-8 定）；角度是弧度。 */
  @Parcelize
  data class Blur(
    val type: BlurType = BlurType.OFF,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val size: Float = 0.35f,
    val falloff: Float = 0.15f,
    val angle: Float = (PI / 2).toFloat()
  ) : Parcelable {
    val isDefault: Boolean
      get() = type == BlurType.OFF
  }

  companion object {
    /** 色调没选。 */
    const val NO_TINT = 0

    /** 色调强度：两端固定 50%（O8）。 */
    const val TINT_INTENSITY = 50f

    @JvmField
    val DEFAULT = ImageAdjustments()
  }
}
