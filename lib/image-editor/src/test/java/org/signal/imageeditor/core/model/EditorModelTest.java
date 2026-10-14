/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.imageeditor.core.model;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Parcel;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;
import org.signal.imageeditor.core.Renderer;
import org.signal.imageeditor.core.RendererContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether a model reports as changed decides whether the image is re-rendered when it is sent, so it has to track what
 * the user did to the image and nothing else.
 */
@RunWith(RobolectricTestRunner.class)
// The editor hierarchy builds an inverse-fill Path for the crop mask, which the legacy graphics shadows cannot do.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class EditorModelTest {

  /** A viewport that does not match the square bounds the model starts out with. */
  private static final RectF VIEW_PORT = new RectF(-1f, -0.75f, 1f, 0.75f);

  /** As if some chrome, e.g. the keyboard, came up after the initial layout. */
  private static final RectF SMALLER_VIEW_PORT = new RectF(-1f, -0.5f, 1f, 0.5f);

  private static final Point IMAGE_SIZE = new Point(2252, 4000);

  private final TestRenderer mainImageRenderer = new TestRenderer();

  @Test
  public void aNewModelIsNotChanged() {
    EditorModel model = modelWithMainImage();

    assertFalse(model.isChanged());
  }

  @Test
  public void layingOutTheModelIsNotAChange() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);

    assertFalse(model.isChanged());
  }

  @Test
  public void loadingTheMainImageAfterLayoutIsNotAChange() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);

    assertFalse(model.isChanged());
  }

  @Test
  public void layingOutAgainAfterLoadingTheMainImageIsNotAChange() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);
    model.setVisibleViewPort(SMALLER_VIEW_PORT);

    assertFalse(model.isChanged());
  }

  @Test
  public void addingAnElementIsAChange() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);
    model.addElement(new EditorElement(new TestRenderer()));

    assertTrue(model.isChanged());
  }

  @Test
  public void layingOutAfterAnEditKeepsTheEdit() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);
    model.addElement(new EditorElement(new TestRenderer()));
    model.setVisibleViewPort(SMALLER_VIEW_PORT);

    assertTrue(model.isChanged());
  }

  /** Tellomi (tellomi/tellomi#1522 E-1): a crop opened before the image loads is still open once it has loaded. */
  @Test
  public void aCropOpenedBeforeTheImageLoadsStaysOpen() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    model.startCrop();
    ready(model);

    assertTrue(model.isCropping());
    model.setCropAspectRatio(1f);
    assertEquals(1f, model.getCropAspectRatioOnScreen(), 0.01f);
  }

  /** Tellomi (tellomi/tellomi#1522 E-1): picking an aspect ratio while cropping shrinks the crop to it and locks it. */
  @Test
  public void croppingToAnAspectRatioShrinksTheCropToItAndLocksIt() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);
    model.startCrop();

    float before = model.getCropAspectRatioOnScreen();
    assertEquals(IMAGE_SIZE.x / (float) IMAGE_SIZE.y, before, 0.01f);
    assertEquals(before, model.getOrientedImageAspectRatio(), 0.01f);

    model.setCropAspectRatio(1.5f);

    assertEquals(1.5f, model.getCropAspectRatioOnScreen(), 0.01f);
    assertTrue(model.isCropAspectLocked());
  }

  /** Tellomi (tellomi/tellomi#1522 E-1): after a quarter turn the ratio is still the one seen on screen. */
  @Test
  public void croppingToAnAspectRatioAfterAQuarterTurnUsesTheScreenAxes() {
    EditorModel model = modelWithMainImage();

    model.setVisibleViewPort(VIEW_PORT);
    ready(model);
    model.startCrop();
    model.rotate90anticlockwise();

    assertEquals(IMAGE_SIZE.y / (float) IMAGE_SIZE.x, model.getCropAspectRatioOnScreen(), 0.01f);
    assertEquals(IMAGE_SIZE.y / (float) IMAGE_SIZE.x, model.getOrientedImageAspectRatio(), 0.01f);

    model.setCropAspectRatio(4f / 5f);

    assertEquals(0.8f, model.getCropAspectRatioOnScreen(), 0.01f);
  }

  private @NonNull EditorModel modelWithMainImage() {
    EditorModel model = EditorModel.create(0);
    model.addElement(new EditorElement(mainImageRenderer));
    return model;
  }

  /** Mimics what the main image's renderer reports once its bitmap has decoded. */
  private void ready(@NonNull EditorModel model) {
    Matrix cropMatrix = new Matrix();

    cropMatrix.preScale(IMAGE_SIZE.x / (float) IMAGE_SIZE.y, 1f);

    model.onReady(mainImageRenderer, cropMatrix, IMAGE_SIZE);
  }

  private static final class TestRenderer implements Renderer {

    @Override
    public void render(@NonNull RendererContext rendererContext) {
    }

    @Override
    public boolean hitTest(float x, float y) {
      return false;
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    public static final Creator<TestRenderer> CREATOR = new Creator<TestRenderer>() {
      @Override
      public TestRenderer createFromParcel(Parcel in) {
        return new TestRenderer();
      }

      @Override
      public TestRenderer[] newArray(int size) {
        return new TestRenderer[size];
      }
    };
  }
}