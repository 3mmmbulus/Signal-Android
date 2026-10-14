/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import org.signal.imageeditor.core.renderers.BezierDrawingRenderer

/**
 * Tellomi (tellomi/tellomi#1522 E-2): the brushes in the draw tool's brush menu, in Telegram's order (pen, arrow,
 * marker, neon, eraser; Signal's highlighter is Telegram's marker). Blur stays its own tool, as upstream.
 */
enum class DrawBrush(val tool: BrushTool, val style: BezierDrawingRenderer.Style) {
  PEN(BrushTool.MARKER, BezierDrawingRenderer.Style.NORMAL),
  ARROW(BrushTool.ARROW, BezierDrawingRenderer.Style.ARROW),
  HIGHLIGHTER(BrushTool.HIGHLIGHTER, BezierDrawingRenderer.Style.NORMAL),
  NEON(BrushTool.NEON, BezierDrawingRenderer.Style.NEON),
  ERASER(BrushTool.ERASER, BezierDrawingRenderer.Style.ERASER)
}
