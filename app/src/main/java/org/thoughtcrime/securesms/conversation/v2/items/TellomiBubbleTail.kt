/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.withClip
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiMotion
import org.thoughtcrime.securesms.conversation.ConversationItemDisplayMode
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.util.Projection
import org.thoughtcrime.securesms.util.hasNoBubble
import org.thoughtcrime.securesms.util.hasOnlyThumbnail
import org.thoughtcrime.securesms.util.hasQuote
import org.thoughtcrime.securesms.util.hasSticker
import org.thoughtcrime.securesms.util.isCaptionlessMms

/**
 * Tellomi：气泡的小尾巴（tellomi/tellomi#1206；设计规范 #1204 `docs/product/specs/bubbles-and-motion-design.md` 第 1、2 节）。
 *
 * 只画在一组消息的最后一条和单独一条上：我发的在右下，对方发的在左下。
 * 下面挂着表情回应的、没有气泡底色的（贴纸、大号表情、没有文字的图片）不画。
 *
 * 尾巴由 [Decoration] 统一画在列表上，在气泡下面一层。
 * 两套气泡渲染（V2 纯文字、旧版 ConversationItem）只各自报告「气泡在列表里的位置和四个角、朝哪边、什么颜色」（[Provider]）。
 * 画之前扣掉气泡本身，所以气泡颜色半透明（有壁纸时）也不会在交接处叠深。
 */
object TellomiBubbleTail {

  /**
   * 尾巴的轮廓：候选 A「圆润」，伸出 6.3、高 14。单位 dp；我发的朝右，原点是气泡右下角，y 向下为正。
   * 对方发的把 x 取反。最后一段回到气泡里面 4dp，补上那一角 4dp 圆角的缺口（画之前会扣掉气泡本身）。
   * owner 2026-09-26 在 #1204 演示页选定 A（规范已定稿）。
   */
  private val OUTLINE: Array<FloatArray> = arrayOf(
    floatArrayOf(0f, -14f),
    floatArrayOf(0f, -6.5f, 2.2f, -1.8f, 5.4f, -1.0f),
    floatArrayOf(6.3f, -0.8f, 6.3f, 0f, 5.4f, 0f),
    floatArrayOf(-4f, 0f)
  )

  /** 尾巴伸出气泡的最大宽度，dp。 */
  const val EXTENT_DP = 6.3f

  /** 尾巴的高度，dp。 */
  const val HEIGHT_DP = 14f

  /**
   * 这一条带不带尾巴：一组里的最后一条或者单独一条，下面没有挂表情回应，有气泡底色，
   * 而且是在普通会话里显示（编辑记录、消息详情等列表里不画）。
   */
  @JvmStatic
  fun shouldDraw(
    isEndOfCluster: Boolean,
    hasReactions: Boolean,
    hasBubble: Boolean,
    displayMode: ConversationItemDisplayMode
  ): Boolean {
    return isEndOfCluster && !hasReactions && hasBubble && displayMode is ConversationItemDisplayMode.Standard
  }

  /** 尾巴默认盖住的那一角的圆角半径，dp：V2 和旧版气泡带尾巴的那一角都改成了 4。 */
  const val DEFAULT_COVER_RADIUS_DP = 4f

  /**
   * 旧版渲染（[org.thoughtcrime.securesms.conversation.ConversationItem]）：这一条有没有看得见的气泡底色，没有的不画尾巴。
   * 贴纸（带引用的除外）、大号表情、没有底色的图，没文字、图或视频铺满了气泡的，已删除的消息，都算没有。
   * 没文字的语音、文件、联系人名片、阅后即焚照样有底色（[hasOnlyThumbnail] 把它们排除在外）。
   */
  @JvmStatic
  fun hasVisibleBubble(record: MessageRecord, context: Context): Boolean {
    val noBubble = record.hasNoBubble(context) && !(record.hasSticker() && record.hasQuote())
    val mediaFillsBubble = record.isCaptionlessMms(context) && record.hasOnlyThumbnail(context)
    return !noBubble && !mediaFillsBubble && !record.isRemoteDelete
  }

  /**
   * 往 [path] 里加尾巴的轮廓。[cornerX] / [bottomY] 是气泡带尾巴那一角（下角）的坐标，
   * [towardsRight] 为 true 时尾巴伸向右边（LTR 下我发的），否则伸向左边。
   *
   * [coverRadiusDp]：那一角原来的圆角半径。轮廓往气泡里多盖一块同样大的方块，画之前扣掉气泡本身后，正好补满圆角的缺口，
   * 那一角就接在尾巴上。默认 4（普通气泡）；「正在输入」的卡片四角都是 18，传 18。
   */
  @JvmStatic
  @JvmOverloads
  fun addOutline(path: Path, cornerX: Float, bottomY: Float, towardsRight: Boolean, density: Float, coverRadiusDp: Float = DEFAULT_COVER_RADIUS_DP) {
    val s = if (towardsRight) density else -density
    fun x(v: Float) = cornerX + v * s
    fun y(v: Float) = bottomY + v * density

    path.moveTo(x(OUTLINE[0][0]), y(OUTLINE[0][1]))
    for (i in 1..2) {
      val c = OUTLINE[i]
      path.cubicTo(x(c[0]), y(c[1]), x(c[2]), y(c[3]), x(c[4]), y(c[5]))
    }
    if (coverRadiusDp > DEFAULT_COVER_RADIUS_DP) {
      path.lineTo(x(-coverRadiusDp), y(0f))
      path.lineTo(x(-coverRadiusDp), y(-coverRadiusDp))
      path.lineTo(x(0f), y(-coverRadiusDp))
    } else {
      path.lineTo(x(OUTLINE[3][0]), y(OUTLINE[3][1]))
    }
    path.close()
  }

  /**
   * 一条消息的尾巴怎么画。
   *
   * @param bubble 气泡在列表里的位置与四个角。[Decoration] 画完后负责 release。
   * @param towardsRight 尾巴伸向右边（画在气泡右下角）还是左边（左下角）。
   * @param color 纯色气泡的颜色。
   * @param gradient 渐变的聊天颜色；不是渐变时为 null，用 [color]。
   * @param alpha 尾巴交接时淡出用（[handoverAlpha]），平时是 1。
   */
  class Spec @JvmOverloads constructor(
    val bubble: Projection,
    val towardsRight: Boolean,
    @ColorInt val color: Int,
    val gradient: ChatColors?,
    val coverRadiusDp: Float = DEFAULT_COVER_RADIUS_DP,
    val alpha: Float = 1f
  ) {
    fun withAlpha(alpha: Float): Spec = Spec(bubble, towardsRight, color, gradient, coverRadiusDp, alpha)
  }

  private val HANDOVER_INTERPOLATOR = TellomiMotion.TailHandover.CURVE.let { PathInterpolatorCompat.create(it[0], it[1], it[2], it[3]) }

  /**
   * 尾巴交接（规范 #1204 第 3 节）：同一条消息上次绑定时画着尾巴、这次不画了（新消息接到了这一组下面，或者挂上了表情回应），
   * 尾巴就淡出 [TellomiMotion.TailHandover.DURATION_MS]。返回新的开始时间（uptimeMillis，0 = 不淡）：
   * 又画回尾巴、这一行换成了别的消息 → 0；同一条接着绑定 → 照旧淡。「移除动画」打开时直接换（规范第 10 节）。
   *
   * @param previousMessageId 这一行上次绑定的消息（没有是 -1）
   * @param previousHadTail 上次画没画尾巴
   */
  @JvmStatic
  @JvmOverloads
  fun handoverStart(
    messageId: Long,
    hasTail: Boolean,
    previousMessageId: Long,
    previousHadTail: Boolean,
    fadeStart: Long,
    context: Context,
    now: Long = SystemClock.uptimeMillis()
  ): Long {
    return when {
      hasTail || previousMessageId != messageId -> 0L
      previousHadTail -> if (TellomiMotion.tier(context) != TellomiMotion.Tier.MINIMAL) now else 0L
      else -> fadeStart
    }
  }

  /** 尾巴交接从 [fadeStart] 开始淡出，现在的透明度；淡完或没在淡是 0。 */
  @JvmStatic
  @JvmOverloads
  fun handoverAlpha(fadeStart: Long, now: Long = SystemClock.uptimeMillis()): Float {
    if (fadeStart <= 0L) {
      return 0f
    }
    val progress = (now - fadeStart).toFloat() / TellomiMotion.TailHandover.DURATION_MS
    if (progress >= 1f) {
      return 0f
    }
    return 1f - HANDOVER_INTERPOLATOR.getInterpolation(progress.coerceAtLeast(0f))
  }

  /** 气泡的渲染方实现：这一条现在要不要画尾巴、画在哪里；不画返回 null。坐标相对于 [parent]。 */
  interface Provider {
    fun getTellomiTail(parent: RecyclerView): Spec?
  }

  /**
   * 在列表上、气泡下面一层画尾巴。挂在 [org.thoughtcrime.securesms.conversation.colors.RecyclerViewColorizer] 后面，
   * 这样渐变色列表先打完洞，尾巴再画上去，不会被盖掉。
   */
  class Decoration : RecyclerView.ItemDecoration() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tail = Path()
    private val bubble = Path()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
      val density = parent.resources.displayMetrics.density
      for (child in parent.children) {
        if (child.visibility != View.VISIBLE || child.alpha <= 0f) {
          continue
        }

        val provider = parent.getChildViewHolder(child) as? Provider ?: child as? Provider ?: continue
        val spec = provider.getTellomiTail(parent) ?: continue
        val projection = spec.bubble

        tail.reset()
        bubble.reset()
        val cornerX = if (spec.towardsRight) projection.x + projection.width else projection.x
        addOutline(tail, cornerX, projection.y + projection.height, spec.towardsRight, density, spec.coverRadiusDp)
        projection.applyToPath(bubble)
        tail.op(bubble, Path.Op.DIFFERENCE)
        projection.release()

        val gradient = spec.gradient
        if (gradient != null && gradient.isGradient()) {
          val mask = gradient.chatBubbleMask
          mask.setBounds(0, 0, parent.width, parent.height)
          mask.alpha = (255 * child.alpha * spec.alpha).toInt()
          c.withClip(tail) { mask.draw(this) }
        } else {
          paint.color = spec.color
          paint.alpha = (Color.alpha(spec.color) * child.alpha * spec.alpha).toInt()
          c.drawPath(tail, paint)
        }
      }
    }
  }
}
