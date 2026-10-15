package org.thoughtcrime.securesms.conversation.mutiselect

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnEnd
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiMotion
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.conversation.ConversationAdapter

/**
 * Class for managing the triggering of item animations (here in the form of decoration redraws) whenever
 * there is a "selection" edge detected.
 *
 * Can be expanded upon in the future to animate other things, such as message sends.
 */
class ConversationItemAnimator(
  private val isInMultiSelectMode: () -> Boolean,
  private val shouldPlayMessageAnimations: () -> Boolean,
  private val isParentFilled: () -> Boolean,
  private val shouldUseSlideAnimation: (RecyclerView.ViewHolder) -> Boolean
) : RecyclerView.ItemAnimator() {

  private data class TweeningInfo(
    val startValue: Float,
    val endValue: Float
  ) {
    fun lerp(progress: Float): Float {
      return startValue + progress * (endValue - startValue)
    }
  }

  private data class AnimationInfo(
    val sharedAnimator: ValueAnimator,
    val tweeningInfo: TweeningInfo
  )

  private val pendingSlideAnimations: MutableMap<RecyclerView.ViewHolder, TweeningInfo> = mutableMapOf()
  private val slideAnimations: MutableMap<RecyclerView.ViewHolder, AnimationInfo> = mutableMapOf()

  // Tellomi (interaction audit A-05): removed messages fade out instead of vanishing in the same frame.
  private val pendingFadeOuts: MutableList<RecyclerView.ViewHolder> = mutableListOf()
  private val fadeOutAnimations: MutableMap<RecyclerView.ViewHolder, Animator> = mutableMapOf()

  override fun animateDisappearance(viewHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo, postLayoutInfo: ItemHolderInfo?): Boolean {
    if (viewHolder is ConversationAdapter.HeaderViewHolder &&
      !pendingSlideAnimations.containsKey(viewHolder) &&
      !slideAnimations.containsKey(viewHolder) &&
      shouldPlayMessageAnimations() &&
      isParentFilled()
    ) {
      pendingSlideAnimations[viewHolder] = TweeningInfo(0f, viewHolder.itemView.height.toFloat())
      dispatchAnimationStarted(viewHolder)
      return true
    }

    if (isRemoval(postLayoutInfo, viewHolder.absoluteAdapterPosition) &&
      viewHolder !in fadeOutAnimations &&
      viewHolder !in pendingFadeOuts &&
      !isInMultiSelectMode() &&
      shouldPlayMessageAnimations() &&
      // Only when the others slide into its place too: in a list that does not fill the screen they jump, and a message
      // fading out on top of the one that jumped into its spot reads as a glitch rather than a removal.
      isParentFilled() &&
      TellomiMotion.tier(viewHolder.itemView.context) != TellomiMotion.Tier.MINIMAL
    ) {
      pendingFadeOuts += viewHolder
      dispatchAnimationStarted(viewHolder)
      return true
    }

    dispatchAnimationFinished(viewHolder)
    return false
  }

  override fun animateAppearance(viewHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo?, postLayoutInfo: ItemHolderInfo): Boolean {
    if (viewHolder.absoluteAdapterPosition > 1 || !shouldUseSlideAnimation(viewHolder)) {
      dispatchAnimationFinished(viewHolder)
      return false
    }

    return animateSlide(viewHolder, preLayoutInfo, postLayoutInfo)
  }

  private fun animateSlide(viewHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo?, postLayoutInfo: ItemHolderInfo): Boolean {
    if (isInMultiSelectMode() || !shouldPlayMessageAnimations()) {
      dispatchAnimationFinished(viewHolder)
      return false
    }

    if (slideAnimations.containsKey(viewHolder)) {
      dispatchAnimationFinished(viewHolder)
      return false
    }

    val translationY = if (preLayoutInfo == null) {
      postLayoutInfo.bottom - postLayoutInfo.top
    } else {
      preLayoutInfo.top - postLayoutInfo.top
    }.toFloat()

    if (translationY == 0f) {
      viewHolder.itemView.translationY = 0f
      dispatchAnimationFinished(viewHolder)
      return false
    }

    viewHolder.itemView.translationY = translationY

    pendingSlideAnimations[viewHolder] = TweeningInfo(translationY, 0f)
    dispatchAnimationStarted(viewHolder)

    Log.d(TAG, "Dispatched slide animation for view at ${viewHolder.absoluteAdapterPosition}")
    return true
  }

  override fun animatePersistence(viewHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo, postLayoutInfo: ItemHolderInfo): Boolean {
    return if (!isInMultiSelectMode() && shouldPlayMessageAnimations() && isParentFilled()) {
      if (pendingSlideAnimations.contains(viewHolder) || slideAnimations.containsKey(viewHolder) || !shouldUseSlideAnimation(viewHolder)) {
        dispatchAnimationFinished(viewHolder)
        false
      } else {
        animateSlide(viewHolder, preLayoutInfo, postLayoutInfo)
      }
    } else {
      dispatchAnimationFinished(viewHolder)
      false
    }
  }

  override fun animateChange(oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo, postLayoutInfo: ItemHolderInfo): Boolean {
    if (oldHolder != newHolder) {
      dispatchAnimationFinished(oldHolder)
    }

    return animatePersistence(newHolder, preLayoutInfo, postLayoutInfo)
  }

  override fun runPendingAnimations() {
    Log.d(TAG, "Starting ${pendingSlideAnimations.size} animations.")
    runPendingFadeOuts()
    runPendingSlideAnimations()
  }

  /** Tellomi (A-05): like Telegram's ChatListItemAnimator, a removed message fades out while the others move into its place. */
  private fun runPendingFadeOuts() {
    for (viewHolder in pendingFadeOuts) {
      val view: View = viewHolder.itemView
      val animator = ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f)
      animator.duration = FADE_OUT_DURATION_MS
      animator.doOnEnd {
        view.alpha = 1f
        if (fadeOutAnimations.remove(viewHolder) != null) {
          dispatchAnimationFinished(viewHolder)
          dispatchFinishedWhenDone()
        }
      }
      fadeOutAnimations[viewHolder] = animator
      animator.start()
    }
    pendingFadeOuts.clear()
  }

  private fun runPendingSlideAnimations() {
    val animators: MutableList<Animator> = mutableListOf()
    for ((viewHolder, tweeningInfo) in pendingSlideAnimations) {
      val animator = ValueAnimator.ofFloat(0f, 1f)
      slideAnimations[viewHolder] = AnimationInfo(animator, tweeningInfo)
      // Tellomi (A-05): 250 ms on a decelerating curve (Telegram's ChatListItemAnimator moves over 250 ms) instead of
      // 150 ms, so the rows filling a removed message's place or making room for a new one read as one movement.
      animator.duration = SLIDE_DURATION_MS
      animator.interpolator = SLIDE_INTERPOLATOR
      animator.addUpdateListener {
        if (viewHolder in slideAnimations) {
          viewHolder.itemView.translationY = tweeningInfo.lerp(it.animatedFraction)
          (viewHolder.itemView.parent as RecyclerView?)?.invalidate()
        }
      }
      animator.doOnEnd {
        if (viewHolder in slideAnimations) {
          handleAnimationEnd(viewHolder)
        }
      }
      animators.add(animator)
    }

    AnimatorSet().apply {
      playTogether(animators)
      start()
    }

    pendingSlideAnimations.clear()
  }

  private fun handleAnimationEnd(viewHolder: RecyclerView.ViewHolder) {
    viewHolder.itemView.translationY = 0f
    slideAnimations.remove(viewHolder)
    dispatchAnimationFinished(viewHolder)
    dispatchFinishedWhenDone()
  }

  override fun endAnimation(item: RecyclerView.ViewHolder) {
    endSlideAnimation(item)
    if (pendingFadeOuts.remove(item)) {
      item.itemView.alpha = 1f
      dispatchAnimationFinished(item)
    }
    fadeOutAnimations[item]?.end()
  }

  override fun endAnimations() {
    endSlideAnimations()
    pendingFadeOuts.forEach {
      it.itemView.alpha = 1f
      dispatchAnimationFinished(it)
    }
    pendingFadeOuts.clear()
    fadeOutAnimations.values.toList().forEach { it.end() }
    dispatchAnimationsFinished()
  }

  override fun isRunning(): Boolean {
    return pendingFadeOuts.isNotEmpty() ||
      fadeOutAnimations.values.any { it.isRunning } ||
      slideAnimations.values.any { it.sharedAnimator.isRunning }
  }

  override fun onAnimationFinished(viewHolder: RecyclerView.ViewHolder) {
    val parent = (viewHolder.itemView.parent as? RecyclerView)
    parent?.post { parent.invalidate() }
  }

  private fun endSlideAnimation(item: RecyclerView.ViewHolder) {
    slideAnimations[item]?.sharedAnimator?.cancel()
  }

  private fun endSlideAnimations() {
    slideAnimations.values.map { it.sharedAnimator }.forEach {
      it.cancel()
    }
  }

  private fun dispatchFinishedWhenDone() {
    if (!isRunning) {
      Log.d(TAG, "Finished running animations.")
      dispatchAnimationsFinished()
    }
  }

  companion object {
    private val TAG = Log.tag(ConversationItemAnimator::class.java)

    private const val FADE_OUT_DURATION_MS = 150L
    private const val SLIDE_DURATION_MS = 250L
    private val SLIDE_INTERPOLATOR = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

    /**
     * Tellomi (A-05): a disappearing holder is a real removal (fade it) only when it has no adapter position and no layout
     * after the change; a holder merely pushed off screen still has a position and keeps the old behaviour.
     */
    @VisibleForTesting
    fun isRemoval(postLayoutInfo: RecyclerView.ItemAnimator.ItemHolderInfo?, adapterPosition: Int): Boolean {
      return postLayoutInfo == null && adapterPosition == RecyclerView.NO_POSITION
    }
  }
}
