package org.signal.imageeditor.core.renderers;

import android.animation.ValueAnimator;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcel;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.graphics.ColorUtils;

import org.signal.imageeditor.core.Bounds;
import org.signal.imageeditor.core.ColorableRenderer;
import org.signal.imageeditor.core.PaintStrokeRenderer;
import org.signal.imageeditor.core.RendererContext;

/**
 * Renders a {@link AutomaticControlPointBezierLine} with {@link #thickness}, {@link #color} and {@link #cap} end type.
 */
public final class BezierDrawingRenderer extends InvalidateableRenderer implements ColorableRenderer, PaintStrokeRenderer {

  /**
   * Tellomi (tellomi/tellomi#1522 E-2, as Telegram's brushes on both clients): the arrow, neon and eraser brushes on
   * top of Signal's pen / highlighter / blur (which are all {@link #NORMAL} with a different cap, alpha or z-order).
   */
  public enum Style {
    NORMAL,
    /** The pen, plus a two-armed head at the end that grows in when the finger lifts. */
    ARROW,
    /** A white core in a light tint of the color, with a glow of the color around it. */
    NEON,
    /** Clears the paint strokes and blur strokes drawn before it (see {@link PaintStrokeRenderer}). */
    ERASER;

    static @NonNull Style fromOrdinal(int ordinal) {
      Style[] values = values();
      return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NORMAL;
    }
  }

  /** Arrow arms leave the tip at ±0.45 rad from the stroke's way back (as Telegram iOS's pen tool). */
  @VisibleForTesting static final float ARROW_ARM_ANGLE = 0.45f;
  /** Arrow head length: the larger of 3× the stroke width and 2% of the image width. */
  @VisibleForTesting static final float ARROW_MIN_HEAD  = 0.02f * Bounds.FULL_BOUNDS.width();
  private static final long  ARROW_HEAD_DURATION_MS = 200;

  private static final int   NEON_DARK_GLOW   = 0xFF440881;
  private static final float NEON_GLOW_ALPHA  = 0.87f;

  private static final PorterDuffXfermode ERASE_MODE = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);

  private static long lastStrokeSequence;

  private final Paint      paint;
  private final AutomaticControlPointBezierLine bezierLine;
  private final Paint.Cap  cap;
  private final Style      style;
  private final long       strokeSequence;

  @Nullable
  private final RectF      clipRect;

  private       int        color;
  private       float      thickness;
  private       float      arrowHeadProgress;
  private       boolean    arrowHeadStarted;

  @Nullable private Paint neonPaint;
  @Nullable private BlurMaskFilter neonGlow;
  private float neonGlowRadius;

  private BezierDrawingRenderer(int color,
                                float thickness,
                                @NonNull Paint.Cap cap,
                                @Nullable AutomaticControlPointBezierLine bezierLine,
                                @Nullable RectF clipRect,
                                @NonNull Style style,
                                long strokeSequence,
                                float arrowHeadProgress)
  {
    this.paint             = new Paint();
    this.color             = color;
    this.thickness         = thickness;
    this.cap               = cap;
    this.clipRect          = clipRect;
    this.bezierLine        = bezierLine != null ? bezierLine : new AutomaticControlPointBezierLine();
    this.style             = style;
    this.strokeSequence    = strokeSequence;
    this.arrowHeadProgress = arrowHeadProgress;
    this.arrowHeadStarted  = arrowHeadProgress > 0f;

    updatePaint();
  }

  public BezierDrawingRenderer(int color, float thickness, @NonNull Paint.Cap cap, @Nullable RectF clipRect) {
    this(color, thickness, cap, clipRect, Style.NORMAL);
  }

  public BezierDrawingRenderer(int color, float thickness, @NonNull Paint.Cap cap, @Nullable RectF clipRect, @NonNull Style style) {
    this(color, thickness, cap, null, clipRect != null ? new RectF(clipRect) : null, style, nextStrokeSequence(), 0f);
  }

  private static synchronized long nextStrokeSequence() {
    lastStrokeSequence = Math.max(lastStrokeSequence + 1, System.currentTimeMillis() * 1000);
    return lastStrokeSequence;
  }

  /** Strokes brought back from a parcel push the counter past them, so a stroke drawn next is always later. */
  private static synchronized void observeStrokeSequence(long sequence) {
    lastStrokeSequence = Math.max(lastStrokeSequence, sequence);
  }

  @Override
  public int getColor() {
    return color;
  }

  @Override
  public void setColor(int color) {
    if (this.color != color) {
      this.color = color;
      updatePaint();
      invalidate();
    }
  }

  public void setThickness(float thickness) {
    if (this.thickness != thickness) {
      this.thickness = thickness;
      updatePaint();
      invalidate();
    }
  }

  public @NonNull Style getStyle() {
    return style;
  }

  @Override
  public boolean isEraser() {
    return style == Style.ERASER;
  }

  @Override
  public long getStrokeSequence() {
    return strokeSequence;
  }

  private void updatePaint() {
    // The eraser takes away whatever it covers, so it is always fully opaque.
    paint.setColor(style == Style.ERASER ? Color.BLACK : color);
    paint.setStrokeWidth(thickness);
    paint.setStyle(Paint.Style.STROKE);
    paint.setAntiAlias(true);
    paint.setStrokeCap(cap);
  }

  public void setFirstPoint(PointF point) {
    bezierLine.reset();
    bezierLine.addPoint(point.x, point.y);
    invalidate();
  }

  public void addNewPoint(PointF point) {
    if (cap != Paint.Cap.ROUND) {
      bezierLine.addPointFiltered(point.x, point.y, thickness * 0.5f);
    } else {
      bezierLine.addPoint(point.x, point.y);
    }
    invalidate();
  }

  /**
   * The finger lifted. An arrow grows its head now (as Telegram on both clients); other brushes have nothing to do.
   */
  public void finishStroke() {
    if (style != Style.ARROW || arrowHeadStarted) return;
    arrowHeadStarted = true;

    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    animator.setDuration(ARROW_HEAD_DURATION_MS);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(animation -> {
      arrowHeadProgress = (float) animation.getAnimatedValue();
      invalidate();
    });
    animator.start();
  }

  @VisibleForTesting
  float getArrowHeadProgress() {
    return arrowHeadProgress;
  }

  @VisibleForTesting
  void setArrowHeadProgress(float arrowHeadProgress) {
    this.arrowHeadProgress = arrowHeadProgress;
    this.arrowHeadStarted  = arrowHeadProgress > 0f;
  }

  @Override
  public void render(@NonNull RendererContext rendererContext) {
    super.render(rendererContext);
    Canvas canvas = rendererContext.canvas;
    canvas.save();
    if (clipRect != null) {
      canvas.clipRect(clipRect);
    }

    int alpha = paint.getAlpha();
    paint.setAlpha(rendererContext.getAlpha(alpha));

    if (style == Style.ERASER) {
      paint.setXfermode(ERASE_MODE);
    } else {
      paint.setXfermode(rendererContext.getMaskPaint() != null ? rendererContext.getMaskPaint().getXfermode() : null);
    }

    if (style == Style.NEON) {
      drawNeon(canvas, rendererContext.getAlpha(255));
    } else {
      bezierLine.draw(canvas, paint);
    }

    if (style == Style.ARROW && arrowHeadProgress > 0f) {
      float[] head = arrowHead(bezierLine, thickness, arrowHeadProgress);
      if (head != null) {
        canvas.drawLine(head[0], head[1], head[2], head[3], paint);
        canvas.drawLine(head[0], head[1], head[4], head[5], paint);
      }
    }

    paint.setAlpha(alpha);
    rendererContext.canvas.restore();
  }

  /**
   * The arrow head for the line: { tipX, tipY, arm1X, arm1Y, arm2X, arm2Y }, or null if the line has no direction.
   * <p>
   * The direction is taken from the tip back to the last point more than half a head length away, so a little
   * wobble as the finger lifts does not swing the head around.
   */
  @VisibleForTesting
  static @Nullable float[] arrowHead(@NonNull AutomaticControlPointBezierLine line, float thickness, float progress) {
    int count = line.getPointCount();
    if (count < 2) return null;

    float tipX = line.getX(count - 1);
    float tipY = line.getY(count - 1);
    float head = Math.max(3 * thickness, ARROW_MIN_HEAD);
    float half = head / 2f;

    int i = count - 2;
    while (i > 0 && distanceSquared(line.getX(i), line.getY(i), tipX, tipY) < half * half) {
      i--;
    }

    float dx = tipX - line.getX(i);
    float dy = tipY - line.getY(i);
    if (dx == 0f && dy == 0f) return null;

    double back   = Math.atan2(-dy, -dx);
    float  length = head * Math.min(1f, Math.max(0f, progress));
    return new float[] {
        tipX,
        tipY,
        tipX + (float) (length * Math.cos(back + ARROW_ARM_ANGLE)),
        tipY + (float) (length * Math.sin(back + ARROW_ARM_ANGLE)),
        tipX + (float) (length * Math.cos(back - ARROW_ARM_ANGLE)),
        tipY + (float) (length * Math.sin(back - ARROW_ARM_ANGLE))
    };
  }

  private static float distanceSquared(float x1, float y1, float x2, float y2) {
    float dx = x1 - x2;
    float dy = y1 - y2;
    return dx * dx + dy * dy;
  }

  /**
   * Neon (as Telegram on both clients): a glow of the color, a body in a light tint of it, and a white core. A near
   * black color glows purple around a black core, as Telegram iOS.
   * <p>
   * The glow is a blur, which hardware canvases only draw from Android 9; before that the screen gets a soft wide
   * stroke instead (the image that is sent is drawn in software and always has the real glow).
   */
  private void drawNeon(@NonNull Canvas canvas, int alpha) {
    if (neonPaint == null) {
      neonPaint = new Paint();
      neonPaint.setStyle(Paint.Style.STROKE);
      neonPaint.setAntiAlias(true);
      neonPaint.setStrokeCap(Paint.Cap.ROUND);
      neonPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    int     base = color | 0xFF000000;
    boolean dark = ColorUtils.calculateLuminance(base) < 0.02;
    int     glow = dark ? NEON_DARK_GLOW : base;
    int     core = dark ? Color.BLACK : Color.WHITE;
    int     body = dark ? glow : ColorUtils.blendARGB(base, Color.WHITE, 0.8f);

    float alphaFraction = alpha / 255f;

    neonPaint.setStrokeWidth(thickness * 2f);
    if (canBlur(canvas)) {
      if (neonGlow == null || neonGlowRadius != thickness) {
        neonGlowRadius = thickness;
        neonGlow       = new BlurMaskFilter(Math.max(1f, thickness), BlurMaskFilter.Blur.NORMAL);
      }
      neonPaint.setMaskFilter(neonGlow);
      neonPaint.setColor(ColorUtils.setAlphaComponent(glow, Math.round(255 * NEON_GLOW_ALPHA * alphaFraction)));
    } else {
      neonPaint.setMaskFilter(null);
      neonPaint.setColor(ColorUtils.setAlphaComponent(glow, Math.round(255 * 0.45f * alphaFraction)));
    }
    bezierLine.draw(canvas, neonPaint);

    neonPaint.setMaskFilter(null);
    neonPaint.setStrokeWidth(thickness);
    neonPaint.setColor(ColorUtils.setAlphaComponent(body, alpha));
    bezierLine.draw(canvas, neonPaint);

    neonPaint.setStrokeWidth(thickness * 0.5f);
    neonPaint.setColor(ColorUtils.setAlphaComponent(core, alpha));
    bezierLine.draw(canvas, neonPaint);
  }

  private static boolean canBlur(@NonNull Canvas canvas) {
    return Build.VERSION.SDK_INT >= 28 || !canvas.isHardwareAccelerated();
  }

  @Override
  public boolean hitTest(float x, float y) {
    return false;
  }

  public static final Creator<BezierDrawingRenderer> CREATOR = new Creator<BezierDrawingRenderer>() {
    @Override
    public BezierDrawingRenderer createFromParcel(Parcel in) {
      int        color      = in.readInt();
      float      thickness  = in.readFloat();
      Paint.Cap  cap        = Paint.Cap.values()[in.readInt()];
      AutomaticControlPointBezierLine bezierLine = in.readParcelable(AutomaticControlPointBezierLine.class.getClassLoader());
      RectF      clipRect   = in.readParcelable(RectF.class.getClassLoader());
      Style      style      = Style.fromOrdinal(in.readInt());
      long       sequence   = in.readLong();
      float      arrowHead  = in.readFloat();

      observeStrokeSequence(sequence);
      return new BezierDrawingRenderer(color, thickness, cap, bezierLine, clipRect, style, sequence, arrowHead);
    }

    @Override
    public BezierDrawingRenderer[] newArray(int size) {
      return new BezierDrawingRenderer[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(color);
    dest.writeFloat(thickness);
    dest.writeInt(cap.ordinal());
    dest.writeParcelable(bezierLine, flags);
    dest.writeParcelable(clipRect, flags);
    dest.writeInt(style.ordinal());
    dest.writeLong(strokeSequence);
    // A head that has started growing is a finished arrow; bring it back whole.
    dest.writeFloat(arrowHeadStarted ? 1f : 0f);
  }

}
