/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.renderers.MultiLineTextRenderer

/**
 * Tellomi（tellomi/tellomi#1522 E-4）：文字工具的对齐键和半透明底（照 Telegram 安卓 `PaintTextOptionsView` / TextPaintView 的类型）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// The editor hierarchy builds an inverse-fill Path for the crop mask, which the legacy graphics shadows cannot do.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageControllerTextAlignTest {

  private val controller = ImageController(EditorModel.create(0))

  @Test
  fun `Given new text, when the align button is tapped, then it goes center, right, left and the text follows`() {
    controller.enterTextMode()
    val renderer = controller.textEditingElement?.renderer as MultiLineTextRenderer
    assertEquals(MultiLineTextRenderer.Align.CENTER, controller.textAlign)

    controller.nextTextAlign()
    assertEquals(MultiLineTextRenderer.Align.RIGHT, controller.textAlign)
    assertEquals(MultiLineTextRenderer.Align.RIGHT, renderer.align)

    controller.nextTextAlign()
    assertEquals(MultiLineTextRenderer.Align.LEFT, controller.textAlign)

    controller.nextTextAlign()
    assertEquals(MultiLineTextRenderer.Align.CENTER, controller.textAlign)
  }

  @Test
  fun `Given new text, when the style button is tapped twice, then it has the semi-transparent frame`() {
    controller.enterTextMode()
    val renderer = controller.textEditingElement?.renderer as MultiLineTextRenderer

    controller.nextTextStyle()
    controller.nextTextStyle()

    assertEquals(MultiLineTextRenderer.Mode.SEMI, renderer.mode)
  }
}
