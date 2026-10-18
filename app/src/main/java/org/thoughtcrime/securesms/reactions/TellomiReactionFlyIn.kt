/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions

import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiHaptics
import org.signal.core.ui.motion.TellomiMotion
import org.signal.emoji.EmojiProvider
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（交互审计 A-07 Android 半边；owner 2026-09-26 定「两端都飞」，10-18「一比一复刻 Telegram 的这个动效」）：
 * 回应条上选中的表情飞到消息下方胶囊里的同一个表情上。
 *
 * 飞法照 Telegram 安卓 `ReactionsEffectOverlay` 的 `ONLY_MOVE_ANIMATION`（从条上选回应时用的那一种；只取数，没搬代码）：
 * - 按帧推进：每帧进度 + 16 / 350（[FRAME_STEP]），60 Hz 屏上 350 ms、120 Hz 屏上约 180 ms——Telegram 就是这样按帧加的，
 *   而且把那一层的刷新率设成屏幕最高，所以同一台手机上和它一样快；
 * - 进度先过一道 `DEFAULT`（cubic-bezier 0.25, 0.1, 0.25, 1），横向再过 `EASE_OUT_QUINT`（0.23, 1, 0.32, 1），
 *   纵向再过一道 `DEFAULT`——横向先到、纵向后落，就是那道弧线；
 * - 表情的左上角从起点线性插到落点（横用横向进度、纵用纵向进度），大小随横向进度从条上的大小变到胶囊里的大小，落地不回弹；
 * - 进度过 0.7 在落点爆开（Telegram 这时开始播回应周围的效果），同时轻触一下（Telegram 这时 `KEYBOARD_TAP`）；
 * - 进度到 1 换成胶囊里真的那个字。
 *
 * 等起飞的那一会儿（长按菜单收完、胶囊画出来）表情在原处「拿起来」（放大到 [LIFT_SCALE]）。飞行期间胶囊里的那个字一直藏着
 * （[ReactionsConversationView] 问 [isAwaitingLanding]），一帧都不先露；等不到落点（写失败、消息滚走、表情并进了「+N」）
 * 就原地淡出，藏着的字放回来。
 *
 * 飞的字是 decorView 的 [android.view.ViewOverlay] 上的一个 Drawable，不进布局；每帧重新量落点，列表在动也跟得上
 * （Telegram 也是每帧重新取落点）。只在 [TellomiMotion.allowsFlourish] 时由调用方创建。
 */
class TellomiReactionFlyIn private constructor(
  private val host: View,
  private val key: Key,
  private val drawable: Drawable,
  sourceInWindow: RectF,
  private val isReadyToLand: () -> Boolean,
  private val findTarget: () -> View?
) : Choreographer.FrameCallback {

  data class Key(val messageId: Long, val baseEmoji: String)

  /** 飞到某一时刻时横、纵两个方向各走了多少（0–1）。 */
  data class Progress(val x: Float, val y: Float)

  private enum class Phase { WAITING, FLYING, SETTLING, DONE }

  private val hostOrigin = IntArray(2).also { host.getLocationInWindow(it) }
  private val startedAt = SystemClock.uptimeMillis()
  private var readySince = 0L
  private var phase = Phase.WAITING

  private var centerX = sourceInWindow.centerX() - hostOrigin[0]
  private var centerY = sourceInWindow.centerY() - hostOrigin[1]
  private val size = FloatValueHolder(sourceInWindow.width())
  private var alpha = 1f
  private var sizeSpring: SpringAnimation? = null

  /** 飞行进度（按帧加，0–1）。 */
  private var linearProgress = 0f
  private var fromLeft = 0f
  private var fromTop = 0f
  private var fromSize = 0f
  private var burstPlayed = false

  init {
    awaiting = key
    host.overlay.add(drawable)
    applyBounds()
    sizeSpring = SpringAnimation(size).apply {
      spring = SpringForce(size.value * LIFT_SCALE).setStiffness(TellomiMotion.Snap.stiffness).setDampingRatio(TellomiMotion.Snap.dampingRatio)
      setMinimumVisibleChange(MIN_VISIBLE_CHANGE_PX)
      addUpdateListener { _, _, _ -> applyBounds() }
      start()
    }
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (phase == Phase.DONE || phase == Phase.SETTLING) {
      return
    }

    val now = SystemClock.uptimeMillis()
    when (phase) {
      Phase.WAITING -> {
        if (now - startedAt > LANDING_TIMEOUT_MS) {
          cancel()
          return
        }
        if (isReadyToLand()) {
          if (readySince == 0L) {
            readySince = now
          }
          val target = findTarget()?.takeIf { it.isLaidOutOnScreen() }
          if (target != null) {
            takeOff()
          } else if (now - readySince > TARGET_WAIT_AFTER_READY_MS) {
            cancel()
            return
          }
        }
      }
      Phase.FLYING -> {
        // 列表还在动（补位、胶囊撑高气泡）：落点每帧重新量。
        val target = findTarget()?.takeIf { it.isLaidOutOnScreen() }
        if (target == null) {
          cancel()
          return
        }
        val (tx, ty, ts) = measure(target)
        val progress = flightProgress(linearProgress)
        val currentSize = lerp(fromSize, ts, progress.x)
        val left = lerp(fromLeft, tx - ts / 2f, progress.x)
        val top = lerp(fromTop, ty - ts / 2f, progress.y)
        size.value = currentSize
        centerX = left + currentSize / 2f
        centerY = top + currentSize / 2f

        linearProgress = (linearProgress + FRAME_STEP).coerceAtMost(1f)
        if (!burstPlayed && linearProgress > TellomiReactionBurst.TRIGGER_PROGRESS) {
          playBurst(tx, ty, ts)
        }
        if (linearProgress >= 1f) {
          applyBounds()
          land(tx, ty, ts)
          return
        }
      }
      Phase.SETTLING, Phase.DONE -> Unit
    }

    applyBounds()
    if (phase != Phase.DONE) {
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  /** 从此刻的位置和大小（拿起来之后）起飞。 */
  private fun takeOff() {
    phase = Phase.FLYING
    sizeSpring?.cancel()
    sizeSpring = null
    linearProgress = 0f
    fromSize = size.value
    fromLeft = centerX - fromSize / 2f
    fromTop = centerY - fromSize / 2f
  }

  /** 落地：换成胶囊里真的那个字。还没爆开（帧掉得厉害）就在这里补上。 */
  private fun land(x: Float, y: Float, emojiSize: Float) {
    if (!burstPlayed) {
      playBurst(x, y, emojiSize)
    }
    finish()
  }

  /** Tellomi（A-07）：在落点（host 坐标）播一次爆开，用一份新的表情图画粒子；同时轻触一下。 */
  private fun playBurst(x: Float, y: Float, emojiSize: Float) {
    burstPlayed = true
    TellomiHaptics.perform(host, TellomiHaptics.Event.SELECTION_TICK)
    val emoji = EmojiProvider.getEmojiDrawable(host.context, key.baseEmoji, true) ?: return
    val ringColor = ContextCompat.getColor(host.context, CoreUiR.color.signal_colorOnSurfaceVariant)
    TellomiReactionBurst.play(host, emoji, x, y, emojiSize, ringColor)
  }

  /** 等不到落点：原地淡出，藏着的字放回来。可以重复调用。 */
  fun cancel() {
    if (phase == Phase.DONE || phase == Phase.SETTLING) {
      return
    }
    phase = Phase.SETTLING
    sizeSpring?.cancel()
    revealTarget()
    SpringAnimation(FloatValueHolder(1f)).apply {
      spring = SpringForce(0f).setStiffness(TellomiMotion.Fade.stiffness).setDampingRatio(TellomiMotion.Fade.dampingRatio)
      setMinimumVisibleChange(SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA)
      addUpdateListener { _, value, _ ->
        alpha = value.coerceIn(0f, 1f)
        applyBounds()
      }
      addEndListener { _, _, _, _ -> finish() }
      start()
    }
  }

  private fun finish() {
    if (phase == Phase.DONE) {
      return
    }
    phase = Phase.DONE
    Choreographer.getInstance().removeFrameCallback(this)
    sizeSpring?.cancel()
    revealTarget()
    host.overlay.remove(drawable)
    host.invalidate()
  }

  /** 放回藏着的字：落点自己藏起来的那些都放回（不靠 [findTarget] 找得到），再加上此刻找得到的落点。 */
  private fun revealTarget() {
    if (awaiting == key) {
      awaiting = null
      held.forEach { it.get()?.alpha = 1f }
      held.clear()
    }
    findTarget()?.alpha = 1f
  }

  /**
   * 落点（胶囊里那个字）的中心和大小，换算到 host 坐标。中心按此刻的缩放算（胶囊正在弹出时整颗在缩放）；
   * 大小取它停下来的大小（Telegram 的落点大小也是固定的），免得跟着弹出先缩小再变大。
   */
  private fun measure(target: View): Triple<Float, Float, Float> {
    // getLocationInWindow 给的是缩放之后的左上角，宽高要自己乘上缩放。
    val location = IntArray(2).also { target.getLocationInWindow(it) }
    val scale = target.matrixScale()
    return Triple(
      location[0] - hostOrigin[0] + target.width * scale / 2f,
      location[1] - hostOrigin[1] + target.height * scale / 2f,
      minOf(target.width, target.height).toFloat()
    )
  }

  private fun applyBounds() {
    val half = size.value / 2f
    drawable.setBounds(
      (centerX - half).roundToInt(),
      (centerY - half).roundToInt(),
      (centerX + half).roundToInt(),
      (centerY + half).roundToInt()
    )
    drawable.alpha = (alpha * 255).roundToInt()
    host.invalidate()
  }

  companion object {
    /** 选中后放大一点，表示「拿起来了」（等菜单收完、胶囊画出来的那一会儿）。 */
    const val LIFT_SCALE = 1.15f

    /** 每帧加的进度（Telegram 安卓 `ONLY_MOVE_ANIMATION`：`animateOutProgress += 16f / 350`）。 */
    const val FRAME_STEP = 16f / 350f

    /** 等落点的最长时间。 */
    const val LANDING_TIMEOUT_MS = 2000L

    /** 菜单收完以后最多再等胶囊这么久（表情并进了「+N」时不用等满 [LANDING_TIMEOUT_MS]）。 */
    const val TARGET_WAIT_AFTER_READY_MS = 800L

    private const val MIN_VISIBLE_CHANGE_PX = 0.5f

    /** Telegram 安卓 `CubicBezierInterpolator.DEFAULT` 的控制点。 */
    private val DEFAULT_EASING = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Telegram 安卓 `CubicBezierInterpolator.EASE_OUT_QUINT` 的控制点。 */
    private val EASE_OUT_QUINT = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    private var awaiting: Key? = null

    /** 因为 [awaiting] 藏起来的字（行被重新绑定时会换新的），落地或取消时一起放回。 */
    private val held = mutableListOf<WeakReference<View>>()

    /**
     * 飞行进度 [linear]（0–1）时横、纵各走了多少：先过 `DEFAULT`，横向再过 `EASE_OUT_QUINT`、纵向再过 `DEFAULT`
     * （Telegram 安卓 `ReactionsEffectOverlay` 的 `ONLY_MOVE_ANIMATION` 就是这样叠的）。
     */
    @JvmStatic
    fun flightProgress(linear: Float): Progress {
      val eased = DEFAULT_EASING.transform(linear.coerceIn(0f, 1f))
      return Progress(x = EASE_OUT_QUINT.transform(eased), y = DEFAULT_EASING.transform(eased))
    }

    /**
     * 这条消息的这个表情正等着飞进来：胶囊里的字先藏着。[baseEmoji] 是规范化之后的表情（胶囊按它分组），
     * 这里不再规范化——那要等表情数据加载完，建胶囊时不该等。
     */
    @JvmStatic
    fun isAwaitingLanding(messageId: Long, baseEmoji: String?): Boolean {
      val awaiting = awaiting ?: return false
      return baseEmoji != null && awaiting.messageId == messageId && awaiting.baseEmoji == baseEmoji
    }

    /** 胶囊建好时调：这个字正等着飞进来就先藏起来，记下，落地或取消时放回。 */
    @JvmStatic
    fun holdUntilLanded(messageId: Long, baseEmoji: String?, emojiView: View) {
      if (isAwaitingLanding(messageId, baseEmoji)) {
        emojiView.alpha = 0f
        held.removeAll { it.get() == null }
        held += WeakReference(emojiView)
      }
    }

    /**
     * 从 [sourceInWindow]（回应条上那个字此刻在窗口里的位置）起飞，落到 [baseEmoji]（规范化之后的表情，和胶囊的分组一致）
     * 那一格。拿不到表情图就不飞。
     */
    fun start(
      host: View,
      messageId: Long,
      baseEmoji: String,
      drawable: Drawable?,
      sourceInWindow: RectF,
      isReadyToLand: () -> Boolean,
      findTarget: () -> View?
    ): TellomiReactionFlyIn? {
      if (drawable == null || sourceInWindow.isEmpty) {
        return null
      }
      return TellomiReactionFlyIn(
        host = host,
        key = Key(messageId, baseEmoji),
        drawable = drawable,
        sourceInWindow = sourceInWindow,
        isReadyToLand = isReadyToLand,
        findTarget = findTarget
      )
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    private fun View.isLaidOutOnScreen(): Boolean = isAttachedToWindow && isLaidOut && width > 0 && height > 0 && isShown

    /** 胶囊弹出时整颗在缩放：取落点自己加上各级父 View 的缩放。 */
    private fun View.matrixScale(): Float {
      var scale = scaleX
      var parent = parent
      while (parent is View) {
        scale *= parent.scaleX
        parent = parent.parent
      }
      return scale
    }
  }
}
