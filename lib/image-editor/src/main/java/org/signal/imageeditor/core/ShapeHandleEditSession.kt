/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.imageeditor.core

import android.graphics.Matrix
import android.graphics.PointF
import org.signal.imageeditor.core.model.EditorElement
import org.signal.imageeditor.core.renderers.ShapeHandles
import org.signal.imageeditor.core.renderers.ShapeRenderer

/**
 * Tellomi（tellomi/tellomi#1522 E-3b）：拉选中形状的一个拉柄。每次都从拉之前的形状算（[ShapeHandles.drag]），新的框和参数给渲染器，
 * 中心挪动和转角放进元素的编辑矩阵（松手时 [commit] 并进元素的矩阵），线宽不跟着变。第二根手指落下就结束（不当捏合）。
 *
 * @param inverse 拉之前，屏幕 → 形状局部坐标。
 */
internal class ShapeHandleEditSession(
  private val selected: EditorElement,
  private val renderer: ShapeRenderer,
  private val handle: ShapeHandles.Handle,
  private val inverse: Matrix
) : EditSession {

  private val start = renderer.shape
  override fun movePoint(p: Int, point: PointF) {
    if (p != 0) return
    val local = FloatArray(2)
    inverse.mapPoints(local, floatArrayOf(point.x, point.y))
    val result = ShapeHandles.drag(handle, PointF(local[0], local[1]), start, MINIMUM_SIZE)
    renderer.apply(result.shape)
    selected.editorMatrix.apply {
      reset()
      preTranslate(result.centerOffset.x, result.centerOffset.y)
      preRotate(Math.toDegrees(result.rotation.toDouble()).toFloat())
    }
  }

  override fun getSelected(): EditorElement = selected

  override fun newPoint(newInverse: Matrix, point: PointF, p: Int): EditSession? = null

  override fun removePoint(newInverse: Matrix, p: Int): EditSession? = null

  override fun commit() {
    selected.commitEditorMatrix()
  }

  companion object {
    /** 最小宽高（局部单位；新形状宽 2000，相当于 iOS 的图短边 0.04）。 */
    const val MINIMUM_SIZE = 160f
  }
}
