/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.imageeditor.core.renderers;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Parcel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tellomi (tellomi/tellomi#1522 E-4, as Telegram's text tools): text alignment and the semi-transparent frame.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MultiLineTextRendererTellomiTest {

  @Test
  public void alignmentCyclesLeftCenterRight() {
    assertEquals(MultiLineTextRenderer.Align.RIGHT, MultiLineTextRenderer.Align.CENTER.next());
    assertEquals(MultiLineTextRenderer.Align.LEFT, MultiLineTextRenderer.Align.RIGHT.next());
    assertEquals(MultiLineTextRenderer.Align.CENTER, MultiLineTextRenderer.Align.LEFT.next());
  }

  @Test
  public void theStyleToggleGoesThroughTheSemiTransparentFrameAfterTheSolidOne() {
    MultiLineTextRenderer renderer = new MultiLineTextRenderer("a", Color.WHITE, MultiLineTextRenderer.Mode.REGULAR);
    MultiLineTextRenderer.Mode[] seen = new MultiLineTextRenderer.Mode[6];
    for (int i = 0; i < seen.length; i++) {
      seen[i] = renderer.getMode();
      renderer.nextMode();
    }
    assertEquals(MultiLineTextRenderer.Mode.REGULAR, seen[0]);
    assertEquals(MultiLineTextRenderer.Mode.HIGHLIGHT, seen[1]);
    assertEquals(MultiLineTextRenderer.Mode.SEMI, seen[2]);
    assertEquals(MultiLineTextRenderer.Mode.UNDERLINE, seen[3]);
    assertEquals(MultiLineTextRenderer.Mode.OUTLINE, seen[4]);
    assertEquals(MultiLineTextRenderer.Mode.REGULAR, seen[5]);
  }

  @Test
  public void theSemiFrameIsDarkBehindLightTextAndLightBehindDarkText() {
    assertEquals(0x99000000, MultiLineTextRenderer.semiBackgroundColor(Color.WHITE));
    assertEquals(0x99000000, MultiLineTextRenderer.semiBackgroundColor(Color.YELLOW));
    assertEquals(0x99ffffff, MultiLineTextRenderer.semiBackgroundColor(Color.BLACK));
    assertEquals(0x99ffffff, MultiLineTextRenderer.semiBackgroundColor(0xff202060));
  }

  @Test
  public void shorterLinesMoveTowardsTheChosenSide() {
    MultiLineTextRenderer renderer = new MultiLineTextRenderer("i\nmmmmmmmm", Color.WHITE, MultiLineTextRenderer.Mode.REGULAR);
    assertEquals(0f, renderer.getLineAlignOffset(0), 0.001f);

    renderer.setAlign(MultiLineTextRenderer.Align.LEFT);
    assertTrue("short line moves left", renderer.getLineAlignOffset(0) < 0);
    assertEquals("widest line stays", 0f, renderer.getLineAlignOffset(1), 0.001f);

    renderer.setAlign(MultiLineTextRenderer.Align.RIGHT);
    assertTrue("short line moves right", renderer.getLineAlignOffset(0) > 0);

    renderer.setText("mmmmmmmmmmmmmmmm\ni");
    assertTrue("re-aligned after the text changes", renderer.getLineAlignOffset(1) > 0);
  }

  @Test
  public void alignmentSurvivesAParcelAndOldParcelsReadAsCentered() {
    MultiLineTextRenderer renderer = new MultiLineTextRenderer("hi", Color.RED, MultiLineTextRenderer.Mode.SEMI, MultiLineTextRenderer.Align.RIGHT);
    Parcel parcel = Parcel.obtain();
    renderer.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);
    MultiLineTextRenderer copy = MultiLineTextRenderer.CREATOR.createFromParcel(parcel);
    parcel.recycle();
    assertEquals(MultiLineTextRenderer.Align.RIGHT, copy.getAlign());
    assertEquals(MultiLineTextRenderer.Mode.SEMI, copy.getMode());

    Parcel old = Parcel.obtain();
    old.writeString("hi");
    old.writeInt(Color.RED);
    old.writeInt(1);
    old.setDataPosition(0);
    MultiLineTextRenderer oldCopy = MultiLineTextRenderer.CREATOR.createFromParcel(old);
    old.recycle();
    assertEquals(MultiLineTextRenderer.Align.CENTER, oldCopy.getAlign());
    assertEquals(MultiLineTextRenderer.Mode.HIGHLIGHT, oldCopy.getMode());
  }

  @Test
  public void fontsCycleAndTheDefaultLeavesTheProvidersOwnTypeface() {
    MultiLineTextRenderer renderer = new MultiLineTextRenderer("hi", Color.WHITE, MultiLineTextRenderer.Mode.REGULAR);
    assertEquals(MultiLineTextRenderer.Font.DEFAULT, renderer.getFont());
    Typeface fallback = Typeface.create("sans-serif", Typeface.BOLD);
    assertSame(fallback, MultiLineTextRenderer.typefaceFor(renderer, fallback));

    renderer.setFont(MultiLineTextRenderer.Font.DEFAULT.next());
    assertEquals(MultiLineTextRenderer.Font.MEDIUM, renderer.getFont());
    assertNotSame(fallback, MultiLineTextRenderer.typefaceFor(renderer, fallback));

    MultiLineTextRenderer.Font font = MultiLineTextRenderer.Font.MONO;
    assertEquals(MultiLineTextRenderer.Font.DEFAULT, font.next());
  }

  @Test
  public void theFontSurvivesAParcel() {
    MultiLineTextRenderer renderer = new MultiLineTextRenderer("hi", Color.RED, MultiLineTextRenderer.Mode.REGULAR, MultiLineTextRenderer.Align.LEFT, MultiLineTextRenderer.Font.SERIF);
    Parcel parcel = Parcel.obtain();
    renderer.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);
    MultiLineTextRenderer copy = MultiLineTextRenderer.CREATOR.createFromParcel(parcel);
    parcel.recycle();
    assertEquals(MultiLineTextRenderer.Font.SERIF, copy.getFont());
    assertEquals(MultiLineTextRenderer.Align.LEFT, copy.getAlign());
  }
}
