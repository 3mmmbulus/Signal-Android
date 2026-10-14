/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import android.app.Application
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.renderers.BezierDrawingRenderer
import org.signal.mediasend.screens.edit.ImageController
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 两端的画笔）：画笔菜单里的笔、箭头、荧光笔、霓虹、橡皮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawBrushTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `the brushes come in Telegram's order`() {
    assertEquals(
      listOf(DrawBrush.PEN, DrawBrush.ARROW, DrawBrush.HIGHLIGHTER, DrawBrush.NEON, DrawBrush.ERASER),
      DrawBrush.entries.toList()
    )
  }

  @Test
  fun `Given the draw tool, when a brush is picked, then the brush paints with its style, width and round ends`() {
    val controller = ImageController(EditorModel.create(0))
    controller.enterDrawMode()
    assertEquals(DrawBrush.PEN, controller.currentBrush)
    assertEquals(BezierDrawingRenderer.Style.NORMAL, controller.imageEditorState.drawStyle)

    for (brush in listOf(DrawBrush.ARROW, DrawBrush.NEON, DrawBrush.ERASER)) {
      controller.selectBrush(brush)

      assertEquals(ImageController.Mode.DRAW, controller.mode)
      assertEquals(brush, controller.currentBrush)
      assertEquals(brush.tool, controller.brushTool)
      assertEquals(brush.style, controller.imageEditorState.drawStyle)
      assertEquals(Paint.Cap.ROUND, controller.imageEditorState.drawCap)
      assertEquals(brush.tool.thicknessAt(controller.brushWidthFraction), controller.imageEditorState.drawThickness, 0f)
    }
  }

  @Test
  fun `Given another brush, when the highlighter is picked, then upstream's highlight mode takes over`() {
    val controller = ImageController(EditorModel.create(0))
    controller.selectBrush(DrawBrush.NEON)

    controller.selectBrush(DrawBrush.HIGHLIGHTER)

    assertEquals(ImageController.Mode.HIGHLIGHT, controller.mode)
    assertEquals(DrawBrush.HIGHLIGHTER, controller.currentBrush)
    assertEquals(BezierDrawingRenderer.Style.NORMAL, controller.imageEditorState.drawStyle)
    assertEquals(Paint.Cap.SQUARE, controller.imageEditorState.drawCap)

    // Back to the draw tool keeps the brush that was picked before.
    controller.selectBrush(DrawBrush.PEN)
    assertEquals(BezierDrawingRenderer.Style.NORMAL, controller.imageEditorState.drawStyle)
    assertEquals(DrawBrush.PEN, controller.currentBrush)
  }

  @Test
  fun `each brush keeps its own color and starts from Telegram's default`() {
    val controller = ImageController(EditorModel.create(0))
    controller.enterDrawMode()
    val penColor = controller.drawColorBarState.color

    controller.selectBrush(DrawBrush.ARROW)
    assertEquals(0xFFFF8A00.toInt(), controller.drawColorBarState.color)
    assertEquals(0xFFFF8A00.toInt(), controller.imageEditorState.drawColor)

    controller.drawColorBarState.show(0xFF3366FF.toInt())
    controller.setDrawColor(controller.drawColorBarState.color)

    controller.selectBrush(DrawBrush.NEON)
    assertEquals(0xFF34C759.toInt(), controller.drawColorBarState.color)

    controller.selectBrush(DrawBrush.HIGHLIGHTER)
    assertEquals(0xFFFFD60A.toInt(), controller.drawColorBarState.color)

    // The eraser has no color: the bar stays as it was.
    controller.selectBrush(DrawBrush.ERASER)
    assertEquals(0xFFFFD60A.toInt(), controller.drawColorBarState.color)

    controller.selectBrush(DrawBrush.ARROW)
    assertEquals(0xFF3366FF.toInt(), controller.drawColorBarState.color)

    controller.selectBrush(DrawBrush.PEN)
    assertEquals(penColor, controller.drawColorBarState.color)
  }

  @Test
  fun `each new brush keeps its own width`() {
    val widths = BrushWidths()
    assertEquals(0f, widths[BrushTool.ARROW], 0f)
    assertEquals(BrushWidths.DEFAULT_NEON, widths[BrushTool.NEON], 0f)
    assertEquals(BrushWidths.DEFAULT_ERASER, widths[BrushTool.ERASER], 0f)

    val changed = widths.with(BrushTool.NEON, 0.8f).with(BrushTool.ERASER, 0.1f).with(BrushTool.ARROW, 0.4f)
    assertEquals(0.8f, changed[BrushTool.NEON], 0f)
    assertEquals(0.1f, changed[BrushTool.ERASER], 0f)
    assertEquals(0.4f, changed[BrushTool.ARROW], 0f)
    assertEquals(widths.marker, changed.marker, 0f)
  }

  @Test
  fun `Given the brush menu, when neon is tapped, then the draw tool paints neon`() {
    val controller = ImageController(EditorModel.create(0))
    controller.enterDrawMode()
    composeTestRule.setContent {
      DrawModeColorBar(imageEditorController = controller)
    }

    composeTestRule.onNodeWithTag(TestTags.IMAGE_EDITOR_BRUSH_MENU).performClick()
    composeTestRule.onNodeWithTag(TestTags.imageEditorBrush(DrawBrush.ERASER)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TestTags.imageEditorBrush(DrawBrush.NEON)).performClick()
    composeTestRule.waitForIdle()

    assertEquals(DrawBrush.NEON, controller.currentBrush)
    assertEquals(BezierDrawingRenderer.Style.NEON, controller.imageEditorState.drawStyle)
  }
}
