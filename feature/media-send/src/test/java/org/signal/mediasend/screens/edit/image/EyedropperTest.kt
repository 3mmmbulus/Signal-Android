/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.renderers.MultiLineTextRenderer
import org.signal.mediasend.screens.edit.ImageController
import org.signal.mediasend.test.TestTags

/**
 * Tellomi（tellomi/tellomi#1522 E-2，照 Telegram 两端的吸管）：放大镜跟着手指，松手取手指下的颜色；取到的颜色给文字或画笔。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EyedropperTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  /** 左半边红、右半边蓝。 */
  private val snapshot: Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
    for (x in 0 until width) {
      for (y in 0 until height) {
        setPixel(x, y, if (x < 50) Color.RED else Color.BLUE)
      }
    }
  }

  @Test
  fun `colorAt reads the pixel at a fraction of the bitmap and clamps at the edges`() {
    assertEquals(Color.RED, Eyedropper.colorAt(snapshot, 0.1f, 0.5f))
    assertEquals(Color.BLUE, Eyedropper.colorAt(snapshot, 0.9f, 0.5f))
    assertEquals(Color.BLUE, Eyedropper.colorAt(snapshot, 1.5f, 2f))
    assertEquals(Color.RED, Eyedropper.colorAt(snapshot, -1f, -1f))
  }

  @Test
  fun `Given the eyedropper, when a finger is dragged and let go on the right, then the right color is picked`() {
    var picked: Int? = null
    composeTestRule.setContent {
      Box(Modifier.size(200.dp)) {
        EyedropperOverlay(snapshot = snapshot, onPicked = { picked = it }, onCancel = {}, modifier = Modifier.size(200.dp))
      }
    }

    composeTestRule.onNodeWithTag(TestTags.IMAGE_EDITOR_EYEDROPPER).performTouchInput {
      down(Offset(width * 0.2f, height * 0.5f))
      moveTo(Offset(width * 0.8f, height * 0.5f))
      up()
    }
    composeTestRule.waitForIdle()

    assertEquals(Color.BLUE, picked)
  }

  @Test
  fun `Given text being written, when a color is picked, then the text and the text color bar take it`() {
    val controller = ImageController(EditorModel.create(0))
    controller.enterTextMode()
    controller.startColorPicking()
    assertTrue(controller.isPickingColor)

    controller.pickColor(0xFF336699.toInt())

    val renderer = controller.textEditingElement?.renderer as MultiLineTextRenderer
    assertEquals(0xFF336699.toInt(), renderer.color)
    assertEquals(0xFF336699.toInt(), controller.textColorBarState.color)
    assertEquals(false, controller.isPickingColor)
  }

  @Test
  fun `Given the brush, when a color is picked, then the brush and the draw color bar take it`() {
    val controller = ImageController(EditorModel.create(0))
    controller.enterDrawMode()

    controller.pickColor(0xFF336699.toInt())

    assertEquals(0xFF336699.toInt(), controller.drawColorBarState.color)
    assertEquals(0xFF336699.toInt(), controller.imageEditorState.drawColor)
  }
}
