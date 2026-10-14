/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import org.signal.imageeditor.core.renderers.BezierDrawingRenderer

/**
 * Tellomi (tellomi/tellomi#1522 E-2): the brushes in the draw tool's brush menu, in Telegram's order (pen, arrow,
 * marker, neon, eraser; Signal's highlighter is Telegram's marker). Blur stays its own tool, as upstream.
 *
 * Each brush keeps its own color, as on both Telegram clients; [defaultColor] is the one a brush starts with (Telegram's
 * defaults: orange arrow, yellow marker, green neon). The pen starts from the color bar's upstream default, and the
 * eraser has no color.
 */
enum class DrawBrush(val tool: BrushTool, val style: BezierDrawingRenderer.Style, val defaultColor: Int?) {
  PEN(BrushTool.MARKER, BezierDrawingRenderer.Style.NORMAL, null),
  ARROW(BrushTool.ARROW, BezierDrawingRenderer.Style.ARROW, 0xFFFF8A00.toInt()),
  HIGHLIGHTER(BrushTool.HIGHLIGHTER, BezierDrawingRenderer.Style.NORMAL, 0xFFFFD60A.toInt()),
  NEON(BrushTool.NEON, BezierDrawingRenderer.Style.NEON, 0xFF34C759.toInt()),
  ERASER(BrushTool.ERASER, BezierDrawingRenderer.Style.ERASER, null)
}
