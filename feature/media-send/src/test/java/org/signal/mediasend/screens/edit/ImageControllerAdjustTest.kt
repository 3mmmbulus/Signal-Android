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
import androidx.compose.ui.geometry.Offset
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
import org.signal.mediasend.screens.edit.image.BlurControl
import org.signal.mediasend.screens.edit.image.BlurMapping
import org.signal.mediasend.screens.edit.image.adjustmentValueText
import org.signal.mediasend.screens.edit.image.blurControlAt
import org.signal.mediasend.screens.edit.image.blurDragged
import org.signal.mediasend.screens.edit.image.blurPinched
import org.signal.mediasend.screens.edit.image.curveSegment
import org.signal.mediasend.screens.edit.image.curveValueText

/**
 * Tellomi（tellomi/tellomi#1593 E5-3、#1597 E5-7）：调色页——进出、拖动 / 完成 / 取消和撤销、按住看原图、返回键、曲线页签。
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
        ImageAdjustments.Tool.ENHANCE,
        ImageAdjustments.Tool.EXPOSURE,
        ImageAdjustments.Tool.CONTRAST,
        ImageAdjustments.Tool.SATURATION,
        ImageAdjustments.Tool.WARMTH,
        ImageAdjustments.Tool.FADE,
        ImageAdjustments.Tool.HIGHLIGHTS,
        ImageAdjustments.Tool.SHADOWS,
        ImageAdjustments.Tool.VIGNETTE,
        ImageAdjustments.Tool.GRAIN,
        ImageAdjustments.Tool.SHARPEN
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

  /** E5-7：曲线页签——打开调色页在工具列表上；改的是当前那条曲线的那个点（夹在 0…100）；换通道；完成算一步撤销。 */
  @Test
  fun `curves move the current channel's point and done is one undo step`() {
    controller.beginAdjustEdit()
    assertEquals(ImageController.AdjustTab.TOOLS, controller.adjustTab)
    controller.selectAdjustTab(ImageController.AdjustTab.CURVES)
    assertEquals(ImageAdjustments.CurveChannel.ALL, controller.curveChannel)

    controller.moveCurvePoint(2, 20f)
    assertEquals(70f, model.adjustments.curves.all.midtones, 0f)
    controller.moveCurvePoint(4, 30f)
    assertEquals("夹在 0…100", 100f, model.adjustments.curves.all.whites, 0f)

    controller.selectCurveChannel(ImageAdjustments.CurveChannel.RED)
    controller.moveCurvePoint(0, 12f)
    assertEquals(12f, model.adjustments.curves.red.blacks, 0f)
    assertEquals("别的通道不动", 70f, model.adjustments.curves.all.midtones, 0f)
    assertEquals("0.12", curveValueText(model.adjustments.curves.red.blacks))

    controller.commitEdit()
    model.undo()
    assertTrue(model.adjustments.curves.isDefault)

    controller.beginAdjustEdit()
    assertEquals("再打开回到工具列表、全部", ImageController.AdjustTab.TOOLS, controller.adjustTab)
    assertEquals(ImageAdjustments.CurveChannel.ALL, controller.curveChannel)
    controller.cancelEdit()
  }

  /** E5-7：按在哪一段改哪个点；照片在画布上的位置跟着视图矩阵。 */
  @Test
  fun `curve segments split the photo into fifths`() {
    val rect = RectF(100f, 0f, 600f, 300f)
    assertEquals(0, curveSegment(100f, rect))
    assertEquals(2, curveSegment(350f, rect))
    assertEquals(4, curveSegment(599f, rect))
    assertEquals(4, curveSegment(800f, rect))
    assertEquals(0, curveSegment(0f, rect))

    val view = Matrix().apply { setScale(0.1f, 0.1f); postTranslate(200f, 300f) }
    val crop = model.findCropRectInView(view)
    val root = model.findCropRelativeToRoot()
    assertTrue("裁剪框在画布上：${crop.toShortString()} vs ${root.toShortString()}", crop.width() > 0f && crop.height() > 0f)
    assertEquals(crop.width() / crop.height(), root.width() / root.height(), 0.01f)
  }

  /** E5-8：模糊页签——选「径向 / 线性」、改模糊只改预览，完成算一步撤销。 */
  @Test
  fun `blur type and blur changes are one undo step`() {
    controller.beginAdjustEdit()
    controller.selectAdjustTab(ImageController.AdjustTab.BLUR)
    controller.selectBlurType(ImageAdjustments.BlurType.RADIAL)
    assertEquals(ImageAdjustments.BlurType.RADIAL, model.adjustments.blur.type)
    controller.setBlur(model.adjustments.blur.copy(centerX = 0.3f))
    assertEquals(0.3f, model.adjustments.blur.centerX, 0f)
    controller.selectBlurType(ImageAdjustments.BlurType.LINEAR)
    assertEquals("中心留着", 0.3f, model.adjustments.blur.centerX, 0f)

    controller.commitEdit()
    model.undo()
    assertEquals(ImageAdjustments.BlurType.OFF, model.adjustments.blur.type)
  }

  /** E5-8：模糊控件——按中心、内外圈、线性别处；拖中心、拖外圈、捏、转；画布上的位置用单位坐标 → view 的矩阵。 */
  @Test
  fun `blur controls hit test and move like Telegram`() {
    // 图 4:3，在画布上 (100, 50) 起、400 × 300：短边 300 像素。
    val mapping = BlurMapping(Matrix().apply { setScale(400f, 300f); postTranslate(100f, 50f) })
    assertEquals(300f, mapping.shortSideInView, 1e-3f)
    val radial = ImageAdjustments.Blur(type = ImageAdjustments.BlurType.RADIAL, falloff = 0.12f, size = 0.24f)
    val center = mapping.view(0.5f, 0.5f)
    assertEquals(Offset(300f, 200f), center)
    assertEquals(BlurControl.CENTER, blurControlAt(radial, mapping, center, 30f))
    assertEquals(BlurControl.INNER, blurControlAt(radial, mapping, center + Offset(36f, 0f), 30f))
    assertEquals(BlurControl.OUTER, blurControlAt(radial, mapping, center + Offset(0f, 72f), 30f))
    assertEquals("径向时别处不动", null, blurControlAt(radial, mapping, Offset(110f, 60f), 30f))

    val moved = blurDragged(BlurControl.CENTER, radial, center, center + Offset(40f, -30f), mapping)
    assertEquals(0.6f, moved.centerX, 1e-5f)
    assertEquals(0.4f, moved.centerY, 1e-5f)
    val bigger = blurDragged(BlurControl.OUTER, radial, center + Offset(72f, 0f), center + Offset(102f, 0f), mapping)
    assertEquals(0.34f, bigger.size, 1e-5f)
    val inner = blurDragged(BlurControl.INNER, radial, center + Offset(36f, 0f), center + Offset(200f, 0f), mapping)
    assertEquals("内圈不超过外圈", 0.22f, inner.falloff, 1e-5f)
    val pinched = blurPinched(radial, 0.5f)
    assertEquals(0.06f, pinched.falloff, 1e-5f)
    assertEquals(0.12f, pinched.size, 1e-5f)

    val linear = radial.copy(type = ImageAdjustments.BlurType.LINEAR)
    val below = center + Offset(0f, 150f)
    assertEquals(BlurControl.ROTATION, blurControlAt(linear, mapping, below, 30f))
    val turned = blurDragged(BlurControl.ROTATION, linear, below, center + Offset(150f, 0f), mapping)
    assertEquals("转了 −90°", linear.angle - (Math.PI / 2).toFloat(), turned.angle, 1e-4f)
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
