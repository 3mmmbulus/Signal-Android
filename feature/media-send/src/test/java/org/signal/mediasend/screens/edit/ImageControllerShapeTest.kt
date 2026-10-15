/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.Bounds
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.renderers.ShapeRenderer
import org.signal.imageeditor.core.renderers.UriGlideRenderer
import org.signal.mediasend.screens.edit.image.BrushTool
import org.signal.mediasend.screens.edit.image.DrawBrush
import org.signal.mediasend.screens.edit.image.SHAPE_MENU_ORDER

/**
 * Tellomi（tellomi/tellomi#1522 E-3）：画笔菜单里加形状——次序、颜色 / 线宽 / 填充取哪里、选中的形状接手指、色条和粗细跟着它走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// The editor hierarchy builds an inverse-fill Path for the crop mask, which the legacy graphics shadows cannot do.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageControllerShapeTest {

  private val mainImageRenderer = UriGlideRenderer(Uri.parse("content://tellomi.test/photo"), true, 1000, 1000)
  private val model = EditorModel.create(0).apply {
    addElement(EditorElement(mainImageRenderer))
    setVisibleViewPort(RectF(-1f, -0.75f, 1f, 0.75f))
    onReady(mainImageRenderer, Matrix().apply { preScale(IMAGE_SIZE.x / IMAGE_SIZE.y.toFloat(), 1f) }, IMAGE_SIZE)
  }
  private val controller = ImageController(model)

  @Test
  fun `the shapes page follows Telegram Android's order`() {
    assertEquals(
      listOf(ShapeRenderer.Kind.CIRCLE, ShapeRenderer.Kind.RECTANGLE, ShapeRenderer.Kind.STAR, ShapeRenderer.Kind.BUBBLE, ShapeRenderer.Kind.ARROW),
      SHAPE_MENU_ORDER
    )
  }

  @Test
  fun `a new shape takes the color bar's color and the pen's width, is outlined, selected and one undo step`() {
    // On screen the editor hooks the state to the model's undo callbacks; here the test does it.
    controller.imageEditorState.attach()
    controller.beginDrawEdit()
    controller.drawColorBarState.customColor = Color.RED

    controller.insertShape(ShapeRenderer.Kind.STAR)

    val shape = selectedShape()
    assertEquals(ShapeRenderer.Kind.STAR, shape.kind)
    assertEquals(Color.RED, shape.color)
    assertFalse("outlined by default", shape.isFilled)
    val expected = BrushTool.MARKER.thicknessAt(controller.brushWidthFraction) * (Bounds.RIGHT - Bounds.LEFT) / ImageController.SHAPE_SCALE
    assertEquals(expected, shape.thickness, 0.01f)
    assertFalse("the selected shape takes the touch, so the brush is not live", controller.imageEditorState.isDrawing)
    assertEquals(ImageController.Mode.DRAW, controller.mode)
    assertTrue(controller.imageEditorState.undoAvailable)

    controller.undo()
    assertFalse("adding the shape was the one step to undo", controller.imageEditorState.undoAvailable)
  }

  @Test
  fun `the fill switch fills new shapes but never the arrow`() {
    controller.beginDrawEdit()
    controller.fillsShapes = true

    controller.insertShape(ShapeRenderer.Kind.CIRCLE)
    assertTrue(selectedShape().isFilled)

    controller.insertShape(ShapeRenderer.Kind.ARROW)
    assertFalse(selectedShape().isFilled)
  }

  @Test
  fun `the color bar and the width slider change the selected shape, and picking a brush puts it down`() {
    controller.beginDrawEdit()
    controller.insertShape(ShapeRenderer.Kind.RECTANGLE)
    val shape = selectedShape()
    val before = shape.thickness

    controller.onDrawColorChanged(Color.BLUE)
    assertEquals(Color.BLUE, shape.color)

    controller.setBrushWidthFraction(1f)
    assertTrue(shape.thickness > before)

    controller.selectBrush(DrawBrush.NEON)
    assertNull(controller.selectedElement)
    assertTrue("with nothing selected the brush is live again", controller.imageEditorState.isDrawing)
    controller.onDrawColorChanged(Color.GREEN)
    assertEquals(Color.BLUE, shape.color)
  }

  @Test
  fun `the eyedropper colors the selected shape`() {
    controller.beginDrawEdit()
    controller.insertShape(ShapeRenderer.Kind.BUBBLE)

    controller.pickColor(0x8012AB34.toInt())

    assertEquals(0xFF12AB34.toInt(), selectedShape().color)
  }

  private fun selectedShape(): ShapeRenderer = controller.selectedElement!!.renderer as ShapeRenderer

  private companion object {
    val IMAGE_SIZE = Point(1000, 750)
  }
}
