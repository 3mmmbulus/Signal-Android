package org.thoughtcrime.securesms.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.SimpleColorFilter;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.ViewUtil;

public final class ConversationScrollToView extends FrameLayout {

  private final TextView  unreadCount;
  private final ImageView scrollButton;

  // Tellomi（交互审计 A-14「快速进出可能停在隐藏」）：原来出场是 50 ms 的 View 动画、播完才设 INVISIBLE，
  // 这期间再要显示时 animateIn 看到还是 VISIBLE 就直接 return，出场播完按钮就停在隐藏。改成一个可中途反向的进度：
  // 从当前进度往目标走，透明度、缩放（0.7 → 1）、上移一起跟着，走到 0 才设 INVISIBLE。
  // 机制同 Telegram 安卓 ChatActivitySideControlsButtonsLayout 的 visibilityAnimator（280 ms 减速，可反向）。
  private static final long  VISIBILITY_DURATION_MS = 280;
  private static final float HIDDEN_SCALE           = 0.7f;
  private static final float BADGE_POP_SCALE        = 1.15f;
  private static final long  BADGE_DURATION_MS      = 180;

  private final ValueAnimator visibilityAnimator = ValueAnimator.ofFloat(0f, 1f);
  private final float         hiddenOffsetY;

  private float   visibility;
  private boolean shown;
  private int     lastUnreadCount;

  public ConversationScrollToView(@NonNull Context context) {
    this(context, null);
  }

  public ConversationScrollToView(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ConversationScrollToView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    inflate(context, R.layout.conversation_scroll_to, this);

    unreadCount  = findViewById(R.id.conversation_scroll_to_count);
    scrollButton = findViewById(R.id.conversation_scroll_to_button);

    if (attrs != null && !isInEditMode()) {
      TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.ConversationScrollToView);
      int        srcId = array.getResourceId(R.styleable.ConversationScrollToView_cstv_scroll_button_src, 0);

      scrollButton.setImageResource(srcId);

      array.recycle();
    }

    hiddenOffsetY = ViewUtil.dpToPx(16);

    visibilityAnimator.setInterpolator(new DecelerateInterpolator());
    visibilityAnimator.addUpdateListener(animation -> {
      visibility = (float) animation.getAnimatedValue();
      applyVisibility();
    });
    visibilityAnimator.addListener(new AnimatorListenerAdapter() {
      private boolean canceled;

      @Override
      public void onAnimationStart(Animator animation) {
        canceled = false;
      }

      @Override
      public void onAnimationCancel(Animator animation) {
        canceled = true;
      }

      @Override
      public void onAnimationEnd(Animator animation) {
        if (!canceled && !shown) {
          setVisibility(INVISIBLE);
        }
      }
    });
  }

  public void setShown(boolean isShown) {
    float target = isShown ? 1f : 0f;

    if (!visibilityAnimator.isRunning()) {
      // 布局里一开始是 INVISIBLE；别处直接改过可见性时也从实际状态出发。
      visibility = getVisibility() == VISIBLE ? visibility : 0f;
      if (shown == isShown && visibility == target) {
        return;
      }
    } else if (shown == isShown) {
      return;
    }

    shown = isShown;
    visibilityAnimator.cancel();

    if (isShown) {
      setVisibility(VISIBLE);
    }

    visibilityAnimator.setFloatValues(visibility, target);
    visibilityAnimator.setDuration(Math.max(1, (long) (VISIBILITY_DURATION_MS * Math.abs(target - visibility))));
    visibilityAnimator.start();
  }

  private void applyVisibility() {
    float scale = HIDDEN_SCALE + (1f - HIDDEN_SCALE) * visibility;
    setAlpha(visibility);
    setScaleX(scale);
    setScaleY(scale);
    setTranslationY(hiddenOffsetY * (1f - visibility));
  }

  public void setWallpaperEnabled(boolean hasWallpaper) {
    if (hasWallpaper) {
      scrollButton.setBackgroundResource(R.drawable.scroll_to_bottom_background_wallpaper);
    } else {
      scrollButton.setBackgroundResource(R.drawable.scroll_to_bottom_background_normal);
    }
  }

  public void setUnreadCountBackgroundTint(@ColorInt int tint) {
    unreadCount.getBackground().setColorFilter(new SimpleColorFilter(tint));
  }

  @Override
  public void setOnClickListener(@Nullable OnClickListener l) {
    scrollButton.setOnClickListener(l);
  }

  public void setUnreadCount(int unreadCount) {
    int previous = lastUnreadCount;
    lastUnreadCount = unreadCount;

    // Tellomi（交互审计 A-14）：按钮看得见时，未读数出现 / 消失 / 变化都有交代（弹出、缩回、轻弹一下），不再只换字。
    boolean animate = shown && getVisibility() == VISIBLE && previous != unreadCount;
    TextView badge  = this.unreadCount;

    if (unreadCount > 0) {
      badge.setText(formatUnreadCount(unreadCount));
    }

    if (!animate) {
      badge.animate().cancel();
      badge.setScaleX(1f);
      badge.setScaleY(1f);
      badge.setAlpha(1f);
      badge.setVisibility(unreadCount > 0 ? VISIBLE : GONE);
      return;
    }

    badge.animate().cancel();
    badge.animate().setListener(null);

    if (previous == 0) {
      badge.setVisibility(VISIBLE);
      badge.setAlpha(0f);
      badge.setScaleX(HIDDEN_SCALE);
      badge.setScaleY(HIDDEN_SCALE);
      badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(BADGE_DURATION_MS).setInterpolator(new DecelerateInterpolator()).start();
    } else if (unreadCount == 0) {
      badge.animate().alpha(0f).scaleX(HIDDEN_SCALE).scaleY(HIDDEN_SCALE).setDuration(BADGE_DURATION_MS).setInterpolator(new DecelerateInterpolator())
           .withEndAction(() -> {
             if (lastUnreadCount == 0) {
               badge.setVisibility(GONE);
             }
             badge.setAlpha(1f);
             badge.setScaleX(1f);
             badge.setScaleY(1f);
           })
           .start();
    } else {
      badge.setVisibility(VISIBLE);
      badge.setAlpha(1f);
      badge.setScaleX(BADGE_POP_SCALE);
      badge.setScaleY(BADGE_POP_SCALE);
      badge.animate().scaleX(1f).scaleY(1f).setDuration(BADGE_DURATION_MS).setInterpolator(new DecelerateInterpolator()).start();
    }
  }

  private @NonNull CharSequence formatUnreadCount(int unreadCount) {
    return unreadCount > 999 ? "999+" : String.valueOf(unreadCount);
  }
}
