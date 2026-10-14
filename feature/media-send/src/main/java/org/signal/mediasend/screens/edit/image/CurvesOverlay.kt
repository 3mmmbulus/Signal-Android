/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.mediasend.screens.edit.image

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.signal.imageeditor.core.adjustments.ImageAdjustmentsColor
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.mediasend.R
import org.signal.mediasend.screens.edit.ImageController
import java.util.Locale

/*
 * Tellomi（tellomi/tellomi#1597 E5-7，Epic #1523）：调色页的「曲线」页签。
 *
 * 照两端 Telegram 的曲线（安卓 `PhotoFilterCurvesControl` + `PhotoFilterView` 的 curveLayout，iOS `TGPhotoEditorCurvesToolView`；
 * 只读机制，代码自己写）：
 * - 照片上盖一层：竖线把宽度分成 5 段，一条虚线对角线（不调时的样子），当前那条曲线（全部白、红绿蓝各自的颜色），每段底下写那个点的值（0.25）；
 * - 在哪一段里上下拖就改哪个点（第 1 段 = 黑、…、第 5 段 = 白），这里点跟着手指走：拖过整个照片的高度 = 0…1；
 * - 面板里「全部 / 红 / 绿 / 蓝」四个单选（照 TG 安卓 `curveRadioButton`：彩色圆圈 + 名字）。
 * O8：直方图、双击复位只有 Telegram iOS 有，不做。
 */

/** 曲线的颜色（自己挑的，和 iOS 一样）。 */
internal fun curveChannelColor(channel: ImageAdjustments.CurveChannel): Color {
  return when (channel) {
    ImageAdjustments.CurveChannel.ALL -> Color.White
    ImageAdjustments.CurveChannel.RED -> Color(0xFFFF5A5F)
    ImageAdjustments.CurveChannel.GREEN -> Color(0xFF3DDC84)
    ImageAdjustments.CurveChannel.BLUE -> Color(0xFF4D8CFF)
  }
}

internal const val CURVE_SEGMENTS = 5

/** 横坐标 [x] 落在 [rect] 的哪一段（0…4）。 */
internal fun curveSegment(x: Float, rect: RectF): Int {
  if (rect.width() <= 0f) return 0
  return ((x - rect.left) / (rect.width() / CURVE_SEGMENTS)).toInt().coerceIn(0, CURVE_SEGMENTS - 1)
}

/** 一个点的值：照两端 TG，值 / 100，两位小数（0.25）。 */
internal fun curveValueText(value: Float): String = String.format(Locale.US, "%.2f", value / 100f)

/**
 * 曲线页签里照片上的手势：按在照片（裁剪框）里，看在哪一段，之后上下拖改那个点，点跟着手指（拖过整个高度 = 100）；
 * 按在照片外面什么都不做。
 */
internal suspend fun AwaitPointerEventScope.curveGesture(state: ImageEditorState, controller: ImageController, down: PointerInputChange) {
  val rect = state.editorModel.findCropRectInView(state.viewMatrix)
  val inside = rect.contains(down.position.x, down.position.y) && rect.height() > 0f
  val segment = curveSegment(down.position.x, rect)
  var lastY = down.position.y
  while (true) {
    val event = awaitPointerEvent()
    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
    event.changes.forEach { it.consume() }
    if (inside && change.pressed) {
      val dy = change.position.y - lastY
      lastY = change.position.y
      if (dy != 0f) {
        controller.moveCurvePoint(segment, -dy / rect.height() * 100f)
      }
    }
    if (event.changes.none { it.pressed }) {
      break
    }
  }
}

/** 盖在照片上的曲线层（只画，手势在 [curveGesture]）。 */
@Composable
internal fun CurvesOverlay(state: ImageEditorState, controller: ImageController, modifier: Modifier = Modifier) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
  Canvas(modifier = modifier.testTag(CURVES_OVERLAY_TAG)) {
    state.revision
    val rect = state.editorModel.findCropRectInView(state.viewMatrix)
    if (rect.width() <= 0f || rect.height() <= 0f) return@Canvas
    val channel = controller.curveChannel
    val points = controller.adjustments.curves[channel]

    val segmentWidth = rect.width() / CURVE_SEGMENTS
    val gridColor = Color.White.copy(alpha = 0.3f)
    for (index in 1 until CURVE_SEGMENTS) {
      val x = rect.left + segmentWidth * index
      drawLine(gridColor, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1.dp.toPx())
    }
    drawLine(
      color = Color.White.copy(alpha = 0.4f),
      start = Offset(rect.left, rect.bottom),
      end = Offset(rect.right, rect.top),
      strokeWidth = 1.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    )

    val spline = ImageAdjustmentsColor.CurveSpline(points)
    val path = Path()
    val steps = 100
    for (step in 0..steps) {
      val x = step / steps.toFloat()
      val point = Offset(rect.left + x * rect.width(), rect.top + (1f - spline.value(x)) * rect.height())
      if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, curveChannelColor(channel), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

    for (index in 0 until CURVE_SEGMENTS) {
      val layout = textMeasurer.measure(curveValueText(points[index]), labelStyle)
      drawText(
        textLayoutResult = layout,
        topLeft = Offset(
          rect.left + segmentWidth * index + (segmentWidth - layout.size.width) / 2f,
          rect.bottom - layout.size.height - 6.dp.toPx()
        )
      )
    }
  }
}

/** 曲线页签的面板：「全部 / 红 / 绿 / 蓝」四个单选（照 TG 安卓 `curveRadioButton`）。 */
@Composable
internal fun CurvesPanel(controller: ImageController, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
      .padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically
  ) {
    ImageAdjustments.CurveChannel.entries.forEach { channel ->
      CurveChannelButton(
        channel = channel,
        isSelected = controller.curveChannel == channel,
        onClick = { controller.selectCurveChannel(channel) }
      )
    }
  }
}

@Composable
private fun CurveChannelButton(channel: ImageAdjustments.CurveChannel, isSelected: Boolean, onClick: () -> Unit) {
  val color = curveChannelColor(channel)
  Column(
    modifier = Modifier
      .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 8.dp)
      .testTag("$CURVES_CHANNEL_TAG-${channel.name.lowercase(Locale.US)}"),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
      Canvas(modifier = Modifier.size(20.dp)) {
        val ring = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2 - ring / 2, style = Stroke(width = ring))
        if (isSelected) {
          drawCircle(color, radius = size.minDimension / 2 - ring * 2.5f)
        }
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = curveChannelTitle(channel), color = Color.White, fontSize = 12.sp)
  }
}

@Composable
private fun curveChannelTitle(channel: ImageAdjustments.CurveChannel): String {
  return stringResource(
    when (channel) {
      ImageAdjustments.CurveChannel.ALL -> R.string.ImageEditor_tellomi_adjust_curves_all
      ImageAdjustments.CurveChannel.RED -> R.string.ImageEditor_tellomi_adjust_curves_red
      ImageAdjustments.CurveChannel.GREEN -> R.string.ImageEditor_tellomi_adjust_curves_green
      ImageAdjustments.CurveChannel.BLUE -> R.string.ImageEditor_tellomi_adjust_curves_blue
    }
  )
}

internal const val CURVES_OVERLAY_TAG = "tellomi-curves-overlay"
internal const val CURVES_CHANNEL_TAG = "tellomi-curves-channel"
