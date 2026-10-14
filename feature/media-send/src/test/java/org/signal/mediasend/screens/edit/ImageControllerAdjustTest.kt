/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.model.ImageAdjustments
import org.signal.imageeditor.core.renderers.UriGlideRenderer
import org.signal.mediasend.screens.edit.image.AVAILABLE_TOOLS
import org.signal.mediasend.screens.edit.image.adjustmentValueText

/**
 * Tellomi（tellomi/tellomi#1593 E5-3）：调色页——进出、拖动 / 完成 / 取消和撤销、按住看原图、返回键。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// The editor hierarchy builds an inverse-fill Path for the crop mask, which the legacy graphics shadows cannot do.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageControllerAdjustTest {

  private val mainImageRenderer = UriGlideRenderer(Uri.parse("content://tellomi.test/photo"), true, 1000, 1000)
  private val model = EditorModel.create(0).apply {
    addElement(EditorElement(mainImageRenderer))
    setVisibleViewPort(RectF(-1f, -0.75f, 1f, 0.75f))
    onReady(mainImageRenderer, Matrix().apply { preScale(IMAGE_SIZE.x / IMAGE_SIZE.y.toFloat(), 1f) }, IMAGE_SIZE)
  }
  private val controller = ImageController(model)

  @Test
  fun `the panel lists the rendered tools in Telegram Android order and values read like Telegram`() {
    assertEquals(
      listOf(
        ImageAdjustments.Tool.EXPOSURE,
        ImageAdjustments.Tool.CONTRAST,
        ImageAdjustments.Tool.SATURATION,
        ImageAdjustments.Tool.WARMTH,
        ImageAdjustments.Tool.FADE,
        ImageAdjustments.Tool.HIGHLIGHTS,
        ImageAdjustments.Tool.SHADOWS
      ),
      AVAILABLE_TOOLS
    )
    assertEquals("+35", adjustmentValueText(35f))
    assertEquals("-35", adjustmentValueText(-35f))
    assertEquals("0", adjustmentValueText(0f))
  }

  @Test
  fun `dragging changes the preview without undo, and done is one undo step`() {
    controller.beginAdjustEdit()
    assertTrue(controller.isUserAdjusting)
    assertTrue(controller.isUserInEdit)

    controller.setAdjustment(ImageAdjustments.Tool.EXPOSURE, 20f)
    controller.setAdjustment(ImageAdjustments.Tool.EXPOSURE, 40f)
    controller.setAdjustment(ImageAdjustments.Tool.WARMTH, -25f)
    assertEquals(40f, model.adjustments.exposure, 0f)
    assertEquals(40f, controller.adjustments.exposure, 0f)

    controller.commitEdit()

    assertFalse(controller.isUserAdjusting)
    assertEquals(ImageAdjustments(exposure = 40f, warmth = -25f), model.adjustments)
    assertTrue("调过色算改过", model.isChanged)
    model.undo()
    assertTrue("一步撤销回到打开前", model.adjustments.isDefault)
    assertFalse(model.isChanged)
  }

  @Test
  fun `cancel restores the adjustments from before without a dialog`() {
    model.setAdjustments(ImageAdjustments(contrast = 30f), true)
    controller.beginAdjustEdit()
    assertEquals(30f, controller.adjustments.contrast, 0f)

    controller.setAdjustment(ImageAdjustments.Tool.CONTRAST, -60f)
    controller.setAdjustment(ImageAdjustments.Tool.SATURATION, 50f)
    controller.requestCancelEdit()

    assertFalse("照 Telegram 安卓：取消直接回去，不问", controller.showDiscardDialog)
    assertFalse(controller.isUserAdjusting)
    assertEquals(ImageAdjustments(contrast = 30f), model.adjustments)
    model.undo()
    assertTrue("撤销栈里只有打开前那一步", model.adjustments.isDefault)
  }

  @Test
  fun `tint swatches change the preview and done is one undo step`() {
    controller.beginAdjustEdit()
    val red = ImageAdjustments.TINT_SHADOWS_PALETTE[1]
    val blue = ImageAdjustments.TINT_HIGHLIGHTS_PALETTE[6]

    controller.setTint(shadows = true, color = red)
    controller.setTint(shadows = false, color = blue)
    assertEquals(red, model.adjustments.tintShadowsColor)
    assertEquals(blue, model.adjustments.tintHighlightsColor)

    controller.commitEdit()
    model.undo()
    assertTrue(model.adjustments.isDefault)
  }

  @Test
  fun `back leaves the panel like cancel`() {
    controller.beginAdjustEdit()
    controller.setAdjustment(ImageAdjustments.Tool.EXPOSURE, 50f)

    runBlocking { controller.onBackPressed() }

    assertFalse(controller.isUserAdjusting)
    assertTrue(model.adjustments.isDefault)
  }

  @Test
  fun `holding shows the original without touching the model, and leaving the panel lets go`() {
    controller.beginAdjustEdit()
    controller.setAdjustment(ImageAdjustments.Tool.EXPOSURE, 50f)

    controller.setShowsOriginal(true)
    assertTrue(controller.isShowingOriginal)
    assertTrue(mainImageRenderer.isShowingOriginal)
    assertEquals(50f, model.adjustments.exposure, 0f)

    controller.commitEdit()
    assertFalse(controller.isShowingOriginal)
    assertFalse("离开调色页就不再显示原图", (model.mainImage!!.renderer as UriGlideRenderer).isShowingOriginal)
  }

  private companion object {
    val IMAGE_SIZE = Point(2252, 4000)
  }
}
