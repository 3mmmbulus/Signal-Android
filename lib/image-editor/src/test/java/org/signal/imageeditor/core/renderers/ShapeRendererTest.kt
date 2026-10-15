/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.Bounds
import org.signal.imageeditor.core.Renderer
import org.signal.imageeditor.core.RendererContext
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tellomi（tellomi/tellomi#1522 E-3）：画笔菜单里的形状——轮廓、存取、画出来的样子（和 iOS 用同一套比例）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapeRendererTest {

  @Test
  fun `outlined shapes keep their line inside the box and filled ones fill it`() {
    val box = RectF(-1000f, -750f, 1000f, 750f)
    val thickness = 60f
    for (kind in ShapeRenderer.Kind.entries) {
      for (filled in listOf(false, true)) {
        val bounds = RectF()
        ShapeRenderer.path(kind, box, thickness, filled).computeBounds(bounds, true)
        val outset = if (filled || kind == ShapeRenderer.Kind.ARROW) 0f else thickness / 2f
        assertFalse("$kind", bounds.isEmpty)
        assertTrue("$kind filled=$filled", bounds.left - outset >= box.left - 0.01f)
        assertTrue("$kind filled=$filled", bounds.top - outset >= box.top - 0.01f)
        assertTrue("$kind filled=$filled", bounds.right + outset <= box.right + 0.01f)
        assertTrue("$kind filled=$filled", bounds.bottom + outset <= box.bottom + 0.01f)
      }
    }
  }

  @Test
  fun `the star has five tips pointing up first with a 0_4 inner radius and the circle is round`() {
    val star = ShapeRenderer.path(ShapeRenderer.Kind.STAR, RectF(0f, 0f, 100f, 100f), 0f, true)
    assertTrue(star.containsPoint(50f, 50f))
    assertTrue("top tip", star.containsPoint(50f, 3f))
    val between = -PI / 2 + PI / 5
    assertFalse("between two tips", star.containsPoint(50f + 22f * cos(between).toFloat(), 50f + 22f * sin(between).toFloat()))
    assertTrue("inner corner", star.containsPoint(50f + 18f * cos(between).toFloat(), 50f + 18f * sin(between).toFloat()))

    val circle = RectF()
    ShapeRenderer.path(ShapeRenderer.Kind.CIRCLE, RectF(0f, 0f, 100f, 100f), 10f, true).computeBounds(circle, true)
    assertEquals(circle.width(), circle.height(), 0.01f)
  }

  @Test
  fun `the bubble tail is on the bottom left and the arrow tip is on the right in the middle`() {
    val box = RectF(0f, 0f, 200f, 140f)
    val bubble = ShapeRenderer.path(ShapeRenderer.Kind.BUBBLE, box, 10f, true)
    val bounds = RectF()
    bubble.computeBounds(bounds, true)
    assertEquals(140f, bounds.bottom, 0.01f)
    assertTrue("the tail is on the bottom left", bubble.containsPoint(40f, 126f))
    assertFalse("nothing on the bottom right", bubble.containsPoint(160f, 126f))

    val arrow = ShapeRenderer.path(ShapeRenderer.Kind.ARROW, RectF(0f, 0f, 200f, 60f), 8f, false)
    arrow.computeBounds(bounds, true)
    assertEquals(200f, bounds.right, 0.01f)
    assertTrue(arrow.containsPoint(195f, 30f))
    assertTrue("the shaft", arrow.containsPoint(20f, 30f))
    assertFalse("the shaft is one line wide", arrow.containsPoint(20f, 40f))
  }

  @Test
  fun `the arrow is never filled and a shape comes back from the parcel`() {
    assertFalse(ShapeRenderer(ShapeRenderer.Kind.ARROW, Color.RED, true, 40f).isFilled)

    val shape = ShapeRenderer(ShapeRenderer.Kind.BUBBLE, Color.BLUE, true, 42f)
    shape.apply(ShapeHandles.Shape(ShapeRenderer.Kind.BUBBLE, 1234f, 0.9f, tailX = 0.7f, bodyFraction = 0.6f, bend = 0.1f))
    val parcel = Parcel.obtain()
    val back = try {
      shape.writeToParcel(parcel, 0)
      parcel.setDataPosition(0)
      ShapeRenderer.CREATOR.createFromParcel(parcel)
    } finally {
      parcel.recycle()
    }
    assertEquals(ShapeRenderer.Kind.BUBBLE, back.kind)
    assertEquals(Color.BLUE, back.color)
    assertTrue(back.isFilled)
    assertEquals(42f, back.thickness, 0f)
    assertEquals("the handles' edits come back too", shape.shape, back.shape)
  }

  @Test
  fun `a bent arrow passes through its bend point and a moved tail tip is drawn where it was moved`() {
    val root = EditorElement(PhotoRenderer(Color.GRAY))
    // 弯过的红箭头：宽 2000 缩一半 = 1000，放在 (0, -500)；中间往下弯 0.2 × 1000 = 200。
    val arrow = ShapeRenderer(ShapeRenderer.Kind.ARROW, Color.RED, false, 120f)
    arrow.apply(ShapeHandles.Shape(ShapeRenderer.Kind.ARROW, 2000f, ShapeRenderer.arrowAspect(0.2f), bend = 0.2f))
    root.addElement(
      EditorElement(arrow, EditorModel.Z_STICKERS).apply {
        localMatrix.setScale(0.5f, 0.5f)
        localMatrix.postTranslate(0f, -500f)
      }
    )
    // 实心蓝气泡，尾巴尖挪到右边 0.85：宽 1000、高 700，放在 (0, 500)。
    val bubble = ShapeRenderer(ShapeRenderer.Kind.BUBBLE, Color.BLUE, true, 40f)
    bubble.apply(ShapeHandles.Shape(ShapeRenderer.Kind.BUBBLE, 2000f, 0.7f, tailX = 0.85f))
    root.addElement(
      EditorElement(bubble, EditorModel.Z_STICKERS).apply {
        localMatrix.setScale(0.5f, 0.5f)
        localMatrix.postTranslate(0f, 500f)
      }
    )

    val bitmap = render(root)

    assertEquals("the bent arrow passes through its bend point", Color.RED, bitmap.getPixel(at(0f), at(-300f)))
    assertEquals("and not through the straight line", Color.GRAY, bitmap.getPixel(at(0f), at(-500f)))
    // 气泡框 y 150…850，尖在 x = -500 + 0.85 × 1000 = 350、框底往上 30 的地方还在尾巴里。
    assertEquals("the tail tip moved to the right", Color.BLUE, bitmap.getPixel(at(350f), at(820f)))
    assertEquals("nothing left at the old tip", Color.GRAY, bitmap.getPixel(at(-380f), at(820f)))
  }

  @Test
  fun `a filled rectangle covers its middle, an outlined circle is hollow, and the eraser leaves shapes alone`() {
    val root = EditorElement(PhotoRenderer(Color.GRAY))
    // 填充的红矩形，缩到四分之一，放在左上（-500, -500）。
    root.addElement(
      EditorElement(ShapeRenderer(ShapeRenderer.Kind.RECTANGLE, Color.RED, true, 40f), EditorModel.Z_STICKERS).apply {
        localMatrix.setScale(0.25f, 0.25f)
        localMatrix.postTranslate(-500f, -500f)
      }
    )
    // 描边的蓝圆，缩到一半，放在下面（0, 500）：直径 1000，线宽 80 × 0.5 = 40。
    root.addElement(
      EditorElement(ShapeRenderer(ShapeRenderer.Kind.CIRCLE, Color.BLUE, false, 80f), EditorModel.Z_STICKERS).apply {
        localMatrix.setScale(0.5f, 0.5f)
        localMatrix.postTranslate(0f, 500f)
      }
    )
    // 一道橡皮横着擦过红矩形。
    val eraser = BezierDrawingRenderer(Color.RED, 200f, Paint.Cap.ROUND, null, BezierDrawingRenderer.Style.ERASER)
    eraser.setFirstPoint(PointF(-1000f, -500f))
    eraser.addNewPoint(PointF(0f, -500f))
    eraser.addNewPoint(PointF(1000f, -500f))
    root.addElement(EditorElement(eraser, EditorModel.Z_DRAWING))

    val bitmap = render(root)

    assertEquals("the filled rectangle, under the eraser", Color.RED, bitmap.getPixel(at(-500f), at(-500f)))
    assertEquals("the outlined circle is hollow", Color.GRAY, bitmap.getPixel(at(0f), at(500f)))
    // 圆的线在半径 (1000 − 80 / 2) / 2 × … ：局部里半径 = (2000 − 80) / 2 = 960，缩一半 = 480。
    assertEquals("the circle's outline", Color.BLUE, bitmap.getPixel(at(480f), at(500f)))
  }

  private fun at(unit: Float): Int = ((unit - Bounds.LEFT) / (Bounds.RIGHT - Bounds.LEFT) * SIZE).toInt().coerceIn(0, SIZE - 1)

  private fun Path.containsPoint(x: Float, y: Float): Boolean {
    val bounds = RectF()
    computeBounds(bounds, true)
    val region = Region()
    region.setPath(this, Region(bounds.left.toInt() - 1, bounds.top.toInt() - 1, bounds.right.toInt() + 1, bounds.bottom.toInt() + 1))
    return region.contains(x.toInt(), y.toInt())
  }

  private fun render(root: EditorElement): Bitmap {
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val context = RendererContext(
      ApplicationProvider.getApplicationContext(),
      Canvas(bitmap),
      RendererContext.Ready.NULL,
      RendererContext.Invalidate.NULL
    ) { _, _, _ -> Typeface.DEFAULT }
    val matrix = Matrix()
    matrix.setRectToRect(Bounds.FULL_BOUNDS, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), Matrix.ScaleToFit.FILL)
    context.canvasMatrix.initial(matrix)
    root.draw(context)
    return bitmap
  }

  /** 照片：整片一个颜色。 */
  private class PhotoRenderer(private val color: Int) : Renderer {
    override fun render(rendererContext: RendererContext) {
      rendererContext.canvas.drawRect(Bounds.FULL_BOUNDS, Paint().apply { color = this@PhotoRenderer.color })
    }

    override fun hitTest(x: Float, y: Float): Boolean = false
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
  }

  private companion object {
    const val SIZE = 400
  }
}
