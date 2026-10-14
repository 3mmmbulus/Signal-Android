/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.Renderer
import org.signal.imageeditor.core.RendererContext
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.mediasend.screens.edit.image.CropAspectRatio

/**
 * Tellomi（tellomi/tellomi#1522 E-1）：裁剪里选了比例，框变成那个比例并锁住（照 Telegram `CropView.setLockedAspectRatio`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// The editor hierarchy builds an inverse-fill Path for the crop mask, which the legacy graphics shadows cannot do.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageControllerCropAspectTest {

  private val mainImageRenderer = TestRenderer()
  private val model = EditorModel.create(0).apply {
    addElement(EditorElement(mainImageRenderer))
    setVisibleViewPort(RectF(-1f, -0.75f, 1f, 0.75f))
    onReady(mainImageRenderer, Matrix().apply { preScale(IMAGE_SIZE.x / IMAGE_SIZE.y.toFloat(), 1f) }, IMAGE_SIZE)
  }
  private val controller = ImageController(model)

  @Test
  fun `Given the crop tool on a portrait photo, when Square is picked, then the crop becomes square and locked`() {
    controller.enterCropMode()
    assertTrue("竖的照片", controller.isCropPortrait)

    controller.cropTo(CropAspectRatio.SQUARE)

    assertEquals(1f, model.cropAspectRatioOnScreen, 0.01f)
    assertTrue(controller.isCropAspectRatioLocked)
  }

  @Test
  fun `Given the crop tool on a portrait photo, when 3 to 2 is picked, then it crops to 2 by 3`() {
    controller.enterCropMode()

    controller.cropTo(CropAspectRatio.THREE_BY_TWO)

    assertEquals(2f / 3f, model.cropAspectRatioOnScreen, 0.01f)
  }

  private class TestRenderer : Renderer {
    override fun render(rendererContext: RendererContext) = Unit
    override fun hitTest(x: Float, y: Float): Boolean = false
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
  }

  private companion object {
    val IMAGE_SIZE = Point(2252, 4000)
  }
}
