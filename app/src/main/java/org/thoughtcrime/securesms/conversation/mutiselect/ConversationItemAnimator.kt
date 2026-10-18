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
  private val shouldUseSlideAnimation: (RecyclerView.ViewHolder) -> Boolean,
  /** Tellomi (interaction audit A-05): see [ListTranslation]. */
  private val listTranslation: ListTranslation = ListTranslation.NONE
) : RecyclerView.ItemAnimator() {

  /**
   * Tellomi (interaction audit A-05): the list's own translationY. In a conversation too short to scroll,
   * [org.thoughtcrime.securesms.conversation.v2.ConversationHeaderPositionDecoration] moves the whole list to pin the header
   * under the toolbar, so when a row grows (a reaction pill), shrinks, or one is added or removed, that translation changes
   * together with the rows' layout. Every row still moves from where it was on screen:
   * - a list moving down (the content grew) jumps in the same frame, and the rows start their slides that much higher;
   * - a list moving up (the content shrank) is held where it was and eases up with the rows instead. Jumping, it would take
   *   its bottom edge up with it and cut off the rows starting their slides below that edge (the list draws only inside its
   *   bounds).
   */
  interface ListTranslation {
    /** How much the list's translationY is about to change in the frame that draws this layout; 0 for a list that scrolls. */
    fun pendingDelta(): Float

    /** An offset held on top of the pinned translationY; the list eases into place as it goes back to 0. */
    fun setEasingOffset(offset: Float)

    companion object {
      @JvmField
      val NONE: ListTranslation = object : ListTranslation {
        override fun pendingDelta(): Float = 0f
        override fun setEasingOffset(offset: Float) = Unit
      }
    }
  }

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

  /**
   * Tellomi (A-05): a removed message fading out where it was on screen. While the list eases up under it, it follows the list
   * down by [easeBy] (list coordinates), but no further than [room], the space left below it inside the list's bounds.
   */
  private data class FadeOut(
    val easeBy: Float,
    val room: Float
  )

  // Tellomi (interaction audit A-05): removed messages fade out instead of vanishing in the same frame.
  private val pendingFadeOuts: MutableMap<RecyclerView.ViewHolder, FadeOut> = mutableMapOf()
  private val fadeOutAnimations: MutableMap<RecyclerView.ViewHolder, Animator> = mutableMapOf()

  // Tellomi (A-05): the list held where it was, eased up from runPendingAnimations ([ListTranslation]).
  private var pendingEasingOffset = 0f
  private var easingAnimator: ValueAnimator? = null

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
      TellomiMotion.tier(viewHolder.itemView.context) != TellomiMotion.Tier.MINIMAL
    ) {
      // It fades where it was on screen while the others slide into its place: a list jumping down is made up for now, a list
      // easing up is followed frame by frame ([runPendingFadeOuts]).
      val delta = listTranslation.pendingDelta()
      viewHolder.itemView.translationY = -jumpingDelta(delta)
      holdList(delta)
      pendingFadeOuts[viewHolder] = FadeOut(easeBy = (-delta).coerceAtLeast(0f), room = roomBelow(viewHolder.itemView))
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

    val delta = listTranslation.pendingDelta()
    val translationY = if (preLayoutInfo == null) {
      // A new row comes up from just below its final place.
      (postLayoutInfo.bottom - postLayoutInfo.top).toFloat()
    } else {
      slideStart(preLayoutInfo.top, postLayoutInfo.top, jumpingDelta(delta))
    }

    if (translationY == 0f) {
      viewHolder.itemView.translationY = 0f
      dispatchAnimationFinished(viewHolder)
      return false
    }

    viewHolder.itemView.translationY = translationY
    holdList(delta)

    pendingSlideAnimations[viewHolder] = TweeningInfo(translationY, 0f)
    dispatchAnimationStarted(viewHolder)

    Log.d(TAG, "Dispatched slide animation for view at ${viewHolder.absoluteAdapterPosition}")
    return true
  }

  override fun animatePersistence(viewHolder: RecyclerView.ViewHolder, preLayoutInfo: ItemHolderInfo, postLayoutInfo: ItemHolderInfo): Boolean {
    // Tellomi (A-05): also when the list does not fill the screen (Telegram moves its rows either way). Upstream skipped
    // that case: the list's own translation changed in the same frame, so slides measured in list coordinates jumped.
    // [ListTranslation] now accounts for it.
    return if (!isInMultiSelectMode() && shouldPlayMessageAnimations()) {
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
    runPendingEasing()
  }

  /** Tellomi (A-05): like Telegram's ChatListItemAnimator, a removed message fades out while the others move into its place. */
  private fun runPendingFadeOuts() {
    for ((viewHolder, fadeOut) in pendingFadeOuts) {
      val view: View = viewHolder.itemView
      val animator = ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f)
      animator.duration = FADE_OUT_DURATION_MS
      // The list eases up under it: follow it down on the list's own timing, so it stays where it was on screen.
      val follow = if (fadeOut.easeBy > 0f) {
        ValueAnimator.ofFloat(0f, 1f).apply {
          duration = SLIDE_DURATION_MS
          interpolator = SLIDE_INTERPOLATOR
          addUpdateListener { view.translationY = minOf(fadeOut.easeBy * it.animatedFraction, fadeOut.room) }
        }
      } else {
        null
      }
      animator.doOnEnd {
        follow?.cancel()
        view.alpha = 1f
        view.translationY = 0f
        if (fadeOutAnimations.remove(viewHolder) != null) {
          dispatchAnimationFinished(viewHolder)
          dispatchFinishedWhenDone()
        }
      }
      fadeOutAnimations[viewHolder] = animator
      animator.start()
      follow?.start()
    }
    pendingFadeOuts.clear()
  }

  /** Tellomi (A-05): the held list eases up into place on the same timing as the rows' slides ([ListTranslation]). */
  private fun runPendingEasing() {
    val from = pendingEasingOffset
    pendingEasingOffset = 0f
    if (from == 0f) {
      return
    }

    val easing = ValueAnimator.ofFloat(from, 0f)
    easing.duration = SLIDE_DURATION_MS
    easing.interpolator = SLIDE_INTERPOLATOR
    easing.addUpdateListener {
      if (easingAnimator === easing) {
        listTranslation.setEasingOffset(it.animatedValue as Float)
      }
    }
    easing.doOnEnd {
      if (easingAnimator === easing) {
        easingAnimator = null
        listTranslation.setEasingOffset(0f)
        dispatchFinishedWhenDone()
      }
    }
    easingAnimator = easing
    easing.start()
  }

  /**
   * Tellomi (A-05): every animation a layout starts calls this with the list's pending move ([ListTranslation]): a list about
   * to move up is held where it is, to be eased up from [runPendingAnimations]; a list about to move down jumps, so anything
   * still easing it stops.
   */
  private fun holdList(delta: Float) {
    if (delta == 0f) {
      return
    }

    stopEasing()
    pendingEasingOffset = (-delta).coerceAtLeast(0f)
    listTranslation.setEasingOffset(pendingEasingOffset)
  }

  private fun stopEasing() {
    val easing = easingAnimator ?: return
    easingAnimator = null
    easing.cancel()
  }

  private fun finishEasing() {
    if (easingAnimator == null && pendingEasingOffset == 0f) {
      return
    }

    pendingEasingOffset = 0f
    stopEasing()
    listTranslation.setEasingOffset(0f)
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
    if (pendingFadeOuts.remove(item) != null) {
      item.itemView.alpha = 1f
      item.itemView.translationY = 0f
      dispatchAnimationFinished(item)
    }
    fadeOutAnimations[item]?.end()
  }

  override fun endAnimations() {
    endSlideAnimations()
    pendingFadeOuts.keys.forEach {
      it.itemView.alpha = 1f
      it.itemView.translationY = 0f
      dispatchAnimationFinished(it)
    }
    pendingFadeOuts.clear()
    fadeOutAnimations.values.toList().forEach { it.end() }
    finishEasing()
    dispatchAnimationsFinished()
  }

  override fun isRunning(): Boolean {
    return pendingFadeOuts.isNotEmpty() ||
      fadeOutAnimations.values.any { it.isRunning } ||
      slideAnimations.values.any { it.sharedAnimator.isRunning } ||
      pendingEasingOffset != 0f ||
      easingAnimator?.isRunning == true
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

    /**
     * Tellomi (A-05): the translationY a moved row starts its slide from, so that the first frame shows it exactly where it
     * was on screen: its layout moved by `preTop - postTop`, and the whole list's translation moves by [parentDelta] in the
     * same frame (a conversation too short to scroll keeps its header pinned under the toolbar).
     */
    @VisibleForTesting
    fun slideStart(preTop: Int, postTop: Int, parentDelta: Float): Float {
      return (preTop - postTop) - parentDelta
    }

    /**
     * Tellomi (A-05): the part of the list's pending move ([ListTranslation.pendingDelta]) that happens in the frame drawing the
     * new layout: all of a move down, none of a move up (that one is eased).
     */
    @VisibleForTesting
    fun jumpingDelta(delta: Float): Float {
      return delta.coerceAtLeast(0f)
    }

    /** Tellomi (A-05): how far [view] can move down before the list's bottom edge cuts it off. */
    private fun roomBelow(view: View): Float {
      val list = view.parent as? View ?: return Float.MAX_VALUE
      return (list.height - view.bottom).toFloat().coerceAtLeast(0f)
    }
  }
}
