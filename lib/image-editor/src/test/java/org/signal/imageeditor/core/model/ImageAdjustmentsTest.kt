/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core.model

import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.signal.imageeditor.core.renderers.UriGlideRenderer
import kotlin.math.PI

/**
 * Tellomi（tellomi/tellomi#1591 E5-1）：调色参数的数据模型——默认值、范围、parcel，以及放进图片编辑器模型后的撤销 / 重做与「改过」。
 */
@RunWith(RobolectricTestRunner::class)
// 编辑器的层级要建裁剪遮罩的反向填充 Path，旧的图形影子做不了（同 EditorModelTest）。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAdjustmentsTest {

  // region Value type

  /** 默认值照 TG 安卓：曲线 5 点 0 / 25 / 50 / 75 / 100，模糊中心 (0.5, 0.5)、大小 0.35、过渡 0.15、角度 π/2，色调强度 50。 */
  @Test
  fun defaultsFollowTelegramAndroid() {
    val adjustments = ImageAdjustments.DEFAULT
    assertTrue(adjustments.isDefault)
    ImageAdjustments.Tool.entries.forEach { assertEquals(it.name, 0f, adjustments[it], 0f) }
    assertEquals(ImageAdjustments.NO_TINT, adjustments.tintShadowsColor)
    assertEquals(ImageAdjustments.NO_TINT, adjustments.tintHighlightsColor)
    with(adjustments.curves) {
      listOf(all, red, green, blue).forEach { assertEquals(listOf(0f, 25f, 50f, 75f, 100f), it.values) }
    }
    with(adjustments.blur) {
      assertEquals(ImageAdjustments.BlurType.OFF, type)
      assertEquals(0.5f, centerX, 0f)
      assertEquals(0.5f, centerY, 0f)
      assertEquals(0.35f, size, 0f)
      assertEquals(0.15f, falloff, 0f)
      assertEquals((PI / 2).toFloat(), angle, 0f)
    }
    assertEquals(50f, ImageAdjustments.TINT_INTENSITY, 0f)
  }

  /** 任何一项离开默认值都算调过；模糊关着时只挪中心、大小不算；−0 也算 0。 */
  @Test
  fun isDefault() {
    ImageAdjustments.Tool.entries.forEach { tool ->
      val adjusted = ImageAdjustments.DEFAULT.with(tool, 10f)
      assertFalse(tool.name, adjusted.isDefault)
      assertTrue("${tool.name} 调回 0", adjusted.with(tool, 0f).isDefault)
    }

    assertFalse(ImageAdjustments(tintShadowsColor = 0xFFFF0000.toInt()).isDefault)
    assertFalse(ImageAdjustments(tintHighlightsColor = 0xFF0000FF.toInt()).isDefault)
    assertFalse(ImageAdjustments(curves = ImageAdjustments.Curves(green = ImageAdjustments.CurvePoints(midtones = 60f))).isDefault)

    val movedButOff = ImageAdjustments(blur = ImageAdjustments.Blur(centerX = 0.3f, size = 0.5f))
    assertTrue("模糊关着，挪过的中心、大小不算", movedButOff.isDefault)
    assertFalse(movedButOff.copy(blur = movedButOff.blur.copy(type = ImageAdjustments.BlurType.LINEAR)).isDefault)

    assertTrue(ImageAdjustments(exposure = -0f).isDefault)
    assertTrue(ImageAdjustments(curves = ImageAdjustments.Curves(all = ImageAdjustments.CurvePoints(blacks = -0f))).isDefault)
  }

  /** 6 个双极（−100…100）、6 个单极（0…100）；写进去超范围的值夹到边上，NaN 当 0，−0 变 0。 */
  @Test
  fun rangesAndClamping() {
    assertEquals(
      listOf(
        ImageAdjustments.Tool.EXPOSURE,
        ImageAdjustments.Tool.CONTRAST,
        ImageAdjustments.Tool.SATURATION,
        ImageAdjustments.Tool.WARMTH,
        ImageAdjustments.Tool.HIGHLIGHTS,
        ImageAdjustments.Tool.SHADOWS
      ),
      ImageAdjustments.Tool.entries.filter { it.isBipolar }
    )
    assertEquals(12, ImageAdjustments.Tool.entries.size)

    val adjustments = ImageAdjustments.DEFAULT
    assertEquals(100f, adjustments.with(ImageAdjustments.Tool.EXPOSURE, 150f).exposure, 0f)
    assertEquals(-100f, adjustments.with(ImageAdjustments.Tool.SHADOWS, -150f).shadows, 0f)
    assertEquals(0f, adjustments.with(ImageAdjustments.Tool.FADE, -20f).fade, 0f)
    assertEquals(100f, adjustments.with(ImageAdjustments.Tool.GRAIN, 101f).grain, 0f)
    assertEquals(0f, adjustments.with(ImageAdjustments.Tool.GRAIN, Float.NaN).grain, 0f)
    assertEquals(-35f, adjustments.with(ImageAdjustments.Tool.CONTRAST, -35f)[ImageAdjustments.Tool.CONTRAST], 0f)
    assertEquals(ImageAdjustments.DEFAULT, adjustments.with(ImageAdjustments.Tool.CONTRAST, -0f))
  }

  /** 写进 parcel 再读出来，每个字段都原样回来（默认值和全调过的都试）。 */
  @Test
  fun parcelRoundTrip() {
    val adjusted = everythingAdjusted()
    assertFalse(adjusted.isDefault)

    listOf(adjusted, ImageAdjustments.DEFAULT).forEach {
      assertEquals(it, roundTrip(it, ImageAdjustments::class.java))
    }
  }

  /** 主图的 renderer 带着调色进 parcel（撤销栈、EditorState 存的都是这个）。 */
  @Test
  fun theMainImageRendererCarriesTheAdjustmentsThroughAParcel() {
    val renderer = UriGlideRenderer(Uri.parse("content://tellomi.test/photo"), true, 1000, 1000)
    renderer.setAdjustments(everythingAdjusted())

    val restored = roundTrip(renderer, UriGlideRenderer::class.java)

    assertEquals(everythingAdjusted(), restored.adjustments)
    assertEquals(ImageAdjustments.DEFAULT, roundTrip(UriGlideRenderer(Uri.parse("content://tellomi.test/photo"), true, 1000, 1000), UriGlideRenderer::class.java).adjustments)
  }

  // endregion

  // region Editor model

  /** 调色进撤销 / 重做；调过色算「改过」（发出时重新渲染），撤回到默认就不算。 */
  @Test
  fun adjustingIsAChangeThatUndoesAndRedoes() {
    val model = readyModel()
    assertFalse("不调色：发出时走原图，和现在一样", model.isChanged)
    assertTrue(model.adjustments.isDefault)

    val warmer = ImageAdjustments(warmth = 40f)
    model.setAdjustments(warmer, true)
    assertEquals(warmer, model.adjustments)
    assertTrue(model.isChanged)

    model.undo()
    assertTrue(model.adjustments.isDefault)
    assertFalse(model.isChanged)

    model.redo()
    assertEquals(warmer, model.adjustments)
    assertTrue(model.isChanged)
  }

  /** 拖一次滑杆（第一下记撤销，后面不记）只占一步撤销，撤回到拖之前。 */
  @Test
  fun oneDragIsOneUndoStep() {
    val model = readyModel()
    val availability = RecordingUndoListener()
    model.setUndoRedoStackListener(availability)

    listOf(10f, 20f, 30f).forEachIndexed { step, exposure ->
      model.setAdjustments(model.adjustments.with(ImageAdjustments.Tool.EXPOSURE, exposure), step == 0)
    }
    assertEquals(30f, model.adjustments.exposure, 0f)
    assertTrue(availability.canUndo)

    model.undo()
    assertTrue(model.adjustments.isDefault)
    assertFalse(availability.canUndo)
    assertTrue(availability.canRedo)
  }

  /** 换成一样的调色：不记撤销，也不算改过。 */
  @Test
  fun settingTheSameAdjustmentsDoesNothing() {
    val model = readyModel()
    val availability = RecordingUndoListener()
    model.setUndoRedoStackListener(availability)
    val callsBefore = availability.calls

    model.setAdjustments(ImageAdjustments.DEFAULT, true)

    assertEquals(callsBefore, availability.calls)
    assertFalse(availability.canUndo)
    assertFalse(model.isChanged)
  }

  /** 整个模型写进 parcel 再读出来（EditorState 就是这么存的），调色还在，也还算改过。 */
  @Test
  fun theModelParcelKeepsTheAdjustments() {
    val model = readyModel()
    model.setAdjustments(ImageAdjustments(vignette = 50f), true)

    val restored = roundTrip(model, EditorModel::class.java)

    assertEquals(ImageAdjustments(vignette = 50f), restored.adjustments)
    assertTrue(restored.isChanged)
  }

  /** 主图不是照片（没有 UriGlideRenderer）时：读到默认值，写不进去。 */
  @Test
  fun withoutAPhotoAsTheMainImageAdjustmentsStayDefault() {
    val model = EditorModel.create(0)
    assertSame(ImageAdjustments.DEFAULT, model.adjustments)
    val snapshotBefore = model.createSnapshot()

    model.setAdjustments(ImageAdjustments(contrast = 20f), true)

    assertSame(ImageAdjustments.DEFAULT, model.adjustments)
    assertTrue("元素树一点没动", snapshotBefore.contentEquals(model.createSnapshot()))
  }

  // endregion

  private fun readyModel(): EditorModel {
    val renderer = UriGlideRenderer(Uri.parse("content://tellomi.test/photo"), true, 1000, 1000)
    val model = EditorModel.create(0)
    model.addElement(EditorElement(renderer))
    model.setVisibleViewPort(RectF(-1f, -0.75f, 1f, 0.75f))
    // 同 EditorModelTest.ready：主图解码好之后 renderer 报上来的样子。
    model.onReady(renderer, Matrix().apply { preScale(IMAGE_SIZE.x / IMAGE_SIZE.y.toFloat(), 1f) }, IMAGE_SIZE)
    return model
  }

  private fun everythingAdjusted(): ImageAdjustments {
    var adjustments = ImageAdjustments.DEFAULT
    ImageAdjustments.Tool.entries.forEachIndexed { index, tool ->
      adjustments = adjustments.with(tool, (index + 1) * if (tool.isBipolar) -7f else 7f)
    }
    return adjustments.copy(
      tintShadowsColor = 0xFF102030.toInt(),
      tintHighlightsColor = 0xFFE0D0C0.toInt(),
      curves = ImageAdjustments.Curves(
        all = ImageAdjustments.CurvePoints(blacks = 5f),
        red = ImageAdjustments.CurvePoints(shadows = 30f),
        green = ImageAdjustments.CurvePoints(midtones = 45f),
        blue = ImageAdjustments.CurvePoints(highlights = 80f, whites = 95f)
      ),
      blur = ImageAdjustments.Blur(ImageAdjustments.BlurType.RADIAL, 0.25f, 0.75f, 0.4f, 0.2f, (PI / 3).toFloat())
    )
  }

  private fun <T : Parcelable> roundTrip(value: T, type: Class<T>): T {
    val parcel = Parcel.obtain()
    try {
      parcel.writeParcelable(value, 0)
      parcel.setDataPosition(0)
      @Suppress("DEPRECATION")
      val restored: T? = parcel.readParcelable(type.classLoader)
      assertNotNull(restored)
      return restored!!
    } finally {
      parcel.recycle()
    }
  }

  /** E5-7：改曲线的一个点夹在 0…100（NaN 当 0），按通道读写四条。 */
  @Test
  fun curvePointsAndChannels() {
    val points = ImageAdjustments.CurvePoints()
    assertEquals(50f, points[2], 0f)
    assertEquals(70f, points.with(2, 70f).midtones, 0f)
    assertEquals(100f, points.with(4, 130f).whites, 0f)
    assertEquals(0f, points.with(0, -5f).blacks, 0f)
    assertEquals(0f, points.with(1, Float.NaN).shadows, 0f)

    val curves = ImageAdjustments.Curves().with(ImageAdjustments.CurveChannel.GREEN, points.with(3, 60f))
    assertEquals(60f, curves[ImageAdjustments.CurveChannel.GREEN].highlights, 0f)
    assertTrue(curves[ImageAdjustments.CurveChannel.ALL].isDefault)
    assertFalse(curves.isDefault)
  }

  private class RecordingUndoListener : org.signal.imageeditor.core.UndoRedoStackListener {
    var canUndo = false
    var canRedo = false
    var calls = 0

    override fun onAvailabilityChanged(undoAvailable: Boolean, redoAvailable: Boolean) {
      canUndo = undoAvailable
      canRedo = redoAvailable
      calls++
    }
  }

  private companion object {
    val IMAGE_SIZE = Point(2252, 4000)
  }
}
