/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit.image

import android.os.Parcelable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.ColorUtils
import kotlinx.parcelize.Parcelize

private const val OPAQUE_ALPHA = 0xFF
private const val HIGHLIGHTER_ALPHA = 0x60

/**
 * The image editor tools which support a user-adjustable stroke width.
 *
 * Thicknesses are expressed as a fraction of the editor's coordinate space width, matching what
 * [ImageEditorState.drawThickness] expects.
 */
enum class BrushTool(val minThickness: Float, val maxThickness: Float, val alpha: Int = OPAQUE_ALPHA) {
  MARKER(0.01f, 0.05f),
  HIGHLIGHTER(0.03f, 0.08f, alpha = HIGHLIGHTER_ALPHA),
  BLUR(0.052f, 0.092f),

  // Tellomi (tellomi/tellomi#1522 E-2, as Telegram's brushes): the arrow draws like the pen; neon and the eraser run
  // wider, as on Telegram.
  ARROW(0.01f, 0.05f),
  NEON(0.015f, 0.06f),
  ERASER(0.02f, 0.08f);

  fun thicknessAt(fraction: Float): Float = minThickness + (maxThickness - minThickness) * fraction

  /** [color] with this tool's opacity applied. */
  fun applyAlpha(color: Int): Int = ColorUtils.setAlphaComponent(color, alpha)
}

/**
 * The stroke width each [BrushTool] is set to, as a fraction of that tool's own thickness range.
 */
@Parcelize
data class BrushWidths(
  val marker: Float = 0f,
  val highlighter: Float = 0f,
  val blur: Float = 0f,
  val arrow: Float = 0f,
  val neon: Float = DEFAULT_NEON,
  val eraser: Float = DEFAULT_ERASER
) : Parcelable {

  companion object {
    const val DEFAULT_NEON = 0.3f
    const val DEFAULT_ERASER = 0.5f
  }

  operator fun get(tool: BrushTool): Float {
    return when (tool) {
      BrushTool.MARKER -> marker
      BrushTool.HIGHLIGHTER -> highlighter
      BrushTool.BLUR -> blur
      BrushTool.ARROW -> arrow
      BrushTool.NEON -> neon
      BrushTool.ERASER -> eraser
    }
  }

  fun with(tool: BrushTool, fraction: Float): BrushWidths {
    return when (tool) {
      BrushTool.MARKER -> copy(marker = fraction)
      BrushTool.HIGHLIGHTER -> copy(highlighter = fraction)
      BrushTool.BLUR -> copy(blur = fraction)
      BrushTool.ARROW -> copy(arrow = fraction)
      BrushTool.NEON -> copy(neon = fraction)
      BrushTool.ERASER -> copy(eraser = fraction)
    }
  }
}

/**
 * Mutable holder for [BrushWidths], scoped to the edit screen rather than to a single image so that every image in the
 * selection draws with the same brush.
 */
@Stable
internal class BrushWidthsState(initialWidths: BrushWidths = BrushWidths()) {

  var widths: BrushWidths by mutableStateOf(initialWidths)
    private set

  operator fun get(tool: BrushTool): Float = widths[tool]

  fun set(tool: BrushTool, fraction: Float) {
    widths = widths.with(tool, fraction)
  }
}
