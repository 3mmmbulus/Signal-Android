/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.renderers

import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tellomi（tellomi/tellomi#1522 E-3b）：选中形状后的拉柄（两端 Telegram 都有：安卓 `ShapeInput` 的角柄、尾巴柄、箭头两端 + 中间；
 * iOS `DrawingSimpleShapeEntityView` 的角柄、`DrawingBubbleEntityView` 的尾巴柄、`DrawingVectorEntityView` 的两端 + 中间）。
 *
 * - 矩形、气泡：四个角，拉一个角、对角不动，宽高各自变（气泡拉的是身子的角，尾巴长度不变）；
 * - 圆、星形：四个角，对角不动、等比；
 * - 气泡：尾巴尖，左右挪、往下拉长（照 Telegram iOS，尾巴只在底边；安卓能朝任何方向，只有一端有，不做）；
 * - 箭头：两端（另一端不动，长度和方向跟着变）、中间（拉弯，曲线穿过这一点，照 Telegram iOS）。
 *
 * 坐标：形状自己的局部坐标、中心在原点、y 往下。和 iOS `TellomiShapeHandles` 同一套算法。
 */
object ShapeHandles {

  enum class Handle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TAIL, ARROW_START, ARROW_END, ARROW_BEND
  }

  data class Shape(
    val kind: ShapeRenderer.Kind,
    val width: Float,
    val aspectRatio: Float,
    val tailX: Float = ShapeRenderer.BUBBLE_TAIL_TIP,
    val bodyFraction: Float = ShapeRenderer.BUBBLE_BODY_HEIGHT,
    val bend: Float = 0f
  ) {
    val height: Float get() = width * aspectRatio
  }

  /** [centerOffset]：新中心相对旧中心（局部坐标）；[rotation]：多转的弧度（只有拉箭头两端时有）。 */
  data class Result(val shape: Shape, val centerOffset: PointF, val rotation: Float)

  fun handles(kind: ShapeRenderer.Kind): List<Handle> {
    return when (kind) {
      ShapeRenderer.Kind.RECTANGLE, ShapeRenderer.Kind.CIRCLE, ShapeRenderer.Kind.STAR -> listOf(Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT)
      ShapeRenderer.Kind.BUBBLE -> listOf(Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT, Handle.TAIL)
      ShapeRenderer.Kind.ARROW -> listOf(Handle.ARROW_START, Handle.ARROW_END, Handle.ARROW_BEND)
    }
  }

  /** 拉柄的位置。气泡的下面两个角是身子的角，不是框的角。 */
  fun position(handle: Handle, shape: Shape): PointF {
    val halfWidth = shape.width / 2f
    val top = -shape.height / 2f
    val bottom = if (shape.kind == ShapeRenderer.Kind.BUBBLE) top + shape.height * shape.bodyFraction else shape.height / 2f
    return when (handle) {
      Handle.TOP_LEFT -> PointF(-halfWidth, top)
      Handle.TOP_RIGHT -> PointF(halfWidth, top)
      Handle.BOTTOM_LEFT -> PointF(-halfWidth, bottom)
      Handle.BOTTOM_RIGHT -> PointF(halfWidth, bottom)
      Handle.TAIL -> PointF(-halfWidth + shape.width * shape.tailX, shape.height / 2f)
      Handle.ARROW_START -> PointF(-halfWidth, 0f)
      Handle.ARROW_END -> PointF(halfWidth, 0f)
      Handle.ARROW_BEND -> PointF(0f, shape.bend * shape.width)
    }
  }

  /** 把 [handle] 拉到 [point]（局部坐标，按拉之前的形状算）之后的形状；宽高不小于 [minimum]。 */
  fun drag(handle: Handle, point: PointF, shape: Shape, minimum: Float): Result {
    return when (handle) {
      Handle.TOP_LEFT, Handle.TOP_RIGHT, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> {
        val corner = position(handle, shape)
        val opposite = position(opposite(handle), shape)
        val signX = if (corner.x >= opposite.x) 1f else -1f
        val signY = if (corner.y >= opposite.y) 1f else -1f
        var width = max(minimum, (point.x - opposite.x) * signX)
        var height = max(minimum, (point.y - opposite.y) * signY)
        if (shape.kind.keepsSquare) {
          width = max(width, height)
          height = width
        }
        val movedX = opposite.x + signX * width
        val movedY = opposite.y + signY * height
        val bodyCenter = PointF((movedX + opposite.x) / 2f, (movedY + opposite.y) / 2f)
        if (shape.kind == ShapeRenderer.Kind.BUBBLE) {
          // 身子变了，尾巴长度不变；框的中心在身子中心下面半个尾巴。
          val tail = shape.height * (1f - shape.bodyFraction)
          val total = height + tail
          Result(shape.copy(width = width, aspectRatio = total / width, bodyFraction = height / total), PointF(bodyCenter.x, bodyCenter.y + tail / 2f), 0f)
        } else {
          Result(shape.copy(width = width, aspectRatio = height / width), bodyCenter, 0f)
        }
      }

      Handle.TAIL -> {
        val body = shape.height * shape.bodyFraction
        val top = -shape.height / 2f
        val tail = min(max(point.y - (top + body), body * 0.1f), body * 1.5f)
        val total = body + tail
        val tailX = min(max((point.x + shape.width / 2f) / shape.width, 0.05f), 0.95f)
        // 上边不动。
        Result(shape.copy(tailX = tailX, aspectRatio = total / shape.width, bodyFraction = body / total), PointF(0f, (total - shape.height) / 2f), 0f)
      }

      Handle.ARROW_START, Handle.ARROW_END -> {
        val fixed = position(if (handle == Handle.ARROW_END) Handle.ARROW_START else Handle.ARROW_END, shape)
        val vx = if (handle == Handle.ARROW_END) point.x - fixed.x else fixed.x - point.x
        val vy = if (handle == Handle.ARROW_END) point.y - fixed.y else fixed.y - point.y
        val length = max(minimum, hypot(vx, vy))
        val angle = atan2(vy, vx)
        val dx = cos(angle)
        val dy = sin(angle)
        val startX = if (handle == Handle.ARROW_END) fixed.x else fixed.x - dx * length
        val startY = if (handle == Handle.ARROW_END) fixed.y else fixed.y - dy * length
        Result(
          shape.copy(width = length, aspectRatio = ShapeRenderer.arrowAspect(shape.bend)),
          PointF(startX + dx * length / 2f, startY + dy * length / 2f),
          angle
        )
      }

      Handle.ARROW_BEND -> {
        val bend = min(max(point.y / max(shape.width, 0.0001f), -0.5f), 0.5f)
        Result(shape.copy(bend = bend, aspectRatio = ShapeRenderer.arrowAspect(bend)), PointF(0f, 0f), 0f)
      }
    }
  }

  /** 离 [point] 最近、在 [tolerance] 以内的拉柄。 */
  fun handleAt(point: PointF, shape: Shape, tolerance: Float): Handle? {
    return handles(shape.kind)
      .map { it to position(it, shape).let { p -> hypot(p.x - point.x, p.y - point.y) } }
      .filter { it.second <= tolerance }
      .minByOrNull { it.second }
      ?.first
  }

  private fun opposite(handle: Handle): Handle {
    return when (handle) {
      Handle.TOP_LEFT -> Handle.BOTTOM_RIGHT
      Handle.TOP_RIGHT -> Handle.BOTTOM_LEFT
      Handle.BOTTOM_LEFT -> Handle.TOP_RIGHT
      Handle.BOTTOM_RIGHT -> Handle.TOP_LEFT
      else -> handle
    }
  }
}
