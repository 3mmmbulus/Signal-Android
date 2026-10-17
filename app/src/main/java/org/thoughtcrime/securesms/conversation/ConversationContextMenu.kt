package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.ContextMenuList

/**
 * The context menu shown after long pressing a message in ConversationActivity.
 */
class ConversationContextMenu(private val anchor: View, items: List<ActionItem>) : PopupWindow(
  LayoutInflater.from(anchor.context).inflate(R.layout.signal_context_menu, null),
  ViewGroup.LayoutParams.WRAP_CONTENT,
  ViewGroup.LayoutParams.WRAP_CONTENT
) {

  val context: Context = anchor.context

  private val contextMenuList = ContextMenuList(
    recyclerView = contentView.findViewById(R.id.signal_context_menu_list),
    onItemClick = { dismiss() }
  )

  init {
    setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.signal_context_menu_background))
    animationStyle = R.style.ConversationContextMenuAnimation

    isFocusable = false
    isOutsideTouchable = true

    elevation = 20f

    setTouchInterceptor { _, event ->
      event.action == MotionEvent.ACTION_OUTSIDE
    }

    contextMenuList.setItems(items)

    contentView.measure(
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
  }

  /**
   * Tellomi (interaction audit A-06): Telegram fades the menu out while the reaction strip grows into the emoji panel.
   * Set on the view that carries the menu's background, not the root, whose own alpha the grow-in spring may still own.
   */
  fun setContentAlpha(alpha: Float) {
    ((contentView.parent as? View) ?: contentView).alpha = alpha
  }

  fun getMaxWidth(): Int = contentView.measuredWidth
  fun getMaxHeight(): Int = contentView.measuredHeight

  /**
   * Tellomi（交互审计 A-06 Android，审计「Android 菜单不从消息长出来」）：菜单从消息那一侧的上角弹簧展开——[growFromLeft]
   * 为 true（消息在左边）从左上角，否则右上角；缩放用 Move（带一点回弹），透明度用 Fade。省电 / 关了动画照上游淡入。
   */
  fun show(offsetX: Int, offsetY: Int, growFromLeft: Boolean, growDelayMs: Long = 0L) {
    val grow = TellomiMotion.tier(context) != TellomiMotion.Tier.MINIMAL
    animationStyle = if (grow) R.style.TellomiConversationContextMenuAnimation else R.style.ConversationContextMenuAnimation
    showAsDropDown(anchor, offsetX, offsetY, Gravity.TOP or Gravity.START)
    if (grow) {
      growIn(contentView.rootView, growFromLeft, growDelayMs)
    }
  }

  /** [delayMs]: the menu stays invisible this long first, while the message moves into place (see the controller). */
  private fun growIn(root: View, fromLeft: Boolean, delayMs: Long) {
    root.scaleX = GROW_FROM_SCALE
    root.scaleY = GROW_FROM_SCALE
    root.alpha = 0f
    root.doOnPreDraw {
      it.pivotX = if (fromLeft) 0f else it.width.toFloat()
      it.pivotY = 0f
    }
    val start = Runnable {
      spring(root, DynamicAnimation.SCALE_X, TellomiMotion.Move)
      spring(root, DynamicAnimation.SCALE_Y, TellomiMotion.Move)
      spring(root, DynamicAnimation.ALPHA, TellomiMotion.Fade)
    }
    if (delayMs > 0L) {
      root.postDelayed(start, delayMs)
    } else {
      start.run()
    }
  }

  private fun spring(view: View, property: DynamicAnimation.ViewProperty, token: TellomiMotion.SpringToken) {
    SpringAnimation(view, property, 1f).apply {
      spring.stiffness = token.stiffness
      spring.dampingRatio = token.dampingRatio
      if (property == DynamicAnimation.ALPHA) {
        setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
      } else {
        setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
      }
      start()
    }
  }

  companion object {
    /** Tellomi (A-06): how small the menu starts as it grows out of the message. */
    private const val GROW_FROM_SCALE = 0.6f
  }
}
