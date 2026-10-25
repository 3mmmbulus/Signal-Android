package org.thoughtcrime.securesms.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import org.signal.core.util.DimensionUnit;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.ViewUtil;

/**
 * View responsible for displaying the delivery status (NONE, PENDING, SENT, DELIVERED, READ) of a given outgoing message.
 * <p>
 * This view manipulates its start / end padding to properly place the corresponding icon, and also performs a rotation
 * animation on itself in the pending mode. Thus, users should be aware that padding values set in XML will be overwritten.
 * <p>
 * If you need to control the horizontal spacing of this view, utilize margins instead.
 */
public class DeliveryStatusView extends AppCompatImageView {

  private static final String STATE_KEY = "DeliveryStatusView.STATE";
  private static final String ROOT_KEY  = "DeliveryStatusView.ROOT";

  private final int horizontalPadding = (int) DimensionUnit.DP.toPixels(2);

  /** Tellomi：发送中时钟（12dp）在 tellomi_sending_18 画布里的中心横坐标。 */
  private final float sendingClockCenterX = DimensionUnit.DP.toPixels(6);

  private RotateAnimation rotationAnimation;

  private State state = State.NONE;

  /** Tellomi：文字消息发送中的转圈延后露出（#1183），换绑 / 换状态时一律先撤掉。 */
  private final Runnable showDelayedPending = this::showDelayedPending;

  /** Tellomi M7（#1186）：没调过 {@link #setMessageKey} 的地方（「信息」页、预览、故事回复）永远不动。 */
  private static final long NO_MESSAGE_KEY = Long.MIN_VALUE;

  private int                     iconColor        = Color.WHITE;
  private @Nullable Integer       readIconColor    = null;
  private long                    messageKey       = NO_MESSAGE_KEY;
  private boolean                 sameMessage      = false;
  private boolean                 liveSentChange   = false;
  private @Nullable ValueAnimator readGrowAnimator = null;

  public DeliveryStatusView(Context context) {
    this(context, null);
  }

  public DeliveryStatusView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public DeliveryStatusView(final Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    if (attrs != null) {
      TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DeliveryStatusView, 0, 0);
      setTint(typedArray.getColor(R.styleable.DeliveryStatusView_iconColor, getResources().getColor(R.color.core_white)));
      if (typedArray.hasValue(R.styleable.DeliveryStatusView_readIconColor)) {
        setReadTint(typedArray.getColor(R.styleable.DeliveryStatusView_readIconColor, 0));
      }
      typedArray.recycle();
    }

    setNone();
  }

  @Override
  protected void onRestoreInstanceState(Parcelable state) {
    if (state instanceof Bundle) {
      Bundle stateBundle = (Bundle) state;
      State  s           = State.fromCode(stateBundle.getInt(STATE_KEY, State.NONE.code));

      switch (s) {
        case NONE:
          setNone();
          break;
        case PENDING:
          setPending();
          break;
        case SENT:
          setSent();
          break;
        case DELIVERED:
          setDelivered();
          break;
        case READ:
          setRead();
          break;
      }

      Parcelable root = stateBundle.getParcelable(ROOT_KEY);
      super.onRestoreInstanceState(root);
    } else {
      super.onRestoreInstanceState(state);
    }
  }

  @Override
  protected @Nullable Parcelable onSaveInstanceState() {
    Parcelable root        = super.onSaveInstanceState();
    Bundle     stateBundle = new Bundle();

    stateBundle.putParcelable(ROOT_KEY, root);
    stateBundle.putInt(STATE_KEY, state.code);

    return stateBundle;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    if (state == State.PENDING && rotationAnimation == null && getVisibility() == View.VISIBLE) {
      // Tellomi：时钟画在 18dp 宽画布的左边 12dp（tellomi_sending_18），绕时钟中心转；图标不随 RTL 翻转，所以按左内边距算
      final float pivotXValue = getPaddingLeft() + sendingClockCenterX;

      final float pivotYValue = (h - getPaddingTop() - getPaddingBottom()) / 2f;

      rotationAnimation = new RotateAnimation(0, 360f,
                                              Animation.ABSOLUTE, pivotXValue,
                                              Animation.ABSOLUTE, pivotYValue);

      rotationAnimation.setInterpolator(new LinearInterpolator());
      rotationAnimation.setDuration(1500);
      rotationAnimation.setRepeatCount(Animation.INFINITE);

      startAnimation(rotationAnimation);
    }
  }

  @Override
  public void clearAnimation() {
    super.clearAnimation();
    rotationAnimation = null;
  }

  public void setNone() {
    removeCallbacks(showDelayedPending);
    changeState(State.NONE);
    clearAnimation();
    setVisibility(View.GONE);
    updateContentDescription();
  }

  public boolean isPending() {
    return state == State.PENDING;
  }

  public void setPending() {
    removeCallbacks(showDelayedPending);
    changeState(State.PENDING);
    setVisibility(View.VISIBLE);
    // Tellomi：和一勾 / 两勾同样 2dp 起始间距 + 18dp 画布 = 20dp，发送中变一勾时时间不跳
    ViewUtil.setPaddingStart(this, horizontalPadding);
    ViewUtil.setPaddingEnd(this, 0);
    setImageResource(R.drawable.tellomi_sending_18);
    updateContentDescription();
  }

  /**
   * Tellomi：发送中，但 {@code delayMs} 之后才露出转圈（文字消息的 2 秒规则，tellomi/tellomi#1183）。
   * 之前占着位置不显示（INVISIBLE），这样变成一个勾时时间不会跳。
   */
  public void setPending(long delayMs) {
    if (delayMs <= 0) {
      setPending();
      return;
    }

    removeCallbacks(showDelayedPending);
    changeState(State.PENDING);
    // 挂着动画的 View 即使 INVISIBLE 也会被父 View 画出来（ViewGroup.dispatchDraw），等待期间先摘掉，露出来时再转
    clearAnimation();
    setVisibility(View.INVISIBLE);
    // Tellomi：和一勾 / 两勾同样 2dp 起始间距 + 18dp 画布 = 20dp，发送中变一勾时时间不跳
    ViewUtil.setPaddingStart(this, horizontalPadding);
    ViewUtil.setPaddingEnd(this, 0);
    setImageResource(R.drawable.tellomi_sending_18);
    setContentDescription(null);
    postDelayed(showDelayedPending, delayMs);
  }

  private void showDelayedPending() {
    if (state != State.PENDING) {
      return;
    }

    setVisibility(View.VISIBLE);
    updateContentDescription();

    if (rotationAnimation == null && getWidth() > 0 && getHeight() > 0) {
      onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }
  }

  public void setSent() {
    removeCallbacks(showDelayedPending);
    TellomiMessageStatus.Animation animation = changeState(State.SENT);
    setVisibility(View.VISIBLE);
    ViewUtil.setPaddingStart(this, horizontalPadding);
    ViewUtil.setPaddingEnd(this, 0);
    clearAnimation();
    setImageResource(R.drawable.tellomi_check_18);
    applyTint();
    updateContentDescription();
    if (animation == TellomiMessageStatus.Animation.SENT) {
      fadeInSentCheck();
    }
  }

  public void setDelivered() {
    removeCallbacks(showDelayedPending);
    TellomiMessageStatus.Animation animation = changeState(State.DELIVERED);
    setVisibility(View.VISIBLE);
    ViewUtil.setPaddingStart(this, horizontalPadding);
    ViewUtil.setPaddingEnd(this, 0);
    clearAnimation();
    setImageResource(R.drawable.tellomi_check_18);
    applyTint();
    updateContentDescription();
    if (animation == TellomiMessageStatus.Animation.SENT) {
      fadeInSentCheck();
    }
  }

  public void setRead() {
    removeCallbacks(showDelayedPending);
    TellomiMessageStatus.Animation animation = changeState(State.READ);
    setVisibility(View.VISIBLE);
    ViewUtil.setPaddingStart(this, horizontalPadding);
    ViewUtil.setPaddingEnd(this, 0);
    clearAnimation();
    showReadChecks(animation == TellomiMessageStatus.Animation.READ);
    applyTint();
    updateContentDescription();
  }

  public void setTint(int color) {
    iconColor = color;
    applyTint();
  }

  /**
   * Tellomi M7（#1186）：两个勾（已读）用的颜色。聊天列表、「信息」页、没有底色的发出消息（贴纸、大表情）用强调色；
   * 有底色的发出气泡传 null，沿用时间颜色（强调色会和气泡同色看不见）。
   */
  public void setReadTint(@Nullable Integer color) {
    readIconColor = color;
    applyTint();
  }

  /**
   * Tellomi M7（#1186）：这个 View 现在画的是哪条消息。只有同一条消息留在屏上时看到的状态变化才动；
   * 换成另一条（打开会话、滚动 / 回翻换绑、列表行换了最后一条）不动。每次绑定都在 set*() 之前调。
   */
  public void setMessageKey(long key) {
    sameMessage = key != NO_MESSAGE_KEY && key == messageKey;
    messageKey  = key;
  }

  /** Tellomi M7（#1186）：上一次 set*() 是不是同一条消息在屏上从发送中变成一个勾（新手提示用；读一次就清掉）。 */
  public boolean consumeLiveSentChange() {
    boolean result = liveSentChange;
    liveSentChange = false;
    return result;
  }

  private @Nullable TellomiMessageStatus.Animation changeState(@NonNull State next) {
    TellomiMessageStatus.Animation animation = TellomiMessageStatus.statusAnimation(state.display, next.display, sameMessage);

    sameMessage    = messageKey != NO_MESSAGE_KEY;
    liveSentChange = animation == TellomiMessageStatus.Animation.SENT;
    state          = next;

    cancelTellomiAnimations();
    applyTint();

    return animationsEnabled() ? animation : null;
  }

  private void applyTint() {
    int color = (state == State.READ && readIconColor != null) ? readIconColor : iconColor;
    setColorFilter(color, PorterDuff.Mode.SRC_IN);
  }

  private void cancelTellomiAnimations() {
    if (readGrowAnimator != null) {
      readGrowAnimator.cancel();
      readGrowAnimator = null;
    }
    animate().cancel();
    setAlpha(1f);
  }

  private void fadeInSentCheck() {
    setAlpha(0f);
    animate().alpha(1f).setDuration(TellomiMessageStatus.SENT_FADE_IN_MS).start();
  }

  /** 两个勾；{@code grow} 时第二个勾从第一个勾处向右长出（按宽度裁，勾永远从左往右画，RTL 也一样）。 */
  private void showReadChecks(boolean grow) {
    if (!grow) {
      setImageResource(R.drawable.tellomi_double_check_18);
      return;
    }

    Drawable checks = ContextCompat.getDrawable(getContext(), R.drawable.tellomi_double_check_18);
    if (checks == null) {
      setImageResource(R.drawable.tellomi_double_check_18);
      return;
    }

    ClipDrawable clip = new ClipDrawable(checks.mutate(), Gravity.LEFT, ClipDrawable.HORIZONTAL);
    clip.setLevel(TellomiMessageStatus.READ_GROW_START_LEVEL);
    setImageDrawable(clip);

    ValueAnimator animator = ValueAnimator.ofInt(TellomiMessageStatus.READ_GROW_START_LEVEL, 10_000);
    animator.setDuration(TellomiMessageStatus.READ_GROW_MS);
    animator.addUpdateListener(a -> {
      clip.setLevel((Integer) a.getAnimatedValue());
      invalidate();
    });
    readGrowAnimator = animator;
    animator.start();
  }

  /** 减少动态：系统「动画时长缩放」设为 0（开发者选项 / 无障碍里的移除动画）时一律不动。 */
  private boolean animationsEnabled() {
    float scale = Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    return scale != 0f;
  }

  private void updateContentDescription() {
    if (state.contentDescription == -1) {
      setContentDescription(null);
    } else {
      setContentDescription(getContext().getString(state.contentDescription));
    }
  }

  private enum State {
    NONE(0, -1, null),
    PENDING(1, R.string.message_details_recipient_header__pending_send, TellomiMessageStatus.Display.SENDING),
    SENT(2, R.string.message_details_header_sent, TellomiMessageStatus.Display.SENT),
    DELIVERED(3, R.string.conversation_item_sent__delivered_description, TellomiMessageStatus.Display.SENT),
    READ(4, R.string.conversation_item_sent__message_read, TellomiMessageStatus.Display.READ);

    final int code;

    @StringRes
    final int contentDescription;

    @Nullable
    final TellomiMessageStatus.Display display;

    State(int code, @StringRes int contentDescription, @Nullable TellomiMessageStatus.Display display) {
      this.code               = code;
      this.contentDescription = contentDescription;
      this.display            = display;
    }

    static State fromCode(int code) {
      for (State state : State.values()) {
        if (state.code == code) {
          return state;
        }
      }

      return NONE;
    }
  }
}
