package org.thoughtcrime.securesms.conversation;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.signal.core.ui.motion.TellomiHaptics;
import org.signal.core.ui.motion.TellomiMotion;
import org.thoughtcrime.securesms.conversation.v2.items.InteractiveConversationElement;
import org.signal.core.util.AccessibilityUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ConversationItemSwipeCallback extends ItemTouchHelper.SimpleCallback {

  /** Tellomi（A-10）：松手监听装上时这次滑动还没画过。 */
  private static final float NOT_DRAWN = -1f;

  private boolean swipeBack;
  private boolean canTriggerSwipe;
  private float   latestDownX;
  private float   latestDownY;

  // Tellomi（交互审计 A-10，标准第四节）：越过 / 退回阈值的状态、松手速度、按下点是否在系统返回手势的边缘区、松手回位的弹簧。
  private boolean                    armed;
  private boolean                    swipeActive;
  private boolean                    downInGestureEdge;
  private @Nullable VelocityTracker  velocityTracker;
  private @Nullable RecyclerView     attachedRecyclerView;

  private final Map<RecyclerView.ViewHolder, SpringAnimation> returnSprings = new HashMap<>();

  /**
   * Tellomi（A-10）：从按下起就记手指的移动。ItemTouchHelper 接管前的事件只经过这里（排在它前面），接管后的事件由松手监听接着记，
   * 两段不重叠；一甩很快时接管后只剩一两个事件，只靠后一段算不出速度。
   */
  private final RecyclerView.OnItemTouchListener velocitySampler = new RecyclerView.SimpleOnItemTouchListener() {
    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent event) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && velocityTracker != null) {
        velocityTracker.clear();
      }
      addVelocitySample(event);
      return false;
    }
  };

  private final SwipeAvailabilityProvider     swipeAvailabilityProvider;
  private final ConversationItemTouchListener itemTouchListener;
  private final OnSwipeListener               onSwipeListener;

  public ConversationItemSwipeCallback(@NonNull SwipeAvailabilityProvider swipeAvailabilityProvider,
                                       @NonNull OnSwipeListener onSwipeListener)
  {
    // Tellomi（tellomi/tellomi#1109）：回复改成手指从右往左滑（Telegram 基线；RTL 下 START 自动镜像成从左往右）。
    super(0, ItemTouchHelper.START);
    this.itemTouchListener          = new ConversationItemTouchListener(this::updateLatestDownCoordinate);
    this.swipeAvailabilityProvider  = swipeAvailabilityProvider;
    this.onSwipeListener            = onSwipeListener;
    this.canTriggerSwipe            = true;
  }

  public void attachToRecyclerView(@NonNull RecyclerView recyclerView) {
    this.attachedRecyclerView = recyclerView;
    recyclerView.addOnItemTouchListener(itemTouchListener);
    recyclerView.addOnItemTouchListener(velocitySampler);
    new ItemTouchHelper(this).attachToRecyclerView(recyclerView);
  }

  @Override
  public boolean onMove(@NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        @NonNull RecyclerView.ViewHolder target)
  {
    return false;
  }

  @Override
  public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
  }

  @Override
  public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                          @NonNull RecyclerView.ViewHolder viewHolder)
  {
    if (cannotSwipeViewHolder(viewHolder)) return 0;
    return super.getSwipeDirs(recyclerView, viewHolder);
  }

  /**
   * Tellomi（A-10）：一甩很快时，ItemTouchHelper 接管滑动后还没画出一帧手指就抬起了，原来在 onChildDraw 里装的松手监听收不到这次松手。
   * 接管当下就装上（还没画过，位移记 {@link #NOT_DRAWN}），这一甩照样按投射落点判定。
   * ItemTouchHelper 放开这次滑动时（IDLE，在松手监听之后）清掉这次滑动的状态：没装上监听的滑动（刚回复完的 250 ms 内）
   * 不能把「在滑」留给之后一次滚动的松手。
   */
  @Override
  public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
    super.onSelectedChanged(viewHolder, actionState);
    RecyclerView recyclerView = attachedRecyclerView;
    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder != null && recyclerView != null && canTriggerSwipe && !cannotSwipeViewHolder(viewHolder)) {
      swipeActive = true;
      setTouchListener(recyclerView, viewHolder, NOT_DRAWN);
    } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
      swipeActive = false;
      armed       = false;
    }
  }

  @Override
  public int convertToAbsoluteDirection(int flags, int layoutDirection) {
    if (swipeBack) {
      swipeBack = false;
      return 0;
    }
    return super.convertToAbsoluteDirection(flags, layoutDirection);
  }

  @Override
  public void onChildDraw(
          @NonNull Canvas c,
          @NonNull RecyclerView recyclerView,
          @NonNull RecyclerView.ViewHolder viewHolder,
          float dx, float dy, int actionState, boolean isCurrentlyActive)
  {
    if (cannotSwipeViewHolder(viewHolder)) return;

    if (dx == 0) {
      canTriggerSwipe = true;
    }

    // Tellomi（A-10）：松手后这条气泡由回位弹簧画，ItemTouchHelper 自己的 250 ms 补间不画；手指又按住它就交还给手指。
    SpringAnimation returning = returnSprings.get(viewHolder);
    if (returning != null) {
      if (!isCurrentlyActive) return;
      returnSprings.remove(viewHolder);
      returning.cancel();
    }

    float   sign              = getSignFromDirection(viewHolder.itemView);
    boolean isCorrectSwipeDir = sameSign(dx, sign);

    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && isCorrectSwipeDir) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), Math.abs(dx), sign);
      recyclerView.invalidate();
      if (isCurrentlyActive) {
        swipeActive = true;
        handleSwipeFeedback(viewHolder.itemView, requireInteractiveConversationElement(viewHolder), Math.abs(dx));
      }
      if (canTriggerSwipe) {
        setTouchListener(recyclerView, viewHolder, Math.abs(dx));
      }
    } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE || dx == 0) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder), 0, 1);
      recyclerView.invalidate();
    }
  }

  /** Tellomi（A-10，标准第四节「阈值要有反馈」）：越过阈值给一次触感、图标弹一下；退回阈值以内再给一次。不连发。 */
  private void handleSwipeFeedback(@NonNull View itemView, @NonNull InteractiveConversationElement interactiveConversationElement, float dx) {
    boolean pastTrigger = dx > triggerDx(interactiveConversationElement);
    if (pastTrigger == armed) return;

    armed = pastTrigger;
    TellomiHaptics.INSTANCE.perform(itemView, armed ? TellomiHaptics.Event.THRESHOLD_ACTIVATE : TellomiHaptics.Event.THRESHOLD_DEACTIVATE);
    if (armed) {
      ConversationSwipeAnimationHelper.trigger(interactiveConversationElement);
    }
  }

  private void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (cannotSwipeViewHolder(viewHolder)) return;

    InteractiveConversationElement element = requireInteractiveConversationElement(viewHolder);

    onSwipeListener.onSwipe(element.getConversationMessage());
  }

  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener(@NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder,
                                float dx)
  {
    recyclerView.setOnTouchListener((v, event) -> {
      addVelocitySample(event);
      switch (event.getAction()) {
        case MotionEvent.ACTION_UP:
          float velocity = replyVelocity(viewHolder);
          // Tellomi（A-10）：还没画过就松手时，按这次松手的位置算位移（ItemTouchHelper 的位移也是从按下点量的）。
          float releaseDx = dx != NOT_DRAWN ? dx : Math.max(0f, (event.getRawX() - latestDownX) * getSignFromDirection(viewHolder.itemView));
          handleTouchActionUp(recyclerView, viewHolder, releaseDx, velocity);
          swipeBack = true;
          endSwipe(recyclerView, viewHolder, dx, velocity);
          break;
        case MotionEvent.ACTION_CANCEL:
          swipeBack = true;
          endSwipe(recyclerView, viewHolder, dx, 0f);
          break;
      }
      return false;
    });
  }

  /** Tellomi（A-10）：松手 / 取消后回位。弹簧从屏幕上气泡现在的位置出发：还没画过就是原位，带着松手速度先甩出去再回来。 */
  private void endSwipe(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dx, float velocity) {
    if (swipeActive) {
      startReturn(recyclerView, viewHolder, Math.max(dx, 0f), velocity);
    } else {
      resetProgressIfAnimationsDisabled(recyclerView, viewHolder);
    }
    armed       = false;
    swipeActive = false;
  }

  private void addVelocitySample(@NonNull MotionEvent event) {
    if (velocityTracker == null) {
      velocityTracker = VelocityTracker.obtain();
    }
    velocityTracker.addMovement(event);
  }

  /** Tellomi（A-10）：松手时沿回复方向的速度，px/s，往回复方向为正。手指停住超过 40 ms 再抬起时系统算出来是 0。 */
  private float replyVelocity(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (velocityTracker == null) {
      return 0f;
    }
    velocityTracker.computeCurrentVelocity(1000);
    return velocityTracker.getXVelocity() * getSignFromDirection(viewHolder.itemView);
  }

  private void handleTouchActionUp(@NonNull RecyclerView recyclerView,
                                   @NonNull RecyclerView.ViewHolder viewHolder,
                                   float dx,
                                   float velocity)
  {
    InteractiveConversationElement element = requireInteractiveConversationElement(viewHolder);
    // Tellomi（A-10，标准第四节「判定用投射落点」）：位移 + 速度 × 0.1 s 过阈值就回复；只认这一次滑动的松手。
    if (swipeActive && TellomiSwipeToReply.shouldReply(dx, velocity, triggerDx(element))) {
      canTriggerSwipe = false;
      onSwiped(viewHolder);
      if (!armed) {
        TellomiHaptics.INSTANCE.perform(viewHolder.itemView, TellomiHaptics.Event.THRESHOLD_ACTIVATE);
        ConversationSwipeAnimationHelper.trigger(element);
      }
      recyclerView.setOnTouchListener(null);
    }
    recyclerView.cancelPendingInputEvents();
  }

  /**
   * Tellomi（A-10，标准第四节「松手」）：气泡从松手时的位置和速度出发，用 {@link TellomiMotion#getRelease()} 弹簧回到原位，
   * 不再走 ItemTouchHelper 固定 250 ms 的补间。「移除动画」打开时直接归零。
   * 弹簧期间这个 ViewHolder 换绑到别的消息（滚走后复用）就停下、把它归零。
   */
  private void startReturn(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dx, float velocity) {
    SpringAnimation previous = returnSprings.remove(viewHolder);
    if (previous != null) {
      previous.cancel();
    }

    InteractiveConversationElement element = requireInteractiveConversationElement(viewHolder);
    float                          sign    = getSignFromDirection(viewHolder.itemView);

    if (TellomiMotion.INSTANCE.tier(recyclerView.getContext()) == TellomiMotion.Tier.MINIMAL) {
      ConversationSwipeAnimationHelper.update(element, 0f, sign);
      recyclerView.invalidate();
      return;
    }

    long messageId = element.getConversationMessage().getMessageRecord().getId();
    SpringAnimation spring = new SpringAnimation(new FloatValueHolder(ConversationSwipeAnimationHelper.bubbleOffset(element, dx)));
    spring.setSpring(new SpringForce(0f).setStiffness(TellomiMotion.INSTANCE.getRelease().getStiffness())
                                        .setDampingRatio(TellomiMotion.INSTANCE.getRelease().getDampingRatio()));
    spring.setStartVelocity(ConversationSwipeAnimationHelper.bubbleVelocity(element, dx, velocity));
    spring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
    spring.addUpdateListener((animation, value, springVelocity) -> {
      if (element.getConversationMessage().getMessageRecord().getId() != messageId) {
        ConversationSwipeAnimationHelper.update(element, 0f, sign);
        recyclerView.invalidate();
        animation.cancel();
        return;
      }
      ConversationSwipeAnimationHelper.updateReturning(element, value, sign);
      recyclerView.invalidate();
    });
    spring.addEndListener((animation, canceled, value, springVelocity) -> {
      if (returnSprings.get(viewHolder) == animation) {
        returnSprings.remove(viewHolder);
      }
    });

    returnSprings.put(viewHolder, spring);
    spring.start();
  }

  private void resetProgressIfAnimationsDisabled(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
    if (AccessibilityUtil.areAnimationsDisabled(viewHolder.itemView.getContext())) {
      ConversationSwipeAnimationHelper.update(requireInteractiveConversationElement(viewHolder),
                                              0f,
                                              getSignFromDirection(viewHolder.itemView));
      recyclerView.invalidate();
    }
  }

  private @NonNull InteractiveConversationElement requireInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    return Objects.requireNonNull(getInteractiveConversationElement(viewHolder));
  }

  private @Nullable InteractiveConversationElement getInteractiveConversationElement(@NonNull RecyclerView.ViewHolder viewHolder) {
    if (viewHolder instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder;
    } else if (viewHolder.itemView instanceof InteractiveConversationElement) {
      return (InteractiveConversationElement) viewHolder.itemView;
    } else {
      return null;
    }
  }

  private boolean cannotSwipeViewHolder(@NonNull RecyclerView.ViewHolder viewHolder) {
    InteractiveConversationElement element = getInteractiveConversationElement(viewHolder);
    if (element == null) {
      return true;
    }

    return !swipeAvailabilityProvider.isSwipeAvailable(element.getConversationMessage()) ||
           element.disallowSwipe(latestDownX, latestDownY) ||
           downInGestureEdge;
  }

  private void updateLatestDownCoordinate(float x, float y) {
    latestDownX       = x;
    latestDownY       = y;
    downInGestureEdge = isInSystemGestureEdge(x);
  }

  /**
   * Tellomi（A-10，标准第四节「边缘让给系统」）：按下点在系统返回手势的左右边缘区里，就不起滑动回复，
   * 免得气泡先动一下、再被系统的返回手势抢走。宽度取 {@code WindowInsets.systemGestures}，跟着用户调的返回灵敏度走。
   * {@code rawX} 是屏幕坐标，换成窗口坐标再比。
   */
  private boolean isInSystemGestureEdge(float rawX) {
    RecyclerView recyclerView = attachedRecyclerView;
    if (recyclerView == null) return false;

    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(recyclerView);
    if (insets == null) return false;

    View  root     = recyclerView.getRootView();
    int[] location = new int[2];
    root.getLocationOnScreen(location);

    Insets edges = insets.getInsets(WindowInsetsCompat.Type.systemGestures());
    return TellomiSwipeToReply.startsInGestureEdge(rawX - location[0], root.getWidth(), edges.left, edges.right);
  }

  private static float getSignFromDirection(@NonNull View view) {
    return TellomiSwipeToReply.replySign(view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
  }

  /** Tellomi（tellomi/tellomi#1109）：过这个位移松手才算回复——对方的消息 45dp、自己的 60dp（Telegram 同值）。 */
  static float triggerDx(@NonNull InteractiveConversationElement element) {
    boolean isOutgoing = element.getConversationMessage().getMessageRecord().isOutgoing();
    return TellomiSwipeToReply.triggerDp(isOutgoing) * Resources.getSystem().getDisplayMetrics().density;
  }

  private static boolean sameSign(float dX, float sign) {
    return dX * sign > 0;
  }

  public interface SwipeAvailabilityProvider {
    boolean isSwipeAvailable(ConversationMessage conversationMessage);
  }

  public interface OnSwipeListener {
    void onSwipe(ConversationMessage conversationMessage);
  }
}
