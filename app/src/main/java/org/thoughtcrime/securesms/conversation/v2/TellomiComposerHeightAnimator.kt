/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.components.InputPanel
import org.thoughtcrime.securesms.components.TellomiOffsetTopDrawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tellomi（交互审计 A-11「多行增高和链接预览条都会跳」）：输入区高度变了（多打一行 / 删一行、链接预览出现消失、贴纸建议条），
 * 布局照常一步到位，画的时候先把输入区上沿、输入框里的字、气泡里原有的条、消息列表放回原来的位置，再一起滑到新位置；底边不动。
 * 新出现的条（链接预览）被升起的上沿揭开。机制同 Telegram 安卓 `ChatActivity.onLineCountChanged` / `checkAnimation`
 * （输入区背景上沿、输入框、列表一起从旧位置过渡），曲线用 [TellomiMotion.Scroll]：列表跟着动，滚动位置永远不回弹。
 *
 * 不接手：引用条、编辑态（上游自己逐帧改高度做动画）、刚打开会话的头 [SETTLE_MS]、发送后清空、动效最少档。
 * 只在排版之后、画之前读布局结果、改位移 / 裁剪 / 底图上沿，不触发额外的排版。
 */
class TellomiComposerHeightAnimator(
  private val panel: InputPanel,
  private val listFrame: View,
  private val composeText: TextView
) : ViewTreeObserver.OnPreDrawListener {

  private val bubble: View = panel.composeBubble
  private val textContainer: View = panel.composeTextContainer

  /** 每个要跟的东西离输入区底边多远（布局结果，不含这里加的位移）。 */
  private data class Snapshot(
    val panelHeight: Int,
    val bubble: Int,
    val text: Int,
    val bars: Map<View, Int>
  )

  private var last: Snapshot? = null
  private val attachedAt = SystemClock.uptimeMillis()

  // 动画开始时各自的位移；当前位移 = 起始位移 × progress（1 → 0）。
  private var progress = 0f
  private var listStart = 0f
  private var bubbleStart = 0f
  private var textStart = 0f
  private val barStarts = HashMap<View, Float>()
  private val revealedBars = HashSet<View>()
  private var progressSpring: SpringAnimation? = null
  private val textClip = Rect()
  private val barClip = Rect()

  /**
   * 排版一结束就比对、放好位移：只在画之前做的话，实测（模拟器逐帧录屏 + 列表打标记）高度变化后会先出一两帧新布局、
   * 没有位移的画面，再跳回旧位置开始滑。排版完紧接着处理就没有这一帧。
   */
  private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { onPreDraw() }

  fun attach() {
    panel.viewTreeObserver.addOnPreDrawListener(this)
    panel.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
  }

  fun detach() {
    if (panel.viewTreeObserver.isAlive) {
      panel.viewTreeObserver.removeOnPreDrawListener(this)
      panel.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }
    progressSpring?.cancel()
    progress = 0f
    applyOffsets()
  }

  override fun onPreDraw(): Boolean {
    // 绝大多数帧什么都没变：不重新量、不分配。
    val cached = last
    if (cached != null && progress == 0f && panel.height == cached.panelHeight) {
      return true
    }

    val now = snapshot()
    val previous = last
    last = now

    if (previous != null && now != null && previous.panelHeight > 0 && now.panelHeight != previous.panelHeight && shouldAnimate(previous, now)) {
      start(previous, now)
    }

    applyOffsets()
    return true
  }

  private fun shouldAnimate(previous: Snapshot, now: Snapshot): Boolean {
    if (panel.isAnimatingOwnHeight) return false
    if (SystemClock.uptimeMillis() - attachedAt < SETTLE_MS) return false
    if (!panel.isShown) return false
    // 发送后输入框清空：新消息自己有进场动画，输入区直接缩回。
    if (now.panelHeight < previous.panelHeight && composeText.text.isNullOrEmpty()) return false
    return TellomiMotion.tier(panel.context) != TellomiMotion.Tier.MINIMAL
  }

  private fun start(previous: Snapshot, now: Snapshot) {
    // 先把还在走的那根弹簧摘掉再停：它的结束回调不能把下面新算的起始位移清掉。
    val running = progressSpring
    progressSpring = null
    running?.cancel()

    // 动画中途又变了：从现在画着的位置接着走。
    listStart = (now.panelHeight - previous.panelHeight) + listStart * progress
    bubbleStart = (now.bubble - previous.bubble) + bubbleStart * progress
    textStart = (now.text - previous.text) + textStart * progress

    val carried = HashMap(barStarts)
    barStarts.clear()
    revealedBars.clear()
    for ((bar, distance) in now.bars) {
      val before = previous.bars[bar]
      if (before != null) {
        barStarts[bar] = (distance - before) + (carried[bar] ?: 0f) * progress
      } else {
        revealedBars += bar
      }
    }
    for (bar in carried.keys) {
      if (bar !in barStarts) {
        bar.translationY = 0f
      }
    }

    progress = 1f
    progressSpring = SpringAnimation(FloatValueHolder(1f)).apply {
      setSpring(SpringForce(0f).setStiffness(TellomiMotion.Scroll.stiffness).setDampingRatio(TellomiMotion.Scroll.dampingRatio))
      setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
      addUpdateListener { _, value, _ ->
        progress = value
        panel.invalidate()
        listFrame.invalidate()
      }
      addEndListener { animation, _, _, _ ->
        if (progressSpring === animation) {
          progressSpring = null
          progress = 0f
          // 之后的帧走「没变」的快路径不再调 applyOffsets，这里把剩下的零点几像素清干净。
          applyOffsets()
          panel.invalidate()
        }
      }
      start()
    }
  }

  private fun applyOffsets() {
    val p = progress
    val animating = p != 0f

    // 移的是列表外面那层：列表自己的 translationY 归 ConversationHeaderPositionDecoration（短会话把头部钉在顶栏下），它每次画都会重设。
    // 外层移下去时底边伸进输入区让出来的那条；根布局不裁子 View，输入区后画、盖在上面。
    listFrame.translationY = listStart * p
    composeText.translationY = textStart * p
    // 输入区的 clipChildren 是 false，输入框外面那层自己不裁；动画期间裁到气泡里的那一段，字在里面滑：
    // 变高时字往下让（裁掉下面新的一行），变矮时字往上挪（往上多留出位移那么高，上面那行不能先被裁掉）。
    val textOffset = (textStart * p).roundToInt()
    textContainer.clipBounds = if (animating) textClip.apply { set(0, minOf(0, textOffset), textContainer.width, textContainer.height) } else null

    // 气泡和输入区底色只改画法（上沿下移 / 上移），不动 View 的位置：动画途中打字会重新排版，改位置会被排版冲掉。
    val bubbleOffset = (bubbleStart * p).roundToInt()
    val bubbleLayoutTop = bubble.top
    (bubble.background as? TellomiOffsetTopDrawable)?.topOffset = bubbleOffset
    (panel.background as? TellomiOffsetTopDrawable)?.topOffset = bubbleOffset

    for ((bar, start) in barStarts) {
      bar.translationY = start * p
    }
    for (bar in revealedBars) {
      val hidden = bubbleLayoutTop + bubbleOffset - bar.top
      bar.clipBounds = if (animating && hidden > 0) barClip.apply { set(0, hidden, bar.width, max(hidden, bar.height)) } else null
    }

    if (!animating) {
      listStart = 0f
      bubbleStart = 0f
      textStart = 0f
      barStarts.clear()
      revealedBars.clear()
    }
  }

  private fun snapshot(): Snapshot? {
    val height = panel.height
    if (height <= 0 || bubble.measuredHeight <= 0) return null

    val bars = HashMap<View, Int>()
    for (bar in panel.tellomiTopBars) {
      if (bar.visibility == View.VISIBLE && bar.height > 0) {
        bars[bar] = height - bar.top
      }
    }

    return Snapshot(
      panelHeight = height,
      bubble = height - bubble.top,
      text = height - (textContainer.top + composeText.top),
      bars = bars
    )
  }

  companion object {
    /** 打开会话后这么久之内的高度变化（草稿、首帧排版）不做动画。 */
    const val SETTLE_MS = 600L
  }
}
