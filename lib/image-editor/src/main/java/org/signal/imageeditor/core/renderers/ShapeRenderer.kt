/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.renderers

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import org.signal.imageeditor.core.Bounds
import org.signal.imageeditor.core.ColorableRenderer
import org.signal.imageeditor.core.RendererContext
import org.signal.imageeditor.core.SelectableRenderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tellomi（tellomi/tellomi#1522 E-3）：画笔菜单「形状」加到图上的一个形状——圆、矩形、星形、气泡、箭头。
 *
 * 照两端 Telegram 都有的那部分（只读机制，代码自己写）：安卓 `PaintToolsView` 的「+」→ `Brush.Shape` → `ShapeInput`，
 * iOS `DrawingScreen.presentShapePicker` → `DrawingSimpleShapeEntity` / `DrawingBubbleEntity` / `DrawingVectorEntity`。
 * 五种形状、默认描边可选填充、放在图中间、颜色和粗细取当前的笔、能挪能缩放能转。放下之后和贴纸一样是一个能再选中的元素
 * （Signal 自己的贴纸、文字就是这样，D5），不像 Telegram 安卓那样画死在涂鸦层。
 *
 * 局部坐标：宽占满 [Bounds]（2000），高 = 宽 × [Kind.aspectRatio]，中心在原点；[thickness] 也是局部单位。
 */
class ShapeRenderer(
  val kind: Kind,
  @ColorInt private var shapeColor: Int,
  filled: Boolean,
  thickness: Float
) : ColorableRenderer, SelectableRenderer {

  enum class Kind(val aspectRatio: Float) {
    // 高 / 宽：矩形、气泡照 Telegram iOS 的新形状（0.75、0.7），圆、星形是方的；箭头是一条横线，这个高只是让它好点中。
    CIRCLE(1f),
    RECTANGLE(0.75f),
    STAR(1f),
    BUBBLE(0.7f),
    ARROW(0.3f);

    /** 箭头没有填充（照 Telegram 安卓：箭头的图标没有填充的那一版）。 */
    val canFill: Boolean get() = this != ARROW
  }

  val isFilled: Boolean = filled && kind.canFill

  var thickness: Float = thickness
    set(value) {
      field = max(1f, value)
    }

  init {
    this.thickness = thickness
  }

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeJoin = Paint.Join.ROUND
  }
  private val path = Path()

  override fun getColor(): Int = shapeColor

  override fun setColor(@ColorInt color: Int) {
    shapeColor = color
  }

  /** 形状的框（局部坐标）。 */
  fun box(out: RectF = RectF()): RectF {
    val halfWidth = (Bounds.RIGHT - Bounds.LEFT) / 2f
    val halfHeight = halfWidth * kind.aspectRatio
    out.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
    return out
  }

  override fun render(rendererContext: RendererContext) {
    val box = box()
    path(kind, box, thickness, isFilled, path)
    paint.color = shapeColor
    paint.alpha = rendererContext.getAlpha(paint.alpha)
    if (isFilled || kind == Kind.ARROW) {
      paint.style = Paint.Style.FILL
    } else {
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = thickness
    }
    rendererContext.canvas.drawPath(path, paint)
  }

  override fun hitTest(x: Float, y: Float): Boolean = box().contains(x, y)

  override fun onSelected(selected: Boolean) = Unit

  override fun getSelectionBounds(bounds: RectF) {
    box(bounds)
  }

  override fun describeContents(): Int = 0

  override fun writeToParcel(dest: Parcel, flags: Int) {
    dest.writeInt(kind.ordinal)
    dest.writeInt(shapeColor)
    dest.writeInt(if (isFilled) 1 else 0)
    dest.writeFloat(thickness)
  }

  companion object {
    /** 星形内外半径之比（Telegram iOS 0.4、安卓约 0.43）。 */
    const val STAR_INNER_RATIO = 0.4f

    /** 气泡：身子占的高、尾巴根在底边上的位置、尖的位置（按宽 / 高的比例，尾巴在左下，照 Telegram iOS 新气泡）。 */
    const val BUBBLE_BODY_HEIGHT = 0.78f
    const val BUBBLE_TAIL_START = 0.36f
    const val BUBBLE_TAIL_END = 0.16f
    const val BUBBLE_TAIL_TIP = 0.12f

    /**
     * 五种形状的轮廓，画在 [box] 里。描边的往里收半个线宽，线不出框；箭头整个是一块填充（杆 + 头）。两端用同一套比例。
     */
    @JvmStatic
    fun path(kind: Kind, box: RectF, thickness: Float, filled: Boolean, out: Path = Path()): Path {
      out.reset()
      if (kind == Kind.ARROW) {
        return arrow(box, thickness, out)
      }
      val inset = if (filled) 0f else thickness / 2f
      val rect = RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
      if (rect.width() <= 0f || rect.height() <= 0f) return out
      when (kind) {
        Kind.RECTANGLE -> out.addRect(rect, Path.Direction.CW)
        Kind.CIRCLE -> out.addCircle(rect.centerX(), rect.centerY(), min(rect.width(), rect.height()) / 2f, Path.Direction.CW)
        Kind.STAR -> star(rect, out)
        Kind.BUBBLE -> bubble(rect, out)
        Kind.ARROW -> Unit
      }
      return out
    }

    private fun star(rect: RectF, out: Path) {
      val outer = min(rect.width(), rect.height()) / 2f
      val inner = outer * STAR_INNER_RATIO
      for (index in 0 until 10) {
        val radius = if (index % 2 == 0) outer else inner
        // 第一个尖朝上。
        val angle = -PI / 2 + index * PI / 5
        val x = rect.centerX() + radius * cos(angle).toFloat()
        val y = rect.centerY() + radius * sin(angle).toFloat()
        if (index == 0) out.moveTo(x, y) else out.lineTo(x, y)
      }
      out.close()
    }

    private fun bubble(rect: RectF, out: Path) {
      val left = rect.left
      val top = rect.top
      val right = rect.right
      val bodyBottom = rect.top + rect.height() * BUBBLE_BODY_HEIGHT
      val radius = min(rect.width(), bodyBottom - top) * 0.3f
      val tailStart = left + rect.width() * BUBBLE_TAIL_START
      val tailEnd = left + rect.width() * BUBBLE_TAIL_END
      val tipX = left + rect.width() * BUBBLE_TAIL_TIP
      val corner = min(radius, tailEnd - left)

      // 一笔画完（左下角断开接上尾巴），描边时尾巴和身子之间没有线。
      out.moveTo(left + radius, top)
      out.lineTo(right - radius, top)
      out.arcTo(RectF(right - 2 * radius, top, right, top + 2 * radius), -90f, 90f)
      out.lineTo(right, bodyBottom - radius)
      out.arcTo(RectF(right - 2 * radius, bodyBottom - 2 * radius, right, bodyBottom), 0f, 90f)
      out.lineTo(tailStart, bodyBottom)
      out.lineTo(tipX, rect.bottom)
      out.lineTo(tailEnd, bodyBottom)
      out.lineTo(left + corner, bodyBottom)
      out.arcTo(RectF(left, bodyBottom - 2 * corner, left + 2 * corner, bodyBottom), 90f, 90f)
      out.lineTo(left, top + radius)
      out.arcTo(RectF(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f)
      out.close()
    }

    /**
     * 箭头：从左到右一根杆、右边一个实心三角头。头长取 3 倍线宽和宽的 0.18 里大的，两边各 30°（照 Telegram 安卓的箭头形状；
     * iOS 的头是 3 × 1.5 线宽，这里取大一点，细线时头也看得清）。
     */
    private fun arrow(box: RectF, thickness: Float, out: Path): Path {
      val midY = box.centerY()
      val width = box.width()
      val headLength = min(width * 0.6f, max(thickness * 3f, width * 0.18f))
      val headHalfWidth = min(box.height() / 2f, max(headLength * tan(PI / 6).toFloat(), thickness))
      val shaftHalf = min(thickness / 2f, headHalfWidth)
      val tipX = box.right
      val baseX = tipX - headLength
      out.moveTo(box.left, midY - shaftHalf)
      out.lineTo(baseX, midY - shaftHalf)
      out.lineTo(baseX, midY - headHalfWidth)
      out.lineTo(tipX, midY)
      out.lineTo(baseX, midY + headHalfWidth)
      out.lineTo(baseX, midY + shaftHalf)
      out.lineTo(box.left, midY + shaftHalf)
      out.close()
      return out
    }

    @JvmField
    val CREATOR: Parcelable.Creator<ShapeRenderer> = object : Parcelable.Creator<ShapeRenderer> {
      override fun createFromParcel(parcel: Parcel): ShapeRenderer {
        val kind = Kind.entries[parcel.readInt()]
        val color = parcel.readInt()
        val filled = parcel.readInt() == 1
        val thickness = parcel.readFloat()
        return ShapeRenderer(kind, color, filled, thickness)
      }

      override fun newArray(size: Int): Array<ShapeRenderer?> = arrayOfNulls(size)
    }
  }
}
