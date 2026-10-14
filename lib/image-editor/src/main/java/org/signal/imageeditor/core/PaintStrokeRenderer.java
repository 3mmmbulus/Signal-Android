/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.imageeditor.core;

/**
 * Tellomi (tellomi/tellomi#1522 E-2, as Telegram's eraser on both clients): a brush stroke on the paint layer.
 * <p>
 * An eraser stroke clears the paint strokes (and blur strokes) drawn before it, never the photo, stickers or text.
 * Strokes keep a sequence number so the order between blur strokes and erasers survives being kept in different
 * z-order groups.
 */
public interface PaintStrokeRenderer {

  boolean isEraser();

  /** Increases with every stroke drawn; later strokes have larger numbers. */
  long getStrokeSequence();
}
