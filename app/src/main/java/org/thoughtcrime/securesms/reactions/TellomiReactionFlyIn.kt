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
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.signal.core.ui.motion.TellomiHaptics
import org.signal.core.ui.motion.TellomiMotion
import org.signal.emoji.EmojiProvider
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.signal.core.ui.R as CoreUiR

/**
 * Tellomi（交互审计 A-07 Android 半边；owner 2026-09-26 定「两端都飞」）：回应条上选中的表情飞到消息下方胶囊里的同一个表情上。
 *
 * 和 iOS `ReactionFlyIn`（Signal-iOS#79）同一套手感：
 * - 选中时表情在原处「拿起来」（放大到 [LIFT_SCALE]）；
 * - 等长按菜单收完（[isReadyToLand]）、胶囊画出来（[findTarget] 找得到）再起飞：横向临界阻尼的 Snap，纵向 Large，目标在下面够远时
 *   带一点向上的初速 [TOSS_SPEED_DP]——横向先到、纵向后落，就是一道弧线；
 * - 落到比胶囊里的字大 [LANDING_OVERSHOOT] 倍，再用 Emphasis 弹回原大小，换成胶囊里真的那个字，轻触一下；
 * - 飞行期间胶囊里的那个字一直藏着（[ReactionsConversationView] 问 [isAwaitingLanding]），一帧都不先露；
 * - 等不到落点（写失败、消息滚走、表情并进了「+N」）就原地淡出，藏着的字放回来。
 *
 * 飞的字是 decorView 的 [android.view.ViewOverlay] 上的一个 Drawable，不进布局；每帧重新量落点，列表在动也跟得上。
 * 只在 [TellomiMotion.allowsFlourish] 时由调用方创建。
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

  private enum class Phase { WAITING, FLYING, SETTLING, DONE }

  private val density = host.resources.displayMetrics.density
  private val hostOrigin = IntArray(2).also { host.getLocationInWindow(it) }
  private val startedAt = SystemClock.uptimeMillis()
  private var readySince = 0L
  private var phase = Phase.WAITING

  private val centerX = FloatValueHolder(sourceInWindow.centerX() - hostOrigin[0])
  private val centerY = FloatValueHolder(sourceInWindow.centerY() - hostOrigin[1])
  private val size = FloatValueHolder(sourceInWindow.width())
  private var alpha = 1f

  private var xSpring: SpringAnimation? = null
  private var ySpring: SpringAnimation? = null
  private var sizeSpring: SpringAnimation? = null
  private var targetSize = 0f
  private var flightTargetX = 0f
  private var flightTargetY = 0f

  /** Tellomi（A-07 爆开）：起飞时离落点多远；飞过 70% 就在落点爆开一次。 */
  private var startDistance = 0f
  private var burstPlayed = false

  init {
    awaiting = key
    host.overlay.add(drawable)
    applyBounds()
    sizeSpring = spring(size, TellomiMotion.Snap).apply { animateToFinalPosition(sourceInWindow.width() * LIFT_SCALE) }
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (phase == Phase.DONE) {
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
            takeOff(target)
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
        // 只在落点真的挪了时改终点：对停下的弹簧再设同一个终点会让它重新跑起来，就永远落不了地。
        if (abs(tx - flightTargetX) >= MIN_VISIBLE_CHANGE_PX) {
          flightTargetX = tx
          xSpring?.animateToFinalPosition(tx)
        }
        if (abs(ty - flightTargetY) >= MIN_VISIBLE_CHANGE_PX) {
          flightTargetY = ty
          ySpring?.animateToFinalPosition(ty)
        }
        if (abs(ts - targetSize) >= MIN_VISIBLE_CHANGE_PX) {
          targetSize = ts
          sizeSpring?.animateToFinalPosition(ts * LANDING_OVERSHOOT)
        }
        if (!burstPlayed && TellomiReactionBurst.shouldTrigger(startDistance, hypot(tx - centerX.value, ty - centerY.value))) {
          playBurst(tx, ty, ts)
        }
        if (xSpring?.isRunning != true && ySpring?.isRunning != true) {
          settle()
        }
      }
      Phase.SETTLING, Phase.DONE -> Unit
    }

    applyBounds()
    if (phase != Phase.DONE) {
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  private fun takeOff(target: View) {
    phase = Phase.FLYING
    val (tx, ty, ts) = measure(target)
    targetSize = ts
    flightTargetX = tx
    flightTargetY = ty

    startDistance = hypot(tx - centerX.value, ty - centerY.value)
    xSpring = spring(centerX, HORIZONTAL).apply { animateToFinalPosition(tx) }
    ySpring = spring(centerY, VERTICAL).apply {
      setStartVelocity(verticalStartVelocity(ty - centerY.value, density))
      animateToFinalPosition(ty)
    }
    sizeSpring?.cancel()
    sizeSpring = spring(size, VERTICAL).apply { animateToFinalPosition(ts * LANDING_OVERSHOOT) }
  }

  /** 落定：大一点的飞行字弹回胶囊里那个字的大小，然后换成真的那个字。 */
  private fun settle() {
    phase = Phase.SETTLING
    if (!burstPlayed) {
      playBurst(flightTargetX, flightTargetY, targetSize)
    }
    TellomiHaptics.perform(host, TellomiHaptics.Event.SELECTION_TICK)
    sizeSpring?.cancel()
    sizeSpring = spring(size, TellomiMotion.Emphasis).apply {
      addUpdateListener { _, _, _ -> applyBounds() }
      addEndListener { _, _, _, _ -> finish() }
      animateToFinalPosition(targetSize)
    }
  }

  /** Tellomi（A-07）：在落点（host 坐标）播一次爆开，用一份新的表情图画粒子。 */
  private fun playBurst(x: Float, y: Float, emojiSize: Float) {
    burstPlayed = true
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
    xSpring?.cancel()
    ySpring?.cancel()
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
    revealTarget()
    host.overlay.remove(drawable)
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

  /** 落点（胶囊里那个字）的中心和大小，换算到 host 坐标。 */
  private fun measure(target: View): Triple<Float, Float, Float> {
    // getLocationInWindow 给的是缩放之后的左上角，宽高要自己乘上缩放。
    val location = IntArray(2).also { target.getLocationInWindow(it) }
    val scale = target.matrixScale()
    return Triple(
      location[0] - hostOrigin[0] + target.width * scale / 2f,
      location[1] - hostOrigin[1] + target.height * scale / 2f,
      minOf(target.width, target.height) * scale
    )
  }

  private fun applyBounds() {
    val half = size.value / 2f
    drawable.setBounds(
      (centerX.value - half).roundToInt(),
      (centerY.value - half).roundToInt(),
      (centerX.value + half).roundToInt(),
      (centerY.value + half).roundToInt()
    )
    drawable.alpha = (alpha * 255).roundToInt()
    host.invalidate()
  }

  private fun spring(holder: FloatValueHolder, token: TellomiMotion.SpringToken): SpringAnimation {
    return SpringAnimation(holder).apply {
      spring = SpringForce(holder.value).setStiffness(token.stiffness).setDampingRatio(token.dampingRatio)
      setMinimumVisibleChange(MIN_VISIBLE_CHANGE_PX)
    }
  }

  companion object {
    /** 选中后放大一点，表示「拿起来了」。 */
    const val LIFT_SCALE = 1.15f

    /** 落到胶囊上时比胶囊里的字大这么多，再用 Emphasis 弹回原大小。 */
    const val LANDING_OVERSHOOT = 1.25f

    /** 纵向向上的初速（dp / 秒），让路线先轻轻抬起再落下。 */
    const val TOSS_SPEED_DP = 700f

    /** 纵向距离小于这个值（dp）就不抬，免得原地上下抖。 */
    const val MIN_TOSS_DISTANCE_DP = 24f

    /** 等落点的最长时间。 */
    const val LANDING_TIMEOUT_MS = 2000L

    /** 菜单收完以后最多再等胶囊这么久（表情并进了「+N」时不用等满 [LANDING_TIMEOUT_MS]）。 */
    const val TARGET_WAIT_AFTER_READY_MS = 800L

    private const val MIN_VISIBLE_CHANGE_PX = 0.5f

    /** 横向：临界阻尼，先到。 */
    private val HORIZONTAL = TellomiMotion.SpringToken(stiffness = TellomiMotion.Snap.stiffness, dampingRatio = 1f)

    /** 纵向：比横向慢、带一点回弹，后落；和横向的时间差就是弧线。 */
    private val VERTICAL = TellomiMotion.Large

    private var awaiting: Key? = null

    /** 因为 [awaiting] 藏起来的字（行被重新绑定时会换新的），落地或取消时一起放回。 */
    private val held = mutableListOf<WeakReference<View>>()

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

    /** 目标在下面够远：向上抛（负的初速）；在上面或几乎平齐：不抛。 */
    fun verticalStartVelocity(verticalDistancePx: Float, density: Float): Float {
      return if (verticalDistancePx >= MIN_TOSS_DISTANCE_DP * density) -TOSS_SPEED_DP * density else 0f
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
