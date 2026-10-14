package org.signal.imageeditor.core.renderers;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Parcel;
import android.view.animation.Interpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.signal.core.util.DimensionUnit;
import org.signal.imageeditor.core.Bounds;
import org.signal.imageeditor.core.ColorableRenderer;
import org.signal.imageeditor.core.Renderer;
import org.signal.imageeditor.core.RendererContext;
import org.signal.imageeditor.core.SelectableRenderer;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Renders multiple lines of {@link #text} in ths specified {@link #color}.
 * <p>
 * Scales down the text size of long lines to fit inside the {@link Bounds} width.
 */
public final class MultiLineTextRenderer extends InvalidateableRenderer implements ColorableRenderer, SelectableRenderer {

  private static final float HIT_PADDING                  = DimensionUnit.DP.toPixels(30);
  private static final float HIGHLIGHT_HORIZONTAL_PADDING = DimensionUnit.DP.toPixels(8);
  private static final float HIGHLIGHT_TOP_PADDING        = DimensionUnit.DP.toPixels(10);
  private static final float HIGHLIGHT_BOTTOM_PADDING     = DimensionUnit.DP.toPixels(6);
  private static final float HIGHLIGHT_CORNER_RADIUS      = DimensionUnit.DP.toPixels(4);

  @NonNull
  private String text = "";

  private static final int PADDING = 10;

  @ColorInt
  private int color;

  private final Paint paint          = new Paint();
  private final Paint selectionPaint = new Paint();
  private final Paint modePaint      = new Paint();

  private final float textScale;

  private int     selStart;
  private int     selEnd;
  private boolean hasFocus;
  private Mode    mode;
  private Align   align = Align.CENTER;
  private Font    font  = Font.DEFAULT;
  /** The font the lines were last measured in; lines are measured with the paint, which only gets its typeface in render. */
  private @Nullable Font measuredFont;

  private List<Line> lines = emptyList();

  private ValueAnimator cursorAnimator;
  private float         cursorAnimatedValue;

  private final Matrix recommendedEditorMatrix = new Matrix();

  private final RectF textBounds = new RectF();

  public MultiLineTextRenderer(@Nullable String text, @ColorInt int color, @NonNull Mode mode) {
    this(text, color, mode, Align.CENTER);
  }

  public MultiLineTextRenderer(@Nullable String text, @ColorInt int color, @NonNull Mode mode, @NonNull Align align) {
    this(text, color, mode, align, Font.DEFAULT);
  }

  public MultiLineTextRenderer(@Nullable String text, @ColorInt int color, @NonNull Mode mode, @NonNull Align align, @NonNull Font font) {
    this.mode  = mode;
    this.align = align;
    this.font  = font;

    modePaint.setAntiAlias(true);
    modePaint.setTextSize(100);

    setColorInternal(color);

    float regularTextSize = paint.getTextSize();

    paint.setAntiAlias(true);
    paint.setTextSize(100);

    textScale = paint.getTextSize() / regularTextSize;

    selectionPaint.setAntiAlias(true);

    setText(text != null ? text : "");
    createLinesForText();
  }

  @Override
  public void render(@NonNull RendererContext rendererContext) {
    super.render(rendererContext);

    paint.setTypeface(rendererContext.typefaceProvider.getSelectedTypeface(rendererContext.context, this, rendererContext.invalidate));
    modePaint.setTypeface(rendererContext.typefaceProvider.getSelectedTypeface(rendererContext.context, this, rendererContext.invalidate));

    // Tellomi (tellomi/tellomi#1522 E-4): a font changes the width of every line, so measure again in the new typeface.
    if (measuredFont != font) {
      measuredFont = font;
      for (Line line : lines) {
        line.recalculate();
      }
      updateAlignment();
    }

    float height = 0;
    float width  = 0;
    for (Line line : lines) {
      line.render(rendererContext);
      height += line.heightInBounds - line.ascentInBounds + line.descentInBounds;
      width = Math.max(line.textBounds.width(), width);
    }

    textBounds.set(-width - PADDING, -PADDING, width + PADDING, height / 2f + PADDING);
  }

  @NonNull
  public String getText() {
    return text;
  }

  public void setText(@NonNull String text) {
    if (!this.text.equals(text)) {
      this.text = text;
      createLinesForText();
    }
  }

  public void nextMode() {
    setMode(mode.next());
  }

  public @NonNull Mode getMode() {
    return mode;
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-4, as Telegram's text alignment button): lines line up on the left, in the middle or on
   * the right of the widest line.
   */
  public @NonNull Align getAlign() {
    return align;
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-4, as Telegram's text font list): the font this text is drawn in. The typeface
   * providers ask for it, so the editor and the sent image use the same one.
   */
  public @NonNull Font getFont() {
    return font;
  }

  public void setFont(@NonNull Font font) {
    if (this.font != font) {
      this.font = font;
      invalidate();
    }
  }

  public void setAlign(@NonNull Align align) {
    if (this.align != align) {
      this.align = align;
      updateAlignment();
    }
  }

  /** How far line {@code index} is moved sideways for {@link #align}, in bounds units. */
  @VisibleForTesting
  float getLineAlignOffset(int index) {
    return lines.get(index).alignOffset;
  }

  /** Moves each line sideways so that it lines up with the widest one, per {@link #align}. */
  private void updateAlignment() {
    float widest = 0;
    for (Line line : lines) {
      widest = Math.max(widest, line.widthInBounds);
    }
    for (Line line : lines) {
      float offset;
      switch (align) {
        case LEFT:  offset = (line.widthInBounds - widest) / 2f; break;
        case RIGHT: offset = (widest - line.widthInBounds) / 2f; break;
        default:    offset = 0;
      }
      line.setAlignOffset(offset);
    }
  }

  /**
   * Post concats an additional matrix to the supplied matrix that scales and positions the editor
   * so that all the text is visible.
   *
   * @param matrix editor matrix, already zoomed and positioned to fit the regular bounds.
   */
  public void applyRecommendedEditorMatrix(@NonNull Matrix matrix) {
    recommendedEditorMatrix.reset();

    float scale = 1f;
    for (Line line : lines) {
      if (line.scale < scale) {
        scale = line.scale;
      }
    }

    float yOff = 0;
    for (Line line : lines) {
      if (line.containsSelectionEnd()) {
        break;
      } else {
        yOff -= line.heightInBounds;
      }
    }

    recommendedEditorMatrix.postTranslate(0, Bounds.TOP / 1.5f + yOff);

    recommendedEditorMatrix.postScale(scale, scale);

    matrix.postConcat(recommendedEditorMatrix);
  }

  private void createLinesForText() {
    String[] split = text.split("\n", -1);

    if (split.length == lines.size()) {
      for (int i = 0; i < split.length; i++) {
        lines.get(i).setText(split[i]);
      }
    } else {
      lines = new ArrayList<>(split.length);
      for (String s : split) {
        lines.add(new Line(s));
      }
    }
    setSelection(selStart, selEnd);
    updateAlignment();
  }

  private class Line {
    private final Matrix ascentMatrix            = new Matrix();
    private final Matrix descentMatrix           = new Matrix();
    private final Matrix projectionMatrix        = new Matrix();
    private final Matrix inverseProjectionMatrix = new Matrix();
    private final RectF  selectionBounds         = new RectF();
    private final RectF  textBounds              = new RectF();
    private final RectF  hitBounds               = new RectF();
    private final RectF  modeBounds              = new RectF();
    private final Path   outlinerPath            = new Path();

    private String text;
    private int    selStart;
    private int    selEnd;
    private float  ascentInBounds;
    private float  descentInBounds;
    private float  scale = 1f;
    private float  heightInBounds;
    private float  widthInBounds;
    private float  alignOffset;

    Line(String text) {
      this.text = text;
      recalculate();
    }

    private void recalculate() {
      RectF maxTextBounds = new RectF();
      Rect  temp          = new Rect();

      getTextBoundsWithoutTrim(text, 0, text.length(), temp);
      textBounds.set(temp);
      hitBounds.set(textBounds);

      hitBounds.left   -= HIT_PADDING;
      hitBounds.right  += HIT_PADDING;
      hitBounds.top    -= HIT_PADDING;
      hitBounds.bottom += HIT_PADDING;

      maxTextBounds.set(textBounds);
      float widthLimit = 150 * textScale;

      scale = 1f / Math.max(1, maxTextBounds.right / widthLimit);

      maxTextBounds.right = widthLimit;

      if (showSelectionOrCursor()) {
        Rect startTemp = new Rect();
        int startInString = Math.min(text.length(), Math.max(0, selStart));
        int endInString = Math.min(text.length(), Math.max(0, selEnd));
        String startText = this.text.substring(0, startInString);

        getTextBoundsWithoutTrim(startText, 0, startInString, startTemp);

        if (selStart != selEnd) {
          // selection
          getTextBoundsWithoutTrim(text, startInString, endInString, temp);
        } else {
          // cursor
          paint.getTextBounds("|", 0, 1, temp);
          int width = temp.width();

          temp.left  -= width;
          temp.right -= width;
        }

        temp.left  += startTemp.right;
        temp.right += startTemp.right;
        selectionBounds.set(temp);
      }

      projectionMatrix.setRectToRect(new RectF(maxTextBounds), Bounds.FULL_BOUNDS, Matrix.ScaleToFit.CENTER);
      removeTranslate(projectionMatrix);

      float[] pts = { 0, paint.ascent(), 0, paint.descent() };
      projectionMatrix.mapPoints(pts);
      ascentInBounds  = pts[1];
      descentInBounds = pts[3];
      heightInBounds  = descentInBounds - ascentInBounds;

      widthInBounds = projectionMatrix.mapRadius(textBounds.width() / 2f) * 2f;

      projectionMatrix.preTranslate(-textBounds.centerX(), 0);
      projectionMatrix.postTranslate(alignOffset, 0);
      projectionMatrix.invert(inverseProjectionMatrix);

      ascentMatrix.setTranslate(0, -ascentInBounds);
      descentMatrix.setTranslate(0, descentInBounds + HIGHLIGHT_TOP_PADDING + HIGHLIGHT_BOTTOM_PADDING);

      invalidate();
    }

    private void removeTranslate(Matrix matrix) {
      float[] values = new float[9];

      matrix.getValues(values);
      values[2] = 0;
      values[5] = 0;
      matrix.setValues(values);
    }

    private boolean showSelectionOrCursor() {
      return (selStart >= 0             || selEnd >= 0) &&
             (selStart <= text.length() || selEnd <= text.length());
    }

    private boolean containsSelectionEnd() {
      return (selEnd >= 0) &&
             (selEnd <= text.length());
    }

    private void getTextBoundsWithoutTrim(String text, int start, int end, Rect result) {
      Rect extra   = new Rect();
      Rect xBounds = new Rect();

      String cannotBeTrimmed = "x" + text.substring(Math.max(0, start), Math.min(text.length(), end)) + "x";

      paint.getTextBounds(cannotBeTrimmed, 0, cannotBeTrimmed.length(), extra);
      paint.getTextBounds("x", 0, 1, xBounds);
      result.set(extra);
      result.right -= 2 * xBounds.width();

      int temp = result.left;
      result.left  -= temp;
      result.right -= temp;
    }

    public boolean contains(float x, float y) {
      float[] dst = new float[2];

      inverseProjectionMatrix.mapPoints(dst, new float[]{ x, y });

      return hitBounds.contains(dst[0], dst[1]);
    }

    void setText(String text) {
      if (!this.text.equals(text)) {
        this.text = text;
        recalculate();
      }
    }

    void setAlignOffset(float alignOffset) {
      if (this.alignOffset != alignOffset) {
        this.alignOffset = alignOffset;
        recalculate();
      }
    }

    public void render(@NonNull RendererContext rendererContext) {
      // add our ascent for ourselves and the next lines
      rendererContext.canvasMatrix.concat(ascentMatrix);

      rendererContext.save();

      rendererContext.canvasMatrix.concat(projectionMatrix);

      if (mode == Mode.HIGHLIGHT || mode == Mode.SEMI) {
        if (text.isEmpty()) {
          modeBounds.setEmpty();
        } else {
          modeBounds.set(textBounds.left - HIGHLIGHT_HORIZONTAL_PADDING,
                         selectionBounds.top - HIGHLIGHT_TOP_PADDING,
                         textBounds.right + HIGHLIGHT_HORIZONTAL_PADDING,
                         selectionBounds.bottom + HIGHLIGHT_BOTTOM_PADDING);
        }
        int alpha = modePaint.getAlpha();
        modePaint.setAlpha(rendererContext.getAlpha(alpha));
        rendererContext.canvas.drawRoundRect(modeBounds, HIGHLIGHT_CORNER_RADIUS, HIGHLIGHT_CORNER_RADIUS, modePaint);
        modePaint.setAlpha(alpha);
      } else if (mode == Mode.UNDERLINE) {
        if (text.isEmpty()) {
          modeBounds.setEmpty();
        } else {
          modeBounds.set(textBounds.left, selectionBounds.top, textBounds.right, selectionBounds.bottom);
          modeBounds.inset(-DimensionUnit.DP.toPixels(2), -DimensionUnit.DP.toPixels(2));

          modeBounds.set(modeBounds.left,
                         Math.max(modeBounds.top, modeBounds.bottom - DimensionUnit.DP.toPixels(6)),
                         modeBounds.right,
                         modeBounds.bottom - DimensionUnit.DP.toPixels(2));
        }

        int alpha = modePaint.getAlpha();
        modePaint.setAlpha(rendererContext.getAlpha(alpha));
        rendererContext.canvas.drawRect(modeBounds, modePaint);
        modePaint.setAlpha(alpha);
      }

      if (hasFocus && showSelectionOrCursor()) {
        if (selStart == selEnd) {
          selectionPaint.setAlpha((int) (cursorAnimatedValue * 128));
        } else {
          selectionPaint.setAlpha(128);
        }
        rendererContext.canvas.drawRect(selectionBounds, selectionPaint);
      }

      int alpha = paint.getAlpha();
      paint.setAlpha(rendererContext.getAlpha(alpha));

      rendererContext.canvas.drawText(text, 0, 0, paint);

      paint.setAlpha(alpha);

      if (mode == Mode.OUTLINE) {
        int modeAlpha = modePaint.getAlpha();
        modePaint.setAlpha(rendererContext.getAlpha(alpha));

        if (Build.VERSION.SDK_INT >= 31) {
          outlinerPath.reset();
          modePaint.getTextPath(text, 0, text.length(), 0, 0, outlinerPath);
          outlinerPath.op(outlinerPath, Path.Op.INTERSECT);
          rendererContext.canvas.drawPath(outlinerPath, modePaint);
        } else {
          rendererContext.canvas.drawText(text, 0, 0, modePaint);
        }
        modePaint.setAlpha(modeAlpha);
      }

      rendererContext.restore();

      // add our descent for the next lines
      rendererContext.canvasMatrix.concat(descentMatrix);
    }

    void setSelection(int selStart, int selEnd) {
      if (selStart != this.selStart || selEnd != this.selEnd) {
        this.selStart = selStart;
        this.selEnd   = selEnd;
        recalculate();
      }
    }
  }

  @Override
  public int getColor() {
    return color;
  }

  @Override
  public void setColor(@ColorInt int color) {
    if (this.color != color) {
      setColorInternal(color);
    }
  }

  @Override
  public void onSelected(boolean selected) {
  }

  @Override
  public void getSelectionBounds(@NonNull RectF bounds) {
    bounds.set(textBounds);
  }

  @Override
  public boolean hitTest(float x, float y) {
    return textBounds.contains(x, y);
  }

  public void setSelection(int selStart, int selEnd) {
    this.selStart = selStart;
    this.selEnd = selEnd;
    for (Line line : lines) {
      line.setSelection(selStart, selEnd);

      int length = line.text.length() + 1; // one for new line

      selStart -= length;
      selEnd   -= length;
    }
  }

  public void setFocused(boolean hasFocus) {
    if (this.hasFocus != hasFocus) {
      this.hasFocus = hasFocus;
      if (cursorAnimator != null) {
        cursorAnimator.cancel();
        cursorAnimator = null;
      }
      if (hasFocus) {
        cursorAnimator = ValueAnimator.ofFloat(0, 1);
        cursorAnimator.setInterpolator(pulseInterpolator());
        cursorAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cursorAnimator.setDuration(1000);
        cursorAnimator.addUpdateListener(animation -> {
          cursorAnimatedValue = (float) animation.getAnimatedValue();
          invalidate();
        });
        cursorAnimator.start();
      } else {
        invalidate();
      }
    }
  }

  private void setMode(@NonNull Mode mode) {
    if (this.mode != mode) {
      this.mode = mode;
      setColorInternal(color);
    }
  }

  private void setColorInternal(@ColorInt int color) {
    this.color = color;

    if (mode == Mode.REGULAR || mode == Mode.SEMI) {
      paint.setColor(color);
      selectionPaint.setColor(color);
    } else {
      paint.setColor(Color.WHITE);
      selectionPaint.setColor(Color.WHITE);
    }

    if (mode == Mode.OUTLINE) {
      modePaint.setStrokeWidth(DimensionUnit.DP.toPixels(15) / 10f);
      modePaint.setStyle(Paint.Style.STROKE);
    } else {
      modePaint.setStyle(Paint.Style.FILL);
    }

    modePaint.setColor(mode == Mode.SEMI ? semiBackgroundColor(color) : color);
    invalidate();
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-4, as Telegram Android's semi-transparent text style): a 60% black frame behind light
   * text and a 60% white one behind dark text.
   */
  static @ColorInt int semiBackgroundColor(@ColorInt int textColor) {
    double luminance = (0.2126 * Color.red(textColor) + 0.7152 * Color.green(textColor) + 0.0722 * Color.blue(textColor)) / 255d;
    return luminance >= 0.25 ? 0x99000000 : 0x99ffffff;
  }

  public static final Creator<MultiLineTextRenderer> CREATOR = new Creator<MultiLineTextRenderer>() {
    @Override
    public MultiLineTextRenderer createFromParcel(Parcel in) {
      String text  = in.readString();
      int    color = in.readInt();
      Mode   mode  = Mode.fromCode(in.readInt());
      // Tellomi (tellomi/tellomi#1522 E-4): written last, so state saved before it existed still reads.
      Align  align = in.dataAvail() > 0 ? Align.fromCode(in.readInt()) : Align.CENTER;
      Font   font  = in.dataAvail() > 0 ? Font.fromCode(in.readInt()) : Font.DEFAULT;
      return new MultiLineTextRenderer(text, color, mode, align, font);
    }

    @Override
    public MultiLineTextRenderer[] newArray(int size) {
      return new MultiLineTextRenderer[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(text);
    dest.writeInt(color);
    dest.writeInt(mode.code);
    dest.writeInt(align.code);
    dest.writeInt(font.code);
  }

  private static Interpolator pulseInterpolator() {
    return input -> {
      input *= 5;
      if (input > 1) {
        input = 4 - input;
      }
      return Math.max(0, Math.min(1, input));
    };
  }

  public enum Mode {
    REGULAR(0),
    HIGHLIGHT(1),
    UNDERLINE(2),
    OUTLINE(3),
    /** Tellomi (tellomi/tellomi#1522 E-4): the text in its color on a translucent frame, as Telegram's semi style. */
    SEMI(4);

    /** The order the style toggle goes through; the new style sits after the solid frame it resembles. */
    private static final Mode[] CYCLE = { REGULAR, HIGHLIGHT, SEMI, UNDERLINE, OUTLINE };

    private final int code;

    Mode(int code) {
      this.code = code;
    }

    @NonNull Mode next() {
      for (int i = 0; i < CYCLE.length; i++) {
        if (CYCLE[i] == this) {
          return CYCLE[(i + 1) % CYCLE.length];
        }
      }
      return REGULAR;
    }

    private static Mode fromCode(int code) {
      for (final Mode value : Mode.values()) {
        if (value.code == code) {
          return value;
        }
      }

      return REGULAR;
    }
  }

  /** Tellomi (tellomi/tellomi#1522 E-4): text alignment, cycled left, center, right as Telegram does. */
  public enum Align {
    LEFT(0),
    CENTER(1),
    RIGHT(2);

    private final int code;

    Align(int code) {
      this.code = code;
    }

    public @NonNull Align next() {
      switch (this) {
        case LEFT:   return CENTER;
        case CENTER: return RIGHT;
        default:     return LEFT;
      }
    }

    private static Align fromCode(int code) {
      for (final Align value : Align.values()) {
        if (value.code == code) {
          return value;
        }
      }
      return CENTER;
    }
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-4): the fonts text can be drawn in. After the default come Telegram Android's built-in
   * text fonts ({@code PaintTypeface.BUILT_IN_FONTS}), as the Android system families closest to them; Merriweather is left
   * out because it would have to be bundled.
   */
  public enum Font {
    DEFAULT(0, null, Typeface.NORMAL),
    MEDIUM(1, "sans-serif-medium", Typeface.NORMAL),
    ITALIC(2, "sans-serif-medium", Typeface.ITALIC),
    SERIF(3, "serif", Typeface.BOLD),
    CONDENSED(4, "sans-serif-condensed", Typeface.BOLD),
    MONO(5, "monospace", Typeface.NORMAL);

    private final int              code;
    private final @Nullable String family;
    private final int              style;

    Font(int code, @Nullable String family, int style) {
      this.code   = code;
      this.family = family;
      this.style  = style;
    }

    /** The typeface for this font, or null for the provider's own default. */
    public @Nullable Typeface typeface() {
      return family != null ? Typeface.create(family, style) : null;
    }

    public @NonNull Font next() {
      Font[] values = values();
      return values[(ordinal() + 1) % values.length];
    }

    private static Font fromCode(int code) {
      for (final Font value : Font.values()) {
        if (value.code == code) {
          return value;
        }
      }
      return DEFAULT;
    }
  }

  /**
   * Tellomi (tellomi/tellomi#1522 E-4): the typeface a provider should use for {@code renderer}: its own font if it has
   * one, otherwise {@code fallback}.
   */
  public static @NonNull Typeface typefaceFor(@NonNull Renderer renderer, @NonNull Typeface fallback) {
    if (renderer instanceof MultiLineTextRenderer) {
      Typeface typeface = ((MultiLineTextRenderer) renderer).font.typeface();
      if (typeface != null) {
        return typeface;
      }
    }
    return fallback;
  }
}
