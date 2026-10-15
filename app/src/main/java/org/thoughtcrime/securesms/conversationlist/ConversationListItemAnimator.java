package org.thoughtcrime.securesms.conversationlist;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.view.View;
import android.view.ViewPropertyAnimator;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ConversationListItemAnimator extends DefaultItemAnimator {

  private static final long ANIMATION_DURATION = 200;

  // Tellomi（交互审计 A-37）：移动不再默认关掉（原来只有被顶上去的那一行滑、其余行瞬移），见 TellomiChatListMotion。
  // 新出现的行自己淡入、和移动同时开始：DefaultItemAnimator 要等移动播完才淡入，顶上会先空 250 ms。
  private final List<RecyclerView.ViewHolder> pendingAdditions = new ArrayList<>();
  private final List<RecyclerView.ViewHolder> runningAdditions = new ArrayList<>();

  private boolean listScrolling;
  private int     pendingMoves;
  private int     pendingRemovals;

  @VisibleForTesting
  boolean lastRunAnimated;

  public ConversationListItemAnimator() {
    setMoveDuration(TellomiChatListMotion.MOVE_DURATION_MS);
    setAddDuration(TellomiChatListMotion.ADD_DURATION_MS);
    setChangeDuration(ANIMATION_DURATION);
  }

  @Override
  public boolean animateChange(@NonNull RecyclerView.ViewHolder oldHolder, @NonNull RecyclerView.ViewHolder newHolder, int fromX, int fromY, int toX, int toY) {
    if (fromX != toX || fromY != toY) {
      return animateMove(newHolder, fromX, fromY, toX, toY);
    }

    dispatchChangeFinished(newHolder, true);
    return false;
  }

  @Override
  public boolean animateMove(@NonNull RecyclerView.ViewHolder holder, int fromX, int fromY, int toX, int toY) {
    boolean pending = super.animateMove(holder, fromX, fromY, toX, toY);
    if (pending) {
      pendingMoves++;
      holder.itemView.animate().setInterpolator(TellomiChatListMotion.MOVE_INTERPOLATOR);
    }
    return pending;
  }

  @Override
  public boolean animateAdd(@NonNull RecyclerView.ViewHolder holder) {
    endAnimation(holder);
    holder.itemView.setAlpha(0f);
    pendingAdditions.add(holder);
    return true;
  }

  @Override
  public boolean animateRemove(@NonNull RecyclerView.ViewHolder holder) {
    pendingRemovals++;
    return super.animateRemove(holder);
  }

  @Override
  public void runPendingAnimations() {
    boolean animate = TellomiChatListMotion.shouldAnimate(pendingAdditions.size(), pendingRemovals, listScrolling);
    boolean fadeIn  = TellomiChatListMotion.shouldFadeIn(pendingMoves, pendingRemovals);
    pendingMoves    = 0;
    pendingRemovals = 0;
    lastRunAnimated = animate;

    if (!animate) {
      endAnimations();
      return;
    }

    super.runPendingAnimations();

    List<RecyclerView.ViewHolder> additions = new ArrayList<>(pendingAdditions);
    pendingAdditions.clear();
    for (RecyclerView.ViewHolder holder : additions) {
      if (fadeIn) {
        fadeIn(holder);
      } else {
        holder.itemView.setAlpha(1f);
        dispatchAddFinished(holder);
      }
    }
  }

  private void fadeIn(@NonNull RecyclerView.ViewHolder holder) {
    View                 view      = holder.itemView;
    ViewPropertyAnimator animation = view.animate();

    runningAdditions.add(holder);
    animation.alpha(1f)
             .setDuration(getAddDuration())
             .setInterpolator(TellomiChatListMotion.MOVE_INTERPOLATOR)
             .setListener(new AnimatorListenerAdapter() {
               @Override
               public void onAnimationStart(Animator animator) {
                 dispatchAddStarting(holder);
               }

               @Override
               public void onAnimationCancel(Animator animator) {
                 view.setAlpha(1f);
               }

               @Override
               public void onAnimationEnd(Animator animator) {
                 animation.setListener(null);
                 runningAdditions.remove(holder);
                 dispatchAddFinished(holder);
                 if (!isRunning()) {
                   dispatchAnimationsFinished();
                 }
               }
             })
             .start();
  }

  @Override
  public void endAnimation(@NonNull RecyclerView.ViewHolder holder) {
    if (pendingAdditions.remove(holder)) {
      holder.itemView.setAlpha(1f);
      dispatchAddFinished(holder);
    }
    // 正在淡入的由 super 里的 animate().cancel() 收尾（取消回调把透明度补成 1）。
    super.endAnimation(holder);
  }

  @Override
  public void endAnimations() {
    for (RecyclerView.ViewHolder holder : new ArrayList<>(pendingAdditions)) {
      holder.itemView.setAlpha(1f);
      dispatchAddFinished(holder);
    }
    pendingAdditions.clear();
    for (RecyclerView.ViewHolder holder : new ArrayList<>(runningAdditions)) {
      holder.itemView.animate().cancel();
    }
    super.endAnimations();
  }

  @Override
  public boolean isRunning() {
    return super.isRunning() || !pendingAdditions.isEmpty() || !runningAdditions.isEmpty();
  }

  @Override
  public boolean canReuseUpdatedViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, @NonNull List<Object> payloads) {
    return true;
  }

  /** Tellomi（交互审计 A-37）：手指在滑或惯性滚动时来的更新直接换位，不播移动。 */
  @MainThread
  public void setListScrolling(boolean listScrolling) {
    this.listScrolling = listScrolling;
  }

  /** Tellomi（交互审计 A-37）：移动一直开着，滑动归档前后不用再开关；留着给原来的调用处。 */
  @MainThread
  public void enable() {
  }

  @MainThread
  public void disable() {
  }

  @MainThread
  public void disableChangeAnimations() {
    setChangeDuration(0);
  }

  @MainThread
  public void enableChangeAnimations() {
    setChangeDuration(ANIMATION_DURATION);
  }

  @MainThread
  public void postDisable(Handler handler) {
  }
}
