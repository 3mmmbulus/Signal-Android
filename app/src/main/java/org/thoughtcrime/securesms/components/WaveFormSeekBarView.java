package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.appcompat.widget.AppCompatSeekBar;

import org.thoughtcrime.securesms.R;

import java.util.Arrays;

public final class WaveFormSeekBarView extends AppCompatSeekBar {

  private static final int ANIM_DURATION             = 450;
  private static final int ANIM_BAR_OFF_SET_DURATION =  12;

  private final Interpolator overshoot        = new OvershootInterpolator();
  private final Paint        paint            = new Paint();
  private       float[]      data             = new float[0];
  private       long         dataSetTime;
  private       Drawable     progressDrawable;
  private       boolean      waveMode;
  
  @ColorInt private int playedBarColor   = 0xffffffff;
  @ColorInt private int unplayedBarColor = 0x7fffffff;
  @Px       private int barWidth;

  public WaveFormSeekBarView(Context context) {
    super(context);
    init();
  }

  public WaveFormSeekBarView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public WaveFormSeekBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setWillNotDraw(false);
    
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setAntiAlias(true);

    progressDrawable = super.getProgressDrawable();

    if (isInEditMode()) {
      setWaveData(sinusoidalExampleData());
      dataSetTime = 0;
    }

    barWidth = getResources().getDimensionPixelSize(R.dimen.wave_form_bar_width);
  }

  public void setColors(@ColorInt int playedBarColor, @ColorInt int unplayedBarColor, @ColorInt int thumbTint) {
    this.playedBarColor   = playedBarColor;
    this.unplayedBarColor = unplayedBarColor;

    getThumb().setColorFilter(thumbTint, PorterDuff.Mode.SRC_IN);

    invalidate();
  }

  @Override
  public void setProgressDrawable(Drawable progressDrawable) {
    this.progressDrawable = progressDrawable;
    if (!waveMode) {
      super.setProgressDrawable(progressDrawable);
    }
  }

  @Override
  public Drawable getProgressDrawable() {
    return progressDrawable;
  }

  public void setWaveData(@NonNull float[] data) {
    if (!Arrays.equals(data, this.data)) {
      this.data        = data;
      this.dataSetTime = System.currentTimeMillis();
    }
    setWaveMode(data.length > 0);
  }

  public void setWaveMode(boolean waveMode) {
    this.waveMode = waveMode;
    super.setProgressDrawable(this.waveMode ? null : progressDrawable);
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (waveMode) {
      drawWave(canvas);
    }
    super.onDraw(canvas);
  }

  private void drawWave(Canvas canvas) {
    paint.setStrokeWidth(barWidth);

    int   usableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
    int   usableWidth  = getWidth() - getPaddingLeft() - getPaddingRight();
    float midpoint     = usableHeight / 2f;
    float maxHeight    = usableHeight / 2f - barWidth;
    float barGap       = (usableWidth - data.length * barWidth) / (float) (data.length - 1);

    boolean hasMoreFrames = false;

    canvas.save();
    canvas.translate(getPaddingLeft(), getPaddingTop());

    if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
      canvas.scale(-1, 1, usableWidth / 2f, usableHeight / 2f);
    }

    // Tellomi（交互审计 A-18「已播进度按整根柱子跳」）：先整条画成未播的颜色，再裁到播放位置（按像素）用已播的颜色画一遍，
    // 正好在播放位置上的那根柱子一半一半，进度连续地走、拖动时跟着拇指。机制同 Telegram 安卓 SeekBarWaveform.drawFill。
    // 裁切在 RTL 镜像之后，所以 RTL 时已播的那段在右边。
    float playedX = getMax() > 0 ? usableWidth * Math.max(0f, Math.min(1f, getProgress() / (float) getMax())) : 0f;

    paint.setColor(unplayedBarColor);
    hasMoreFrames |= drawBars(canvas, midpoint, maxHeight, barGap);

    if (playedX > 0f) {
      canvas.save();
      canvas.clipRect(0f, 0f, playedX, usableHeight);
      paint.setColor(playedBarColor);
      drawBars(canvas, midpoint, maxHeight, barGap);
      canvas.restore();
    }

    canvas.restore();

    if (hasMoreFrames) {
      invalidate();
    }
  }

  /** 画所有柱子（含刚设数据时的逐根长出动画）；还有柱子没长完时返回 true。 */
  private boolean drawBars(Canvas canvas, float midpoint, float maxHeight, float barGap) {
    boolean hasMoreFrames = false;
    long    now           = System.currentTimeMillis();

    for (int bar = 0; bar < data.length; bar++) {
      float x = bar * (barWidth + barGap) + barWidth / 2f;
      float y = data[bar] * maxHeight;

      long  time             = now - bar * ANIM_BAR_OFF_SET_DURATION - dataSetTime;
      float timeX            = Math.max(0, Math.min(1, time / (float) ANIM_DURATION));
      float interpolatedTime = overshoot.getInterpolation(timeX);
      float interpolatedY    = y * interpolatedTime;

      canvas.drawLine(x, midpoint - interpolatedY, x, midpoint + interpolatedY, paint);

      if (time < ANIM_DURATION) {
        hasMoreFrames = true;
      }
    }

    return hasMoreFrames;
  }

  private static float[] sinusoidalExampleData() {
    float[] data = new float[21];
    for (int i = 0; i < data.length; i++) {
      data[i] = (float) Math.sin(i / (float) (data.length - 1) * 2 * Math.PI);
    }
    return data;
  }
}
