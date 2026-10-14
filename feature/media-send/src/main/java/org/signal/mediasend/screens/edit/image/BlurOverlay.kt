/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.mediasend.screens.edit.image

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.signal.imageeditor.core.adjustments.ImageAdjustmentsDetail
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.ImageController
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Tellomi（tellomi/tellomi#1598 E5-8，Epic #1523）：调色页的「模糊」（移轴）页签。
 *
 * 照两端 Telegram 的模糊（安卓 `PhotoFilterBlurControl` + `PhotoFilterView` 的 blurItem / blurOffButton…，iOS `TGPhotoEditorBlurAreaView`；
 * 只读机制，代码自己写）：
 * - 面板里「关 / 径向 / 线性」三个切换；
 * - 照片上：中心一个圆点；径向画内外两个虚线圆，线性画内外两对虚线；
 * - 按在中心附近拖 = 挪中心；按在内（外）圈 / 线附近拖 = 改内（外）半径；线性时按在别处拖 = 绕中心转；两指捏 = 内外半径一起缩放。
 * O8：强度、人像模糊两端 Telegram 都没有，不做。
 */

/** 图上的单位坐标（左上为原点，0…1）和画布上的点之间换算；半径、距离在「短边 = 1」的单位里量。 */
internal class BlurMapping(private val unitToView: Matrix) {
  private val viewToUnit = Matrix().also { unitToView.invert(it) }

  /** 图的宽、高、短边在画布上有多长。 */
  val widthInView: Float = mappedLength(1f, 0f)
  val heightInView: Float = mappedLength(0f, 1f)
  val shortSideInView: Float = min(widthInView, heightInView)

  fun view(unitX: Float, unitY: Float): Offset {
    val point = floatArrayOf(unitX, unitY)
    unitToView.mapPoints(point)
    return Offset(point[0], point[1])
  }

  fun unit(view: Offset): Offset {
    val point = floatArrayOf(view.x, view.y)
    viewToUnit.mapPoints(point)
    return Offset(point[0], point[1])
  }

  /** 单位坐标的差 → 「短边 = 1」的向量。 */
  fun shortSideVector(unitDelta: Offset): Offset {
    return Offset(unitDelta.x * widthInView / shortSideInView, unitDelta.y * heightInView / shortSideInView)
  }

  fun unitDelta(shortSideVector: Offset): Offset {
    return Offset(shortSideVector.x * shortSideInView / widthInView, shortSideVector.y * shortSideInView / heightInView)
  }

  private fun mappedLength(x: Float, y: Float): Float {
    val vector = floatArrayOf(x, y)
    unitToView.mapVectors(vector)
    return hypot(vector[0], vector[1])
  }

  companion object {
    fun create(state: ImageEditorState): BlurMapping? {
      val matrix = state.editorModel.findImageUnitToView(state.viewMatrix) ?: return null
      return BlurMapping(matrix).takeIf { it.shortSideInView > 0f }
    }
  }
}

internal enum class BlurControl {
  CENTER,
  INNER,
  OUTER,
  ROTATION
}

/** 内半径最小、内外最少差多少（短边 = 1）。 */
internal const val BLUR_MINIMUM_INNER = 0.02f
internal const val BLUR_MINIMUM_GAP = 0.02f

/** 点到中心（径向）或到那条线（线性）的距离，短边 = 1。 */
private fun blurDistance(blur: ImageAdjustments.Blur, mapping: BlurMapping, point: Offset): Float {
  val unit = mapping.unit(point)
  val q = mapping.shortSideVector(Offset(unit.x - blur.centerX, unit.y - blur.centerY))
  return if (blur.type == ImageAdjustments.BlurType.RADIAL) {
    q.getDistance()
  } else {
    val (nx, ny) = ImageAdjustmentsDetail.blurNormal(blur)
    kotlin.math.abs(q.x * nx + q.y * ny)
  }
}

/** 按在 [point] 上算按住的是哪一部分（照两端 TG：中心 → 内圈 → 外圈，线性时别处是转）；[inset] 是多近算按在上面（像素）。 */
internal fun blurControlAt(blur: ImageAdjustments.Blur, mapping: BlurMapping, point: Offset, inset: Float): BlurControl? {
  if (blur.type == ImageAdjustments.BlurType.OFF) return null
  val center = mapping.view(blur.centerX, blur.centerY)
  if ((point - center).getDistance() < inset) {
    return BlurControl.CENTER
  }
  val distance = blurDistance(blur, mapping, point) * mapping.shortSideInView
  val (inner, outer) = ImageAdjustmentsDetail.blurRadii(blur)
  val toInner = kotlin.math.abs(distance - inner * mapping.shortSideInView)
  val toOuter = kotlin.math.abs(distance - outer * mapping.shortSideInView)
  if (min(toInner, toOuter) < inset) {
    return if (toInner <= toOuter) BlurControl.INNER else BlurControl.OUTER
  }
  return if (blur.type == ImageAdjustments.BlurType.LINEAR) BlurControl.ROTATION else null
}

/** 从 [from] 拖到 [to]：按 [control] 改 [start]。 */
internal fun blurDragged(control: BlurControl, start: ImageAdjustments.Blur, from: Offset, to: Offset, mapping: BlurMapping): ImageAdjustments.Blur {
  return when (control) {
    BlurControl.CENTER -> {
      val moved = mapping.unit(to) - mapping.unit(from)
      start.copy(centerX = (start.centerX + moved.x).coerceIn(0f, 1f), centerY = (start.centerY + moved.y).coerceIn(0f, 1f))
    }
    BlurControl.INNER, BlurControl.OUTER -> {
      val delta = blurDistance(start, mapping, to) - blurDistance(start, mapping, from)
      if (control == BlurControl.INNER) {
        start.copy(falloff = min(max(start.falloff + delta, BLUR_MINIMUM_INNER), start.size - BLUR_MINIMUM_GAP))
      } else {
        start.copy(size = max(start.size + delta, start.falloff + BLUR_MINIMUM_GAP))
      }
    }
    BlurControl.ROTATION -> {
      val center = Offset(start.centerX, start.centerY)
      val a = mapping.shortSideVector(mapping.unit(from) - center)
      val b = mapping.shortSideVector(mapping.unit(to) - center)
      if (a.getDistance() == 0f || b.getDistance() == 0f) start else start.copy(angle = start.angle + atan2(b.y, b.x) - atan2(a.y, a.x))
    }
  }
}

/** 两指捏：内外半径一起按比例缩放。 */
internal fun blurPinched(start: ImageAdjustments.Blur, scale: Float): ImageAdjustments.Blur {
  val falloff = max(start.falloff * scale, BLUR_MINIMUM_INNER)
  return start.copy(falloff = falloff, size = max(start.size * scale, falloff + BLUR_MINIMUM_GAP))
}

/** 模糊页签里照片上的手势：一指拖（中心 / 内圈 / 外圈 / 转），两指捏（缩放）。 */
internal suspend fun AwaitPointerEventScope.blurGesture(state: ImageEditorState, controller: ImageController, down: PointerInputChange) {
  val mapping = BlurMapping.create(state)
  val start = controller.adjustments.blur
  val control = mapping?.let { blurControlAt(start, it, down.position, 30.dp.toPx()) }
  var pinchStartDistance: Float? = null
  var pinchStart = start
  while (true) {
    val event = awaitPointerEvent()
    event.changes.forEach { it.consume() }
    val pressed = event.changes.filter { it.pressed }
    if (mapping != null) {
      if (pressed.size >= 2) {
        val distance = (pressed[0].position - pressed[1].position).getDistance()
        val startDistance = pinchStartDistance
        if (startDistance == null) {
          pinchStartDistance = distance
          pinchStart = controller.adjustments.blur
        } else if (startDistance > 0f) {
          controller.setBlur(blurPinched(pinchStart, distance / startDistance))
        }
      } else if (pressed.size == 1 && pinchStartDistance == null && control != null) {
        controller.setBlur(blurDragged(control, start, down.position, pressed[0].position, mapping))
      }
    }
    if (pressed.isEmpty()) {
      break
    }
  }
}

/** 盖在照片上的模糊控件（只画，手势在 [blurGesture]）。 */
@Composable
internal fun BlurOverlay(state: ImageEditorState, controller: ImageController, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier.testTag(BLUR_OVERLAY_TAG)) {
    state.revision
    val blur = controller.adjustments.blur
    if (blur.type == ImageAdjustments.BlurType.OFF) return@Canvas
    val mapping = BlurMapping.create(state) ?: return@Canvas
    val center = mapping.view(blur.centerX, blur.centerY)
    val (inner, outer) = ImageAdjustmentsDetail.blurRadii(blur)
    val innerStyle = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 6.dp.toPx())))
    val outerStyle = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
    // 只画在照片（裁剪框）里面，照 iOS 那边曲线层 / 模糊层贴着照片裁掉。
    val crop = state.editorModel.findCropRectInView(state.viewMatrix)
    clipRect(crop.left, crop.top, crop.right, crop.bottom) {
      if (blur.type == ImageAdjustments.BlurType.RADIAL) {
        drawCircle(Color.White, radius = inner * mapping.shortSideInView, center = center, style = innerStyle)
        drawCircle(Color.White, radius = outer * mapping.shortSideInView, center = center, style = outerStyle)
      } else {
        drawBand(blur, inner, mapping, innerStyle)
        drawBand(blur, outer, mapping, outerStyle)
      }
      drawCircle(Color.White, radius = 8.dp.toPx(), center = center)
    }
  }
}

/** 平行于那条线、离它 [distance]（短边 = 1）的两条线，在图上取得足够长（画出去的被画布裁掉）。 */
private fun DrawScope.drawBand(blur: ImageAdjustments.Blur, distance: Float, mapping: BlurMapping, style: Stroke) {
  val (nx, ny) = ImageAdjustmentsDetail.blurNormal(blur)
  val center = Offset(blur.centerX, blur.centerY)
  val path = Path()
  for (side in floatArrayOf(-1f, 1f)) {
    val offset = center + mapping.unitDelta(Offset(nx * distance * side, ny * distance * side))
    val start = offset + mapping.unitDelta(Offset(ny * 4f, -nx * 4f))
    val end = offset + mapping.unitDelta(Offset(-ny * 4f, nx * 4f))
    val a = mapping.view(start.x, start.y)
    val b = mapping.view(end.x, end.y)
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
  }
  drawPath(path, Color.White, style = style)
}

/** 模糊页签的面板：「关 / 径向 / 线性」三个切换（照两端 TG 的三个按钮）。 */
@Composable
internal fun BlurPanel(controller: ImageController, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
      .padding(vertical = 14.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically
  ) {
    ImageAdjustments.BlurType.entries.forEach { type ->
      val isSelected = controller.adjustments.blur.type == type
      Text(
        text = stringResource(
          when (type) {
            ImageAdjustments.BlurType.OFF -> R.string.ImageEditor_tellomi_adjust_blur_off
            ImageAdjustments.BlurType.RADIAL -> R.string.ImageEditor_tellomi_adjust_blur_radial
            ImageAdjustments.BlurType.LINEAR -> R.string.ImageEditor_tellomi_adjust_blur_linear
          }
        ),
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
        fontSize = 14.sp,
        modifier = Modifier
          .selectable(selected = isSelected, role = Role.RadioButton, onClick = { controller.selectBlurType(type) })
          .padding(horizontal = 12.dp, vertical = 6.dp)
          .testTag("$BLUR_TYPE_TAG-${type.name.lowercase(Locale.US)}")
      )
    }
  }
}

internal const val BLUR_OVERLAY_TAG = "tellomi-blur-overlay"
internal const val BLUR_TYPE_TAG = "tellomi-blur-type"
