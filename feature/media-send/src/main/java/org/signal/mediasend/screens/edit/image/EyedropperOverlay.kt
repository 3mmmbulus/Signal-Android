/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.signal.mediasend.test.TestTags
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 安卓 `PipettePickerView` 的做法）：吸管。
 *
 * [snapshot] 是画布现在的样子（和画布同样大小）。放大镜一开始在正中，跟着手指走，圈里是手指下 7 × 7 个像素放大、带格线，
 * 外圈是取到的颜色；松手就取这个颜色（[onPicked]），手势被取消则 [onCancel]。自己写的，一行没搬（GPLv2）。
 */
@Composable
internal fun EyedropperOverlay(
  snapshot: Bitmap,
  onPicked: (Int) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier
) {
  val image = remember(snapshot) { snapshot.asImageBitmap() }
  var position by remember(snapshot) { mutableStateOf<Offset?>(null) }

  Canvas(
    modifier = modifier
      .testTag(TestTags.IMAGE_EDITOR_EYEDROPPER)
      .pointerInput(snapshot) {
        awaitEachGesture {
          val down = awaitFirstDown()
          down.consume()
          position = down.position
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
              onCancel()
              break
            }
            change.consume()
            position = change.position
            if (!change.pressed) {
              onPicked(Eyedropper.colorAt(snapshot, change.position.x / size.width, change.position.y / size.height))
              break
            }
          }
        }
      }
  ) {
    val center = position ?: Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) * Eyedropper.RADIUS_FRACTION
    val u = center.x / size.width
    val v = center.y / size.height
    val color = Color(Eyedropper.colorAt(snapshot, u, v))
    val pixelX = (u * snapshot.width).roundToInt()
    val pixelY = (v * snapshot.height).roundToInt()
    val half = Eyedropper.PIXELS / 2

    val ring = 6.dp.toPx()
    val outline = 2.dp.toPx()
    val inner = radius - ring - outline
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, inner)) }
    clipPath(circle) {
      drawImage(
        image = image,
        srcOffset = IntOffset(pixelX - half, pixelY - half),
        srcSize = IntSize(Eyedropper.PIXELS, Eyedropper.PIXELS),
        dstOffset = IntOffset((center.x - inner).roundToInt(), (center.y - inner).roundToInt()),
        dstSize = IntSize((inner * 2).roundToInt(), (inner * 2).roundToInt()),
        filterQuality = FilterQuality.None
      )
      val cell = inner * 2 / Eyedropper.PIXELS
      for (i in 1 until Eyedropper.PIXELS) {
        val offset = -inner + i * cell
        drawLine(Color.White.copy(alpha = 0.25f), Offset(center.x + offset, center.y - inner), Offset(center.x + offset, center.y + inner), strokeWidth = 1f)
        drawLine(Color.White.copy(alpha = 0.25f), Offset(center.x - inner, center.y + offset), Offset(center.x + inner, center.y + offset), strokeWidth = 1f)
      }
      drawRect(Color.White, topLeft = Offset(center.x - cell / 2, center.y - cell / 2), size = Size(cell, cell), style = Stroke(width = 2f))
    }
    drawCircle(color, radius = radius - ring / 2, center = center, style = Stroke(width = ring))
    drawCircle(Color.White, radius = radius - ring - outline / 2, center = center, style = Stroke(width = outline))
  }
}

internal object Eyedropper {
  /** 放大镜半径占画布短边的比例（Telegram 安卓 `PipettePickerView` 也是 0.2）。 */
  const val RADIUS_FRACTION = 0.2f

  /** 放大镜里横竖各几个像素。 */
  const val PIXELS = 7

  /** 位图上 (u, v)（0…1）处的颜色，越界的取边上的。 */
  @ColorInt
  fun colorAt(bitmap: Bitmap, u: Float, v: Float): Int {
    val x = (u * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val y = (v * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    return bitmap.getPixel(x, y)
  }
}
