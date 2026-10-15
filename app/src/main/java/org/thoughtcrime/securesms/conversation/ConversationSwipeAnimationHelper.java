package org.thoughtcrime.securesms.conversation;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement;
import org.signal.core.util.Util;

import java.util.List;

final class ConversationSwipeAnimationHelper {

  /** Tellomi（tellomi/tellomi#1109，ADR-0058 §2）：过阈值后的橡皮筋范围与整体上限；阈值本身按消息方向取，见 {@link TellomiSwipeToReply}。 */
  private static final float RUBBER_BAND_RANGE = dpToPx((int) TellomiSwipeToReply.RUBBER_BAND_RANGE_DP);
  private static final float MAX_DX            = dpToPx((int) TellomiSwipeToReply.MAX_OFFSET_DP);

  private static final float REPLY_SCALE_OVERSHOOT          = 1.8f;
  private static final float REPLY_SCALE_MAX                = 1.2f;
  private static final float REPLY_SCALE_MIN                = 1f;
  private static final long  REPLY_SCALE_OVERSHOOT_DURATION = 200;

  private static final Interpolator REPLY_ALPHA_INTERPOLATOR      = new ClampingLinearInterpolator(0f, 1f, 1f);
  private static final Interpolator REPLY_TRANSITION_INTERPOLATOR = new ClampingLinearInterpolator(0f, dpToPx(10));
  private static final Interpolator AVATAR_INTERPOLATOR           = new ClampingLinearInterpolator(0f, dpToPx(8));
  private static final Interpolator REPLY_SCALE_INTERPOLATOR      = new ClampingLinearInterpolator(REPLY_SCALE_MIN, REPLY_SCALE_MAX);
  private static final Interpolator QUOTED_ALPHA_INTERPOLATOR     = new ClampingLinearInterpolator(1f, 0f, 3f);

  private ConversationSwipeAnimationHelper() {
  }

  public static void update(@NonNull InteractiveConversationElement interactiveConversationElement, float dx, float sign) {
    float triggerDx    = ConversationItemSwipeCallback.triggerDx(interactiveConversationElement);
    float bubbleOffset = TellomiSwipeToReply.bubbleOffset(dx, triggerDx, RUBBER_BAND_RANGE, MAX_DX);

    apply(interactiveConversationElement, dx / triggerDx, bubbleOffset, dx < triggerDx, sign);
  }

  /**
   * Tellomi（交互审计 A-10）：松手回位的弹簧走在气泡位移上，按气泡位移画。阈值以内气泡和手指 1:1，阈值以外图标等都已到头，
   * 所以和 {@link #update} 画出来一样；弹簧越过原位时（负值）气泡往反方向多走一点，图标保持隐藏。
   */
  static void updateReturning(@NonNull InteractiveConversationElement interactiveConversationElement, float bubbleOffset, float sign) {
    float triggerDx = ConversationItemSwipeCallback.triggerDx(interactiveConversationElement);

    apply(interactiveConversationElement, bubbleOffset / triggerDx, bubbleOffset, bubbleOffset < triggerDx, sign);
  }

  /** Tellomi（交互审计 A-10）：手指在 {@code dx} 处时气泡的位移。 */
  static float bubbleOffset(@NonNull InteractiveConversationElement interactiveConversationElement, float dx) {
    return TellomiSwipeToReply.bubbleOffset(dx, ConversationItemSwipeCallback.triggerDx(interactiveConversationElement), RUBBER_BAND_RANGE, MAX_DX);
  }

  /** Tellomi（交互审计 A-10）：手指在 {@code dx} 处以 {@code fingerVelocity} 移动时气泡的速度。 */
  static float bubbleVelocity(@NonNull InteractiveConversationElement interactiveConversationElement, float dx, float fingerVelocity) {
    return TellomiSwipeToReply.bubbleVelocity(dx, fingerVelocity, ConversationItemSwipeCallback.triggerDx(interactiveConversationElement), RUBBER_BAND_RANGE, MAX_DX);
  }

  private static void apply(@NonNull InteractiveConversationElement interactiveConversationElement,
                            float progress,
                            float bubbleOffset,
                            boolean beforeTrigger,
                            float sign)
  {
    updateBodyBubbleTransition(interactiveConversationElement.getBubbleViews(), bubbleOffset, sign);
    updateReactionsTransition(interactiveConversationElement.getReactionsView(), bubbleOffset, sign);
    updateQuotedIndicatorTransition(interactiveConversationElement.getQuotedIndicatorView(), bubbleOffset, progress, sign);
    updateReplyIconTransition(interactiveConversationElement.getReplyView(), beforeTrigger, progress, sign);
    updateContactPhotoHolderTransition(interactiveConversationElement.getContactPhotoHolderView(), progress, sign);
    updateContactPhotoHolderTransition(interactiveConversationElement.getBadgeImageView(), progress, sign);
  }

  public static void trigger(@NonNull InteractiveConversationElement interactiveConversationElement) {
    triggerReplyIcon(interactiveConversationElement.getReplyView());
  }

  private static void updateBodyBubbleTransition(@NonNull List<View> bubbleViews, float bubbleOffset, float sign) {
    for (View view : bubbleViews) {
      view.setTranslationX(bubbleOffset * sign);
    }
  }

  private static void updateReactionsTransition(@NonNull View reactionsContainer, float bubbleOffset, float sign) {
    reactionsContainer.setTranslationX(bubbleOffset * sign);
  }

  private static void updateQuotedIndicatorTransition(@Nullable View quotedIndicator, float bubbleOffset, float progress, float sign) {
    if (quotedIndicator != null) {
      quotedIndicator.setTranslationX(bubbleOffset * sign);
      quotedIndicator.setAlpha(QUOTED_ALPHA_INTERPOLATOR.getInterpolation(progress));
    }
  }

  private static void updateReplyIconTransition(@NonNull View replyIcon, boolean beforeTrigger, float progress, float sign) {
    if (progress > 0.05f) {
      replyIcon.setAlpha(REPLY_ALPHA_INTERPOLATOR.getInterpolation(progress));
    } else replyIcon.setAlpha(0f);

    replyIcon.setTranslationX(REPLY_TRANSITION_INTERPOLATOR.getInterpolation(progress) * sign);

    if (beforeTrigger) {
      float scale = REPLY_SCALE_INTERPOLATOR.getInterpolation(progress);
      replyIcon.setScaleX(scale);
      replyIcon.setScaleY(scale);
    }
  }

  private static void updateContactPhotoHolderTransition(@Nullable View contactPhotoHolder,
                                                         float progress,
                                                         float sign)
  {
    if (contactPhotoHolder == null) return;
    contactPhotoHolder.setTranslationX(AVATAR_INTERPOLATOR.getInterpolation(progress) * sign);
  }

  private static void triggerReplyIcon(@NonNull View replyIcon) {
    ValueAnimator animator = ValueAnimator.ofFloat(REPLY_SCALE_MAX, REPLY_SCALE_OVERSHOOT, REPLY_SCALE_MAX);
    animator.setDuration(REPLY_SCALE_OVERSHOOT_DURATION);
    animator.addUpdateListener(animation -> {
      replyIcon.setScaleX((float) animation.getAnimatedValue());
      replyIcon.setScaleY((float) animation.getAnimatedValue());
    });
    animator.start();
  }

  private static int dpToPx(int dp) {
    return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
  }

  private static final class ClampingLinearInterpolator implements Interpolator {

    private final float slope;
    private final float yIntercept;
    private final float max;
    private final float min;

    ClampingLinearInterpolator(float start, float end) {
      this(start, end, 1.0f);
    }

    ClampingLinearInterpolator(float start, float end, float scale) {
      slope      = (end - start) * scale;
      yIntercept = start;
      max        = Math.max(start, end);
      min        = Math.min(start, end);
    }

    @Override
    public float getInterpolation(float input) {
      return Util.clamp(slope * input + yIntercept, min, max);
    }
  }

}
