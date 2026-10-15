/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.model.EditorModel
import org.signal.imageeditor.core.renderers.ShapeHandles
import org.signal.imageeditor.core.renderers.ShapeHandles.Handle
import org.signal.imageeditor.core.renderers.ShapeRenderer
import kotlin.math.PI

/**
 * Tellomi（tellomi/tellomi#1522 E-3b）：选中形状的拉柄——算法（和 iOS `TellomiShapeHandles` 同一组数）、拉柄会话把挪动和转角并进元素。
 */
@RunWith(RobolectricTestRunner::class)
class ShapeHandleEditSessionTest {

  @Test
  fun `a corner resizes from the opposite corner and never flips past it`() {
    val shape = shape(ShapeRenderer.Kind.RECTANGLE, width = 0.5f, aspect = 0.75f)
    val result = ShapeHandles.drag(Handle.BOTTOM_RIGHT, PointF(0.35f, 0.2875f), shape, MINIMUM)
    assertEquals(0.6f, result.shape.width, EPSILON)
    assertEquals(0.475f, result.shape.height, EPSILON)
    assertEquals(0.05f, result.centerOffset.x, EPSILON)
    assertEquals(0.05f, result.centerOffset.y, EPSILON)
    assertEquals(0f, result.rotation, EPSILON)

    val tiny = ShapeHandles.drag(Handle.BOTTOM_RIGHT, PointF(-0.4f, -0.4f), shape, MINIMUM)
    assertEquals(MINIMUM, tiny.shape.width, EPSILON)
    assertEquals(MINIMUM, tiny.shape.height, EPSILON)
  }

  @Test
  fun `circles and stars stay square`() {
    for (kind in listOf(ShapeRenderer.Kind.CIRCLE, ShapeRenderer.Kind.STAR)) {
      val result = ShapeHandles.drag(Handle.TOP_LEFT, PointF(-0.45f, -0.3f), shape(kind, width = 0.5f, aspect = 1f), MINIMUM)
      assertEquals(0.7f, result.shape.width, EPSILON)
      assertEquals(1f, result.shape.aspectRatio, EPSILON)
    }
  }

  @Test
  fun `a bubble corner keeps the tail and the tail handle moves the tip with the top fixed`() {
    val shape = shape(ShapeRenderer.Kind.BUBBLE, width = 0.5f, aspect = 0.7f)
    val tail = shape.height * (1f - shape.bodyFraction)
    val bodyBottom = -shape.height / 2f + shape.height * shape.bodyFraction
    assertEquals(bodyBottom, ShapeHandles.position(Handle.BOTTOM_RIGHT, shape).y, EPSILON)

    val wider = ShapeHandles.drag(Handle.BOTTOM_RIGHT, PointF(0.35f, bodyBottom), shape, MINIMUM)
    assertEquals(0.6f, wider.shape.width, EPSILON)
    assertEquals("the tail keeps its length", tail, wider.shape.height * (1f - wider.shape.bodyFraction), EPSILON)

    val moved = ShapeHandles.drag(Handle.TAIL, PointF(0.15f, bodyBottom + tail * 2f), shape, MINIMUM)
    assertEquals(0.8f, moved.shape.tailX, EPSILON)
    assertEquals(tail * 2f, moved.shape.height * (1f - moved.shape.bodyFraction), EPSILON)
    assertEquals("the body stays", shape.height * shape.bodyFraction, moved.shape.height * moved.shape.bodyFraction, EPSILON)
    assertEquals("the top stays", -shape.height / 2f, moved.centerOffset.y - moved.shape.height / 2f, EPSILON)
  }

  @Test
  fun `arrow ends turn and stretch the arrow and the middle bends it`() {
    val shape = shape(ShapeRenderer.Kind.ARROW, width = 0.5f, aspect = ShapeRenderer.arrowAspect(0f))
    val down = ShapeHandles.drag(Handle.ARROW_END, PointF(-0.25f, 0.5f), shape, MINIMUM)
    assertEquals(0.5f, down.shape.width, EPSILON)
    assertEquals((PI / 2).toFloat(), down.rotation, EPSILON)
    assertEquals(-0.25f, down.centerOffset.x, EPSILON)
    assertEquals(0.25f, down.centerOffset.y, EPSILON)

    val start = ShapeHandles.drag(Handle.ARROW_START, PointF(0.25f, -0.3f), shape, MINIMUM)
    assertEquals(0.3f, start.shape.width, EPSILON)
    assertEquals((PI / 2).toFloat(), start.rotation, EPSILON)
    assertEquals(0.25f, start.centerOffset.x, EPSILON)
    assertEquals(-0.15f, start.centerOffset.y, EPSILON)

    val bent = ShapeHandles.drag(Handle.ARROW_BEND, PointF(0.1f, 0.1f), shape, MINIMUM)
    assertEquals(0.2f, bent.shape.bend, EPSILON)
    assertEquals(0.7f, bent.shape.aspectRatio, EPSILON)
    assertEquals(0.1f, ShapeHandles.position(Handle.ARROW_BEND, bent.shape).y, EPSILON)
  }

  @Test
  fun `the session pulls the handle from where it started and commits the move into the element`() {
    val renderer = ShapeRenderer(ShapeRenderer.Kind.RECTANGLE, Color.RED, false, 40f)
    val element = EditorElement(renderer, EditorModel.Z_STICKERS)
    // 框 2000 × 1500，右下角在 (1000, 750)。拉到 (1400, 1050)：左上角 (-1000, -750) 不动。
    val session = ShapeHandleEditSession(element, renderer, Handle.BOTTOM_RIGHT, Matrix())
    session.movePoint(0, PointF(1200f, 900f))
    session.movePoint(0, PointF(1400f, 1050f))
    session.movePoint(1, PointF(0f, 0f))

    assertEquals(2400f, renderer.width, 0.01f)
    assertEquals(1800f / 2400f, renderer.aspectRatio, EPSILON)
    assertEquals("the line width does not change", 40f, renderer.thickness, 0f)
    assertNull("a second finger ends the pull", session.newPoint(Matrix(), PointF(), 1))

    session.commit()
    val center = floatArrayOf(0f, 0f)
    element.localMatrix.mapPoints(center)
    assertEquals(200f, center[0], 0.01f)
    assertEquals(150f, center[1], 0.01f)
  }

  @Test
  fun `pulling an arrow end turns the element`() {
    val renderer = ShapeRenderer(ShapeRenderer.Kind.ARROW, Color.RED, false, 40f)
    val element = EditorElement(renderer, EditorModel.Z_STICKERS)
    // 起点 (-1000, 0) 不动，尖拉到正下方 (-1000, 1000)。
    val session = ShapeHandleEditSession(element, renderer, Handle.ARROW_END, Matrix())
    session.movePoint(0, PointF(-1000f, 1000f))
    session.commit()

    assertEquals(1000f, renderer.width, 0.01f)
    val tip = floatArrayOf(500f, 0f)
    element.localMatrix.mapPoints(tip)
    assertEquals(-1000f, tip[0], 0.5f)
    assertEquals(1000f, tip[1], 0.5f)
  }

  @Test
  fun `handles only take the touch on a selected shape`() {
    val renderer = ShapeRenderer(ShapeRenderer.Kind.RECTANGLE, Color.RED, false, 40f)
    // 右下角 (1000, 750) 外面一点（局部单位；没画过时一个像素算一个单位、拉柄按 24 以内算）。
    assertNull(renderer.handleAt(1010f, 760f))
    assertEquals(false, renderer.hitTest(1010f, 760f))

    renderer.onSelected(true)
    assertEquals(Handle.BOTTOM_RIGHT, renderer.handleAt(1010f, 760f))
    assertEquals(true, renderer.hitTest(1010f, 760f))
    assertNull(renderer.handleAt(0f, 0f))
  }

  private fun shape(kind: ShapeRenderer.Kind, width: Float, aspect: Float): ShapeHandles.Shape {
    return ShapeHandles.Shape(kind, width, aspect)
  }

  private companion object {
    const val MINIMUM = 0.04f
    const val EPSILON = 0.0001f
  }
}
