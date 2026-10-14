/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.imageeditor.core.renderers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;
import org.signal.imageeditor.core.Bounds;
import org.signal.imageeditor.core.Renderer;
import org.signal.imageeditor.core.RendererContext;
import org.signal.imageeditor.core.model.EditorElement;
import org.signal.imageeditor.core.model.EditorModel;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tellomi (tellomi/tellomi#1522 E-2, as Telegram's brushes): the arrow head, and the eraser clearing only the paint
 * strokes drawn before it.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class BezierDrawingRendererTellomiTest {

  private static final int SIZE = 200;

  @Test
  public void theArrowHeadPointsBackFromTheTipAtTheArmAngle() {
    AutomaticControlPointBezierLine line = line(0, 0, 100, 0, 200, 0, 300, 0);

    float[] head = BezierDrawingRenderer.arrowHead(line, 20f, 1f);

    assertNotNull(head);
    float length = Math.max(60f, BezierDrawingRenderer.ARROW_MIN_HEAD);
    assertEquals(300f, head[0], 0.001f);
    assertEquals(0f, head[1], 0.001f);
    assertEquals(300f + length * (float) Math.cos(Math.PI + BezierDrawingRenderer.ARROW_ARM_ANGLE), head[2], 0.01f);
    assertEquals(length * (float) Math.sin(Math.PI + BezierDrawingRenderer.ARROW_ARM_ANGLE), head[3], 0.01f);
    assertEquals(300f + length * (float) Math.cos(Math.PI - BezierDrawingRenderer.ARROW_ARM_ANGLE), head[4], 0.01f);
    assertEquals(length * (float) Math.sin(Math.PI - BezierDrawingRenderer.ARROW_ARM_ANGLE), head[5], 0.01f);
  }

  @Test
  public void aWobbleAsTheFingerLiftsDoesNotSwingTheHead() {
    // The last few units hook upwards; the head still follows the stroke, which runs to the right.
    AutomaticControlPointBezierLine line = line(0, 0, 100, 0, 200, 0, 300, 0, 303, 6);

    float[] head = BezierDrawingRenderer.arrowHead(line, 20f, 1f);

    assertNotNull(head);
    double arm1 = Math.atan2(head[3] - head[1], head[2] - head[0]);
    double arm2 = Math.atan2(head[5] - head[1], head[4] - head[0]);
    double middle = Math.atan2((Math.sin(arm1) + Math.sin(arm2)) / 2, (Math.cos(arm1) + Math.cos(arm2)) / 2);
    assertEquals(Math.PI, Math.abs(middle), Math.toRadians(5));
  }

  @Test
  public void theHeadGrowsWithItsProgressAndNeedsADirection() {
    AutomaticControlPointBezierLine line = line(0, 0, 300, 0);
    float[] half = BezierDrawingRenderer.arrowHead(line, 20f, 0.5f);
    float[] full = BezierDrawingRenderer.arrowHead(line, 20f, 1f);
    assertNotNull(half);
    assertNotNull(full);
    assertEquals((full[2] - 300f) / 2f, half[2] - 300f, 0.01f);

    assertNull(BezierDrawingRenderer.arrowHead(line(5, 5), 20f, 1f));
    assertNull(BezierDrawingRenderer.arrowHead(line(5, 5, 5, 5), 20f, 1f));
  }

  @Test
  public void styleOrderAndAFinishedHeadSurviveTheParcel() {
    BezierDrawingRenderer arrow = stroke(Color.RED, BezierDrawingRenderer.Style.ARROW, 0, 0, 300, 0);
    arrow.setArrowHeadProgress(0.3f);
    BezierDrawingRenderer eraser = stroke(Color.RED, BezierDrawingRenderer.Style.ERASER, 0, 0, 300, 0);

    BezierDrawingRenderer arrowBack  = roundTrip(arrow);
    BezierDrawingRenderer eraserBack = roundTrip(eraser);

    assertEquals(BezierDrawingRenderer.Style.ARROW, arrowBack.getStyle());
    assertEquals(1f, arrowBack.getArrowHeadProgress(), 0f);
    assertEquals(arrow.getStrokeSequence(), arrowBack.getStrokeSequence());
    assertFalse(arrowBack.isEraser());
    assertTrue(eraserBack.isEraser());
    assertTrue(eraser.getStrokeSequence() > arrow.getStrokeSequence());
  }

  @Test
  public void anArrowSavedTheMomentTheFingerLiftsComesBackWithItsHead() {
    BezierDrawingRenderer arrow = stroke(Color.RED, BezierDrawingRenderer.Style.ARROW, 0, 0, 300, 0);

    arrow.finishStroke();

    assertEquals(1f, roundTrip(arrow).getArrowHeadProgress(), 0f);
    assertEquals(0f, roundTrip(stroke(Color.RED, BezierDrawingRenderer.Style.ARROW, 0, 0, 300, 0)).getArrowHeadProgress(), 0f);
  }

  @Test
  public void theEraserClearsEarlierStrokesButNotThePhotoStickersOrLaterStrokes() {
    EditorElement root = new EditorElement(new FillRenderer(Color.BLUE, Bounds.FULL_BOUNDS));
    // A red stroke across the middle, then a sticker, then an eraser down the middle, then a yellow stroke.
    root.addElement(new EditorElement(stroke(Color.RED, BezierDrawingRenderer.Style.NORMAL, -800, 0, 800, 0), EditorModel.Z_DRAWING));
    root.addElement(new EditorElement(new FillRenderer(Color.GREEN, new RectF(-100, 300, 100, 500)), EditorModel.Z_STICKERS));
    root.addElement(new EditorElement(stroke(Color.RED, BezierDrawingRenderer.Style.ERASER, 0, -800, 0, 800), EditorModel.Z_DRAWING));
    root.addElement(new EditorElement(stroke(Color.YELLOW, BezierDrawingRenderer.Style.NORMAL, -800, -400, 800, -400), EditorModel.Z_DRAWING));

    Bitmap bitmap = render(root);

    assertEquals("the red stroke away from the eraser", Color.RED, bitmap.getPixel(60, 100));
    assertEquals("the red stroke under the eraser is gone, the photo shows", Color.BLUE, bitmap.getPixel(100, 100));
    assertEquals("the sticker is not erased", Color.GREEN, bitmap.getPixel(100, 140));
    assertEquals("the stroke after the eraser is not erased", Color.YELLOW, bitmap.getPixel(100, 60));
    assertEquals("the eraser on bare photo leaves the photo", Color.BLUE, bitmap.getPixel(100, 170));
  }

  @Test
  public void anEraserInsideARunOnlyClearsWhatCameBeforeIt() {
    EditorElement root = new EditorElement(new FillRenderer(Color.BLUE, Bounds.FULL_BOUNDS));
    root.addElement(new EditorElement(stroke(Color.RED, BezierDrawingRenderer.Style.NORMAL, -800, 0, 800, 0), EditorModel.Z_DRAWING));
    root.addElement(new EditorElement(stroke(Color.RED, BezierDrawingRenderer.Style.ERASER, -800, 0, 800, 0), EditorModel.Z_DRAWING));
    root.addElement(new EditorElement(stroke(Color.YELLOW, BezierDrawingRenderer.Style.NORMAL, 0, -800, 0, 800), EditorModel.Z_DRAWING));

    Bitmap bitmap = render(root);

    assertEquals(Color.BLUE, bitmap.getPixel(60, 100));
    assertEquals(Color.YELLOW, bitmap.getPixel(100, 100));
  }

  @Test
  public void aFinishedArrowDrawsItsHeadAndNeonHasAWhiteCoreInAGlow() {
    EditorElement root = new EditorElement(new FillRenderer(Color.BLUE, Bounds.FULL_BOUNDS));
    BezierDrawingRenderer arrow = stroke(Color.RED, BezierDrawingRenderer.Style.ARROW, -800, -500, 400, -500);
    arrow.setArrowHeadProgress(1f);
    root.addElement(new EditorElement(arrow, EditorModel.Z_DRAWING));
    root.addElement(new EditorElement(stroke(Color.GREEN, BezierDrawingRenderer.Style.NEON, -800, 500, 800, 500), EditorModel.Z_DRAWING));

    Bitmap bitmap = render(root);

    // Head arms reach back from the tip (x 400) at ±0.45 rad; a point on the upper arm, 250 units back along it.
    float armX = 400f + 250f * (float) Math.cos(Math.PI - BezierDrawingRenderer.ARROW_ARM_ANGLE);
    float armY = -500f - 250f * (float) Math.sin(Math.PI - BezierDrawingRenderer.ARROW_ARM_ANGLE);
    assertEquals("the arrow head is drawn", Color.RED, bitmap.getPixel(Math.round((armX + 1000) / 10), Math.round((armY + 1000) / 10)));

    assertEquals("neon's core is white", Color.WHITE, bitmap.getPixel(100, 150));
    int glow = bitmap.getPixel(100, 150 - 7);
    assertTrue("neon glows green just outside its body: " + Integer.toHexString(glow), Color.green(glow) > Color.blue(glow));
  }

  @Test
  public void anEraserOnlyTakesBlurAwayWhenItCameAfterABlurStroke() {
    EditorElement blur   = new EditorElement(stroke(Color.BLACK, BezierDrawingRenderer.Style.NORMAL, 0, 0, 10, 0), EditorModel.Z_MASK);
    EditorElement eraser = new EditorElement(stroke(Color.BLACK, BezierDrawingRenderer.Style.ERASER, 0, 0, 10, 0), EditorModel.Z_DRAWING);
    EditorElement later  = new EditorElement(stroke(Color.BLACK, BezierDrawingRenderer.Style.NORMAL, 0, 0, 10, 0), EditorModel.Z_MASK);
    EditorElement face   = new EditorElement(new FaceBlurRenderer(), EditorModel.Z_MASK);

    assertTrue(UriGlideRenderer.hasErasedBlurStrokes(Arrays.asList(blur, face, eraser)));
    assertFalse(UriGlideRenderer.hasErasedBlurStrokes(Arrays.asList(face, eraser)));
    // The eraser was drawn before the only blur stroke (sequence numbers say so, whatever the list order).
    assertFalse(UriGlideRenderer.hasErasedBlurStrokes(Arrays.asList(later, eraser)));
  }

  // region helpers

  private static AutomaticControlPointBezierLine line(float... points) {
    AutomaticControlPointBezierLine line = new AutomaticControlPointBezierLine();
    for (int i = 0; i < points.length; i += 2) {
      line.addPoint(points[i], points[i + 1]);
    }
    return line;
  }

  private static BezierDrawingRenderer stroke(int color, BezierDrawingRenderer.Style style, float x1, float y1, float x2, float y2) {
    BezierDrawingRenderer renderer = new BezierDrawingRenderer(color, 100f, Paint.Cap.ROUND, null, style);
    renderer.setFirstPoint(new PointF(x1, y1));
    renderer.addNewPoint(new PointF((x1 + x2) / 2, (y1 + y2) / 2));
    renderer.addNewPoint(new PointF(x2, y2));
    return renderer;
  }

  private static BezierDrawingRenderer roundTrip(BezierDrawingRenderer renderer) {
    Parcel parcel = Parcel.obtain();
    try {
      renderer.writeToParcel(parcel, 0);
      parcel.setDataPosition(0);
      return BezierDrawingRenderer.CREATOR.createFromParcel(parcel);
    } finally {
      parcel.recycle();
    }
  }

  /** Draws [root] with the editor's coordinate space (−1000…1000) mapped onto a 200×200 bitmap. */
  private static Bitmap render(EditorElement root) {
    Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
    RendererContext context = new RendererContext(ApplicationProvider.getApplicationContext(),
                                                  new Canvas(bitmap),
                                                  RendererContext.Ready.NULL,
                                                  RendererContext.Invalidate.NULL,
                                                  (c, r, i) -> android.graphics.Typeface.DEFAULT);
    Matrix matrix = new Matrix();
    matrix.setRectToRect(Bounds.FULL_BOUNDS, new RectF(0, 0, SIZE, SIZE), Matrix.ScaleToFit.FILL);
    context.canvasMatrix.initial(matrix);
    root.draw(context);
    return bitmap;
  }

  /** Stands in for the photo (full bounds) or a sticker (a small rect). */
  private static final class FillRenderer implements Renderer {
    private final int   color;
    private final RectF rect;

    FillRenderer(int color, RectF rect) {
      this.color = color;
      this.rect  = rect;
    }

    @Override
    public void render(@NonNull RendererContext rendererContext) {
      Paint paint = new Paint();
      paint.setColor(color);
      rendererContext.canvas.drawRect(rect, paint);
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
    public void writeToParcel(@NonNull Parcel dest, int flags) {
    }
  }

  // endregion
}
