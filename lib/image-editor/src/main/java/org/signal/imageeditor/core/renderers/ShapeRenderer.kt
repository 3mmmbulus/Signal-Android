/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.renderers

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import org.signal.imageeditor.core.Bounds
import org.signal.imageeditor.core.ColorableRenderer
import org.signal.imageeditor.core.RendererContext
import org.signal.imageeditor.core.SelectableRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
 * E-3b：选中时画拉柄（[ShapeHandles]），拉柄改宽高、气泡尾巴、箭头两端和弯曲——这些是每个形状自己的，所以框的宽 [width]、
 * 宽高比 [aspectRatio]、尾巴 [tailX] / [bodyFraction]、弯曲 [bend] 都存在这里（拉柄改它们，不缩放元素，线宽不变）。
 *
 * 局部坐标：宽 [width]（新形状是 [Bounds] 的宽 2000），高 = 宽 × [aspectRatio]，中心在原点；[thickness] 也是局部单位。
 */
class ShapeRenderer(
  val kind: Kind,
  @ColorInt private var shapeColor: Int,
  filled: Boolean,
  thickness: Float
) : ColorableRenderer, SelectableRenderer {

  enum class Kind(val aspectRatio: Float) {
    // 新形状的高 / 宽：矩形、气泡照 Telegram iOS 的新形状（0.75、0.7），圆、星形是方的；箭头是一条横线，这个高只是让它好点中。
    CIRCLE(1f),
    RECTANGLE(0.75f),
    STAR(1f),
    BUBBLE(0.7f),
    ARROW(0.3f);

    /** 箭头没有填充（照 Telegram 安卓：箭头的图标没有填充的那一版）。 */
    val canFill: Boolean get() = this != ARROW

    /** 圆、星形只能等比缩放（Telegram 安卓的圆只有一个半径柄，iOS 的星形锁宽高比）。 */
    val keepsSquare: Boolean get() = this == CIRCLE || this == STAR
  }

  val isFilled: Boolean = filled && kind.canFill

  var thickness: Float = thickness
    set(value) {
      field = max(1f, value)
    }

  var width: Float = Bounds.RIGHT - Bounds.LEFT
    private set
  var aspectRatio: Float = kind.aspectRatio
    private set
  var tailX: Float = BUBBLE_TAIL_TIP
    private set
  var bodyFraction: Float = BUBBLE_BODY_HEIGHT
    private set
  var bend: Float = 0f
    private set

  /** 选中了（画拉柄、拉柄附近也算按在它上面）。 */
  var isSelected: Boolean = false
    private set

  /** 上次画的时候一个屏幕像素合多少局部单位（拉柄画多大、按多近算按到）。 */
  private var localPerPixel: Float = 1f
  private var density: Float = 1f

  init {
    this.thickness = thickness
  }

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeJoin = Paint.Join.ROUND
  }
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val path = Path()

  override fun getColor(): Int = shapeColor

  override fun setColor(@ColorInt color: Int) {
    shapeColor = color
  }

  val shape: ShapeHandles.Shape
    get() = ShapeHandles.Shape(kind, width, aspectRatio, tailX, bodyFraction, bend)

  /** 拉柄拉完：换上新的框和参数（中心、转角由元素的矩阵管）。 */
  fun apply(shape: ShapeHandles.Shape) {
    width = shape.width
    aspectRatio = shape.aspectRatio
    tailX = shape.tailX
    bodyFraction = shape.bodyFraction
    bend = shape.bend
  }

  /** 形状的框（局部坐标）。 */
  fun box(out: RectF = RectF()): RectF {
    val halfWidth = width / 2f
    val halfHeight = halfWidth * aspectRatio
    out.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
    return out
  }

  override fun render(rendererContext: RendererContext) {
    val canvasMatrix = Matrix()
    rendererContext.canvasMatrix.copyTo(canvasMatrix)
    localPerPixel = 1f / canvasMatrix.mapRadius(1f).coerceAtLeast(0.0001f)
    density = rendererContext.context.resources.displayMetrics.density

    val box = box()
    path(kind, box, thickness, isFilled, path, tailX, bodyFraction, bend)
    paint.color = shapeColor
    paint.alpha = rendererContext.getAlpha(paint.alpha)
    if (isFilled || kind == Kind.ARROW) {
      paint.style = Paint.Style.FILL
    } else {
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = thickness
    }
    rendererContext.canvas.drawPath(path, paint)

    if (isSelected && rendererContext.isEditing) {
      drawHandles(rendererContext)
    }
  }

  /** 选中时的拉柄：白点灰边，屏幕上半径 [HANDLE_RADIUS_DP]，和缩放无关。 */
  private fun drawHandles(rendererContext: RendererContext) {
    val radius = HANDLE_RADIUS_DP * density * localPerPixel
    val shape = shape
    for (handle in ShapeHandles.handles(kind)) {
      val point = ShapeHandles.position(handle, shape)
      handlePaint.style = Paint.Style.FILL
      handlePaint.color = 0xFFFFFFFF.toInt()
      rendererContext.canvas.drawCircle(point.x, point.y, radius, handlePaint)
      handlePaint.style = Paint.Style.STROKE
      handlePaint.strokeWidth = density * localPerPixel
      handlePaint.color = 0x4D000000
      rendererContext.canvas.drawCircle(point.x, point.y, radius, handlePaint)
    }
  }

  /** 按到的拉柄（局部坐标；屏幕上 [HANDLE_TOUCH_DP] 以内）；没选中时没有。 */
  fun handleAt(x: Float, y: Float): ShapeHandles.Handle? {
    if (!isSelected) return null
    return ShapeHandles.handleAt(PointF(x, y), shape, HANDLE_TOUCH_DP * density * localPerPixel)
  }

  override fun hitTest(x: Float, y: Float): Boolean = box().contains(x, y) || handleAt(x, y) != null

  override fun onSelected(selected: Boolean) {
    isSelected = selected
  }

  override fun getSelectionBounds(bounds: RectF) {
    box(bounds)
  }

  override fun describeContents(): Int = 0

  override fun writeToParcel(dest: Parcel, flags: Int) {
    dest.writeInt(kind.ordinal)
    dest.writeInt(shapeColor)
    dest.writeInt(if (isFilled) 1 else 0)
    dest.writeFloat(thickness)
    dest.writeFloat(width)
    dest.writeFloat(aspectRatio)
    dest.writeFloat(tailX)
    dest.writeFloat(bodyFraction)
    dest.writeFloat(bend)
  }

  companion object {
    /** 星形内外半径之比（Telegram iOS 0.4、安卓约 0.43）。 */
    const val STAR_INNER_RATIO = 0.4f

    /** 气泡：身子占的高、尾巴尖的位置（按宽 / 高的比例，尾巴在左下，照 Telegram iOS 新气泡）；尾巴根宽 0.2、中点在尖右边 0.14。 */
    const val BUBBLE_BODY_HEIGHT = 0.78f
    const val BUBBLE_TAIL_TIP = 0.12f
    const val BUBBLE_TAIL_HALF_BASE = 0.1f
    const val BUBBLE_TAIL_BASE_OFFSET = 0.14f

    /** 拉柄在屏幕上的半径、按多近算按到（dp）。 */
    const val HANDLE_RADIUS_DP = 7f
    const val HANDLE_TOUCH_DP = 24f

    /** 箭头框的高 / 宽：要装下弯出去的那一段（[bend] 按长度算）和箭头。 */
    @JvmStatic
    fun arrowAspect(bend: Float): Float = max(0.3f, 2f * abs(bend) + 0.3f)

    /**
     * 五种形状的轮廓，画在 [box] 里。描边的往里收半个线宽，线不出框；箭头是一根杆（描边后的轮廓）加一个实心头，整个填充。
     * 两端用同一套比例。
     */
    @JvmStatic
    fun path(
      kind: Kind,
      box: RectF,
      thickness: Float,
      filled: Boolean,
      out: Path = Path(),
      tailX: Float = BUBBLE_TAIL_TIP,
      bodyFraction: Float = BUBBLE_BODY_HEIGHT,
      bend: Float = 0f
    ): Path {
      out.reset()
      if (kind == Kind.ARROW) {
        return arrow(box, thickness, bend, out)
      }
      val inset = if (filled) 0f else thickness / 2f
      val rect = RectF(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
      if (rect.width() <= 0f || rect.height() <= 0f) return out
      when (kind) {
        Kind.RECTANGLE -> out.addRect(rect, Path.Direction.CW)
        Kind.CIRCLE -> out.addCircle(rect.centerX(), rect.centerY(), min(rect.width(), rect.height()) / 2f, Path.Direction.CW)
        Kind.STAR -> star(rect, out)
        Kind.BUBBLE -> bubble(rect, box.height(), tailX, bodyFraction, out)
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

    private fun bubble(rect: RectF, frameHeight: Float, tailX: Float, bodyFraction: Float, out: Path) {
      val inset = (frameHeight - rect.height()) / 2f
      val left = rect.left
      val top = rect.top
      val right = rect.right
      // 尾巴尖贴着框底；身子按 bodyFraction 算（描边时身子底边、尖都往里收了 inset）。
      val bodyBottom = max(top + 1f, rect.top - inset + frameHeight * bodyFraction - inset)
      val radius = min(rect.width(), bodyBottom - top) * 0.3f
      val baseCenter = min(max(tailX + BUBBLE_TAIL_BASE_OFFSET, BUBBLE_TAIL_HALF_BASE + 0.02f), 1f - BUBBLE_TAIL_HALF_BASE - 0.02f)
      val baseLeft = left + rect.width() * (baseCenter - BUBBLE_TAIL_HALF_BASE)
      val baseRight = left + rect.width() * (baseCenter + BUBBLE_TAIL_HALF_BASE)
      val tipX = left + rect.width() * tailX
      val leftCorner = max(0f, min(radius, baseLeft - left))
      val rightCorner = max(0f, min(radius, right - baseRight))

      // 一笔画完（身子底边在尾巴根处断开、接上尾巴），描边时尾巴和身子之间没有线。
      out.moveTo(left + radius, top)
      out.lineTo(right - radius, top)
      out.arcTo(RectF(right - 2 * radius, top, right, top + 2 * radius), -90f, 90f)
      out.lineTo(right, bodyBottom - rightCorner)
      out.arcTo(RectF(right - 2 * rightCorner, bodyBottom - 2 * rightCorner, right, bodyBottom), 0f, 90f)
      out.lineTo(baseRight, bodyBottom)
      out.lineTo(tipX, rect.bottom)
      out.lineTo(baseLeft, bodyBottom)
      out.lineTo(left + leftCorner, bodyBottom)
      out.arcTo(RectF(left, bodyBottom - 2 * leftCorner, left + 2 * leftCorner, bodyBottom), 90f, 90f)
      out.lineTo(left, top + radius)
      out.arcTo(RectF(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f)
      out.close()
    }

    /**
     * 箭头：从左到右一根杆（二次曲线，在中间穿过 (0, bend·宽)）、右边一个实心三角头，头顺着曲线末端的方向。
     * 头长取 3 倍线宽和长度的 0.18 里大的，两边各 30°（照 Telegram 安卓的箭头形状；iOS 的头是 3 × 1.5 线宽，这里取大一点，细线时头也看得清）。
     */
    private fun arrow(box: RectF, thickness: Float, bend: Float, out: Path): Path {
      val width = box.width()
      val midY = box.centerY()
      val startX = box.left
      val endX = box.right
      // 曲线在 t = 0.5 穿过 (中点, midY + bend·宽)，所以控制点离直线 2·bend·宽。
      val controlX = box.centerX()
      val controlY = midY + 2f * bend * width
      val headLength = min(width * 0.6f, max(thickness * 3f, width * 0.18f))
      val headHalfWidth = min(width * 0.15f, max(headLength * tan(PI / 6).toFloat(), thickness))
      // 杆是整条曲线在头根处截下的前一段（de Casteljau），所以还穿过中间那一点；头从截点连到尖。
      // 曲线长按（控制折线 + 弦）/ 2 估。
      val curveLength = (hypot(controlX - startX, controlY - midY) + hypot(endX - controlX, midY - controlY) + (endX - startX)) / 2f
      val t = min(max(1f - headLength / max(curveLength, 0.0001f), 0.05f), 1f)
      val u = 1f - t
      val baseX = u * u * startX + 2f * u * t * controlX + t * t * endX
      val baseY = u * u * midY + 2f * u * t * controlY + t * t * midY
      var dx = endX - baseX
      var dy = midY - baseY
      val length = max(hypot(dx, dy), 0.0001f)
      dx /= length
      dy /= length

      val shaft = Path()
      shaft.moveTo(startX, midY)
      shaft.quadTo(startX + (controlX - startX) * t, midY + (controlY - midY) * t, baseX, baseY)
      val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
      }
      stroke.getFillPath(shaft, out)

      out.moveTo(endX, midY)
      out.lineTo(baseX - dy * headHalfWidth, baseY + dx * headHalfWidth)
      out.lineTo(baseX + dy * headHalfWidth, baseY - dx * headHalfWidth)
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
        return ShapeRenderer(kind, color, filled, thickness).apply {
          apply(ShapeHandles.Shape(kind, parcel.readFloat(), parcel.readFloat(), parcel.readFloat(), parcel.readFloat(), parcel.readFloat()))
        }
      }

      override fun newArray(size: Int): Array<ShapeRenderer?> = arrayOfNulls(size)
    }
  }
}
