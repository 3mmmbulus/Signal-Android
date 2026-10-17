/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.animation.Interpolator
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiMotion
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.conversation.mutiselect.Multiselectable
import org.thoughtcrime.securesms.conversation.v2.items.TellomiBubbleTail
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * Tellomi（M9 发送动画；需求 `bubbles-and-motion.md` 3.5，设计规范 `bubbles-and-motion-design.md` 第 4 节）：
 * 点发送后，新气泡从输入框里飞到会话里它的位置。
 *
 * 照 Telegram 两端的做法（只读机制，GPLv2，一行不搬；Android `ui/TextMessageEnterTransition.java` +
 * `ui/recyclerview/ChatListItemAnimator.animateAddImpl`，iOS `TelegramUI/Sources/ChatMessageTransitionNode.swift` +
 * `ChatMessageBubbleItemNode.animateContentFromTextInputField`）：
 * - **输入框的框变成气泡**：气泡的框从输入区那个圆角框插值到气泡最终的位置和大小，左右边跟横向进度、上下边跟纵向进度；
 *   圆角和尾巴不变形，尾巴从头就画着（两端都一样）；
 * - **一道弧线**：横向前快（[TellomiMotion.SendFlight.X_CURVE]）、纵向跟列表（[TellomiMotion.SendFlight.Y_CURVE]），字的原点同样走；
 * - **字**：字号从输入框的缩放到气泡的；输入框里的字（点发送那一刻画成的图）淡出、气泡里的字淡入，不做颜色插值；
 *   气泡底色连同尾巴、时间、送达状态在前 [TellomiMotion.SendFlight.BUBBLE_FADE_IN_MS] 由透明到不透明；
 * - **输入框**：照旧点下就清空；清空前把输入框（不带字）和那几行字各画成一张图，新的一行进列表之前一直画在原处，
 *   看起来输入框还是点发送那一刻的样子（清空后冒出来的相机、麦克风不会从字底下透出来）；起飞后这张输入框图在前
 *   [TellomiMotion.SendFlight.BUBBLE_FADE_IN_MS] 里淡掉，露出已经清空的输入框；输入框里的字（占位字）随飞行进度淡回
 *   （Telegram 安卓同样是 0 → 1 线性；它是等格子准备好才清空、再用一张发送键的图盖住按钮的切换，这里换成冻住整块输入框）。
 *
 * 新的一行在绑定时就藏起来（[holdIfSending]，一帧都不先露），落定后再露出。它照常和别的行一起从下面滑上来（日期条跟着它走，
 * 不会先跑到终点压住还在往上滑的内容），飞的落点是它滑完以后的位置（量位置时减掉它的 translationY，Telegram 安卓同样按
 * cell 的布局位置、不算它自己的滑入）；
 * 等不到它（写失败、不是纯文字的那种行、列表不在底部、页面关了）就不飞：那几行字原地淡掉，这一行照常出现。
 * 飞的东西画在 decorView 的 [android.view.ViewOverlay] 上（在输入区上面），每帧重新量落点，列表在动也跟得上。
 *
 * 只由 [start] 创建，什么时候能飞由调用方判断（纯文字、列表在底部、[TellomiMotion.allowsSendFlight]）。
 */
class TellomiSendTransition private constructor(
  private val host: View,
  private val recycler: RecyclerView,
  private val compose: View,
  private val source: Source,
  private val sentAtMillis: Long,
  private val body: String,
  private val matchAnyBody: Boolean
) : Choreographer.FrameCallback {

  /** 点发送那一刻输入框的样子；坐标都是窗口坐标。 */
  class Source(
    /** 输入框里那几行字（输入框的 Layout 画成的图，图的原点 = Layout 的原点）。 */
    val text: Bitmap,
    /** 点发送那一刻的输入框，不带字（[box] 那么大）。 */
    val field: Bitmap?,
    /** Layout 的原点在窗口里的位置。 */
    val textOrigin: PointF,
    val textSizePx: Float,
    /** 输入区那个圆角框（字在里面）。 */
    val box: RectF
  )

  /** 能飞的那种行（V2 纯文字气泡）。 */
  interface Target {
    val tellomiSendMessageId: Long
    val tellomiSendBubble: View
    val tellomiSendBody: TextView

    /** 有气泡底色（大号表情没有，不飞）。 */
    fun tellomiSendCanFly(): Boolean

    /** 这一条画不画尾巴（一组的最后一条）。 */
    fun tellomiSendHasTail(): Boolean

    /** 不画气泡底色，把这一行画到 [canvas] 上（字、时间、送达状态）。底色和尾巴由飞的那一层自己画，好跟着框变形。 */
    fun tellomiSendDrawContent(canvas: Canvas)

    /** 气泡的形状、朝向、颜色（不管画不画尾巴）。坐标相对于 [parent]。 */
    fun tellomiSendBubbleSpec(parent: RecyclerView): TellomiBubbleTail.Spec?
  }

  private enum class Phase(val holdsRow: Boolean) {
    WAITING(true),
    FLYING(true),
    FADING_OUT(false),
    DONE(false)
  }

  private var phase = Phase.WAITING
  private val createdAt = SystemClock.uptimeMillis()
  private var flyStart = 0L
  private var fadeStart = 0L

  /** 认领到的那条消息；认领之后它那一行一直藏着，直到落定或取消。 */
  private var messageId = NO_MESSAGE

  /** 为这一次藏起来的行（换过 ViewHolder 可能不止一个）；收尾时全部放回来，免得回收后带着透明度去显示别的消息。 */
  private val hiddenRows = mutableListOf<WeakReference<View>>()

  /** 这一次想让输入框是多透明：等待时 0，飞的时候随进度淡回，收尾 1。几次连着发时取最小的（[applyComposeAlpha]）。 */
  private var composeAlpha = 0f

  private val density = host.resources.displayMetrics.density
  private val hostOrigin = IntArray(2)
  private val location = IntArray(2)

  // 起飞时量好的。
  private var content: Bitmap? = null
  private var bubbleTextSizePx = 1f
  private var radii = FloatArray(8)
  private var towardsRight = true
  private var hasTail = true
  private var color = Color.TRANSPARENT
  private var mask: Drawable? = null

  // 每帧算出来的，host 坐标。
  private val bubbleRect = RectF()
  private val textOrigin = PointF()
  private val contentOffset = PointF()
  private val recyclerRect = RectF()
  private var scale = 1f
  private var bubbleAlpha = 0f
  private var copyAlpha = 1f

  private val overlayDrawable = FlightDrawable()

  init {
    active += this
    host.getLocationInWindow(hostOrigin)
    overlayDrawable.setBounds(0, 0, host.width, host.height)
    host.overlay.add(overlayDrawable)
    applyComposeAlpha(compose)
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (phase == Phase.DONE) {
      return
    }

    if (!host.isAttachedToWindow || !recycler.isAttachedToWindow) {
      finish()
      return
    }

    val now = SystemClock.uptimeMillis()
    when (phase) {
      Phase.WAITING -> {
        val claimed = findClaimedRow()
        if (claimed != null && claimed.isLaidOut) {
          val target = recycler.getChildViewHolder(claimed) as? Target
          if (target != null && isOnScreen(target)) {
            takeOff(target, now)
          } else {
            // 不是能飞的那种行（带链接卡片等），或者不在屏幕上：照常出现。
            fadeOut(now)
          }
        } else if (now - createdAt > TellomiMotion.SendFlight.WAIT_FOR_ROW_MS) {
          fadeOut(now)
        }
      }

      Phase.FLYING -> {
        val target = findClaimedRow()?.let { recycler.getChildViewHolder(it) as? Target }
        if (target == null) {
          // 那一行没了（被回收、被删）：没地方落，收掉。
          finish()
          return
        }
        val t = ((now - flyStart).toFloat() / TellomiMotion.SendFlight.DURATION_MS).coerceIn(0f, 1f)
        layOut(target, t)
        composeAlpha = t
        applyComposeAlpha(compose)
        if (t >= 1f) {
          finish()
          return
        }
      }

      Phase.FADING_OUT -> {
        val t = ((now - fadeStart).toFloat() / TellomiMotion.SendFlight.CANCEL_FADE_MS).coerceIn(0f, 1f)
        copyAlpha = 1f - t
        composeAlpha = t
        applyComposeAlpha(compose)
        if (t >= 1f) {
          finish()
          return
        }
      }

      Phase.DONE -> return
    }

    overlayDrawable.invalidateSelf()
    Choreographer.getInstance().postFrameCallback(this)
  }

  /** 发送出错、页面要走：还没起飞就原地淡掉，飞到一半就直接收掉。 */
  @MainThread
  fun cancel() {
    when (phase) {
      Phase.WAITING -> fadeOut(SystemClock.uptimeMillis())
      Phase.FLYING -> finish()
      Phase.FADING_OUT, Phase.DONE -> Unit
    }
  }

  private fun matches(record: MessageRecord): Boolean {
    return record.isOutgoing && record.dateSent >= sentAtMillis && (matchAnyBody || record.body == body)
  }

  private fun findClaimedRow(): View? {
    if (messageId == NO_MESSAGE) {
      return null
    }
    for (index in 0 until recycler.childCount) {
      val child = recycler.getChildAt(index)
      // V2 rows are Multiselectable through their view holder, older rows through the view (as MultiselectItemDecoration does).
      val item = recycler.getChildViewHolder(child) as? Multiselectable ?: child as? Multiselectable
      if (item?.conversationMessage?.messageRecord?.id == messageId) {
        return child
      }
    }
    return null
  }

  private fun isOnScreen(target: Target): Boolean {
    val bubble = target.tellomiSendBubble
    if (!bubble.isLaidOut || bubble.width <= 0 || bubble.height <= 0 || !target.tellomiSendCanFly()) {
      return false
    }
    bubble.getLocationInWindow(location)
    val bubbleTop = location[1] - slideOffset(bubble)
    recycler.getLocationInWindow(location)
    return bubbleTop >= location[1] && bubbleTop + bubble.height <= location[1] + recycler.height
  }

  /** 这一行还在从下面滑上来的那一段（条目动画的 translationY）；落点按滑完以后的位置算。 */
  private fun slideOffset(bubble: View): Float = bubble.rootViewIn(recycler).translationY

  private fun takeOff(target: Target, now: Long) {
    val spec = target.tellomiSendBubbleSpec(recycler)
    if (spec == null) {
      fadeOut(now)
      return
    }
    radii = spec.bubble.corners?.toRadii() ?: FloatArray(8)
    towardsRight = spec.towardsRight
    color = spec.color
    mask = spec.gradient?.takeIf { it.isGradient() }?.chatBubbleMask
    spec.bubble.release()
    hasTail = target.tellomiSendHasTail()
    bubbleTextSizePx = target.tellomiSendBody.textSize.takeIf { it > 0f } ?: source.textSizePx

    val bubble = target.tellomiSendBubble
    content = try {
      captureContent(target, bubble)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Could not draw the new message for the send animation", e)
      null
    }
    if (content == null) {
      fadeOut(now)
      return
    }

    phase = Phase.FLYING
    flyStart = now
    layOut(target, 0f)
  }

  /** 这一行不带底色画成图，裁到气泡那一块（字、时间、送达状态都在里面）。 */
  private fun captureContent(target: Target, bubble: View): Bitmap {
    val row = bubble.rootViewIn(recycler)
    var left = 0f
    var top = 0f
    var view: View = bubble
    while (view !== row) {
      left += view.left - view.scrollX
      top += view.top - view.scrollY
      view = view.parent as View
    }
    val bitmap = Bitmap.createBitmap(bubble.width, bubble.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.translate(-left, -top)
    target.tellomiSendDrawContent(canvas)
    return bitmap
  }

  /** 这一帧气泡框、字的原点、缩放、透明度。[t] 是线性的时间进度。 */
  private fun layOut(target: Target, t: Float) {
    host.getLocationInWindow(hostOrigin)
    val bubble = target.tellomiSendBubble
    val text = target.tellomiSendBody

    val slide = slideOffset(bubble)
    bubble.getLocationInWindow(location)
    val toBubble = RectF(
      (location[0] - hostOrigin[0]).toFloat(),
      location[1] - hostOrigin[1] - slide,
      (location[0] - hostOrigin[0] + bubble.width).toFloat(),
      location[1] - hostOrigin[1] - slide + bubble.height
    )
    text.getLocationInWindow(location)
    val toText = PointF(
      (location[0] - hostOrigin[0] + text.totalPaddingLeft).toFloat(),
      location[1] - hostOrigin[1] - slide + text.totalPaddingTop
    )
    recycler.getLocationInWindow(location)
    recyclerRect.set(
      (location[0] - hostOrigin[0]).toFloat(),
      (location[1] - hostOrigin[1]).toFloat(),
      (location[0] - hostOrigin[0] + recycler.width).toFloat(),
      (location[1] - hostOrigin[1] + recycler.height).toFloat()
    )

    val frame = frameAt(
      t = t,
      fromBox = source.box.offsetBy(-hostOrigin[0].toFloat(), -hostOrigin[1].toFloat()),
      fromText = PointF(source.textOrigin.x - hostOrigin[0], source.textOrigin.y - hostOrigin[1]),
      fromTextSize = source.textSizePx,
      toBubble = toBubble,
      toText = toText,
      toTextSize = bubbleTextSizePx
    )
    bubbleRect.set(frame.bubble)
    textOrigin.set(frame.textOrigin)
    scale = frame.scale
    bubbleAlpha = frame.bubbleAlpha
    copyAlpha = 1f - frame.bubbleAlpha
    contentOffset.set(toText.x - toBubble.left, toText.y - toBubble.top)
  }

  private fun fadeOut(now: Long) {
    phase = Phase.FADING_OUT
    fadeStart = now
    revealRow()
  }

  private fun hide(row: View) {
    row.alpha = 0f
    if (hiddenRows.none { it.get() === row }) {
      hiddenRows += WeakReference(row)
    }
  }

  /** [row] 换去显示别的消息了：要是它是这一次藏的，放出来。 */
  private fun release(row: View) {
    if (hiddenRows.removeAll { it.get() === row }) {
      row.alpha = 1f
    }
  }

  private fun revealRow() {
    hiddenRows.forEach { it.get()?.alpha = 1f }
    hiddenRows.clear()
    messageId = NO_MESSAGE
    recycler.invalidate()
  }

  private fun finish() {
    if (phase == Phase.DONE) {
      return
    }
    phase = Phase.DONE
    revealRow()
    host.overlay.remove(overlayDrawable)
    active -= this
    applyComposeAlpha(compose)
    content = null
  }

  /** 飞的那一层：气泡底色 + 尾巴（跟着框变形）、气泡里的内容、输入框里的那几行字。 */
  private inner class FlightDrawable : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bubblePath = Path()
    private val tailPath = Path()
    private val clampedRadii = FloatArray(8)

    override fun draw(canvas: Canvas) {
      when (phase) {
        Phase.WAITING, Phase.FADING_OUT -> {
          drawField(canvas)
          drawCopy(canvas, source.textOrigin.x - hostOrigin[0], source.textOrigin.y - hostOrigin[1], 1f)
        }
        Phase.FLYING -> {
          drawField(canvas)
          drawFlight(canvas)
        }
        Phase.DONE -> Unit
      }
    }

    private fun drawFlight(canvas: Canvas) {
      val maxRadius = min(bubbleRect.width(), bubbleRect.height()) / 2f
      for (i in radii.indices) {
        clampedRadii[i] = min(radii[i], maxRadius)
      }
      bubblePath.reset()
      bubblePath.addRoundRect(bubbleRect, clampedRadii, Path.Direction.CW)
      tailPath.reset()
      if (hasTail) {
        val cornerX = if (towardsRight) bubbleRect.right else bubbleRect.left
        TellomiBubbleTail.addOutline(tailPath, cornerX, bubbleRect.bottom, towardsRight, density)
        tailPath.op(bubblePath, Path.Op.UNION)
      } else {
        tailPath.set(bubblePath)
      }

      val gradient = mask
      if (gradient != null) {
        gradient.setBounds(recyclerRect.left.toInt(), recyclerRect.top.toInt(), recyclerRect.right.toInt(), recyclerRect.bottom.toInt())
        gradient.alpha = (255 * bubbleAlpha).toInt()
        canvas.withClip(tailPath) { gradient.draw(this) }
      } else {
        fillPaint.color = color
        fillPaint.alpha = (Color.alpha(color) * bubbleAlpha).toInt()
        canvas.drawPath(tailPath, fillPaint)
      }

      canvas.withClip(bubbleRect) {
        content?.let { bitmap ->
          withSave {
            translate(textOrigin.x - scale * contentOffset.x, textOrigin.y - scale * contentOffset.y)
            scale(scale, scale)
            bitmapPaint.alpha = (255 * bubbleAlpha).toInt()
            drawBitmap(bitmap, 0f, 0f, bitmapPaint)
          }
        }
        drawCopy(this, textOrigin.x, textOrigin.y, scale * bubbleTextSizePx / source.textSizePx)
      }
    }

    /** 冻住的输入框：等的时候盖住已经清空的输入框，起飞后和输入框里的字一起淡掉。 */
    private fun drawField(canvas: Canvas) {
      val field = source.field ?: return
      if (copyAlpha <= 0f) {
        return
      }
      bitmapPaint.alpha = (255 * copyAlpha).toInt()
      canvas.drawBitmap(field, source.box.left - hostOrigin[0], source.box.top - hostOrigin[1], bitmapPaint)
    }

    private fun drawCopy(canvas: Canvas, x: Float, y: Float, copyScale: Float) {
      if (copyAlpha <= 0f) {
        return
      }
      canvas.withSave {
        translate(x, y)
        scale(copyScale, copyScale)
        bitmapPaint.alpha = (255 * copyAlpha).toInt()
        drawBitmap(source.text, 0f, 0f, bitmapPaint)
      }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
  }

  /** 一帧的几何：气泡框、字的原点、字的缩放、底色的透明度（host 坐标）。 */
  @VisibleForTesting
  data class Frame(val bubble: RectF, val textOrigin: PointF, val scale: Float, val bubbleAlpha: Float)

  companion object {
    private val TAG = Log.tag(TellomiSendTransition::class.java)

    private const val NO_MESSAGE = -1L

    private val X_INTERPOLATOR: Interpolator = TellomiMotion.SendFlight.X_CURVE.let { PathInterpolatorCompat.create(it[0], it[1], it[2], it[3]) }
    private val Y_INTERPOLATOR: Interpolator = TellomiMotion.SendFlight.Y_CURVE.let { PathInterpolatorCompat.create(it[0], it[1], it[2], it[3]) }

    private val active = mutableListOf<TellomiSendTransition>()

    /**
     * 点发送、清空输入框之前调用：把输入框里的字画下来，输入框变透明，等新的一行进列表就飞。
     * [compose] 是输入框（EditText），[composeBox] 是它外面那个圆角框。拿不到输入框的排版就返回 null（不飞）。
     */
    @JvmStatic
    @MainThread
    fun start(host: View, recycler: RecyclerView, compose: TextView, composeBox: View, body: String, matchAnyBody: Boolean): TellomiSendTransition? {
      val layout = compose.layout ?: return null
      if (layout.width <= 0 || layout.height <= 0 || compose.lineCount !in 1..TellomiMotion.SendFlight.MAX_LINES || compose.scrollY != 0) {
        return null
      }

      val text = try {
        Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888).also { bitmap ->
          compose.paint.color = compose.currentTextColor
          layout.draw(Canvas(bitmap))
        }
      } catch (e: RuntimeException) {
        Log.w(TAG, "Could not draw the input field for the send animation", e)
        return null
      }

      // 输入框不带字的样子：输入框那个圆角框只是一块底，表情键、字、框里的按钮都是它的兄弟，所以画整个输入区、裁到框那一块；
      // 字设成透明再画（父 View 画子 View 时才用子 View 的透明度）。
      val field = try {
        val panel = composeBox.parent as? View ?: composeBox
        val alpha = compose.alpha
        compose.alpha = 0f
        try {
          Bitmap.createBitmap(composeBox.width, composeBox.height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            if (panel !== composeBox) {
              canvas.translate(-composeBox.left.toFloat(), -composeBox.top.toFloat())
            }
            panel.draw(canvas)
          }
        } finally {
          compose.alpha = alpha
        }
      } catch (e: RuntimeException) {
        Log.w(TAG, "Could not draw the input field for the send animation", e)
        null
      }

      val origin = IntArray(2)
      compose.getLocationInWindow(origin)
      val textOrigin = PointF(
        (origin[0] + compose.totalPaddingLeft - compose.scrollX).toFloat(),
        (origin[1] + compose.totalPaddingTop - compose.scrollY).toFloat()
      )
      composeBox.getLocationInWindow(origin)
      val box = RectF(origin[0].toFloat(), origin[1].toFloat(), (origin[0] + composeBox.width).toFloat(), (origin[1] + composeBox.height).toFloat())

      return TellomiSendTransition(
        host = host,
        recycler = recycler,
        compose = compose,
        source = Source(text, field, textOrigin, compose.textSize, box),
        sentAtMillis = System.currentTimeMillis(),
        body = body,
        matchAnyBody = matchAnyBody
      )
    }

    /**
     * 绑定一行时问：这一行是不是刚发出、正等着飞过来的那一条；是就先藏起来（一帧都不先露）。
     * 只认点发送之后写进库的自己的消息（`dateSent` 不早于点发送的那一刻），正文对得上，先发的先认。
     */
    @JvmStatic
    @MainThread
    fun holdIfSending(record: MessageRecord, row: View) {
      if (active.isEmpty()) {
        return
      }
      if (!record.isOutgoing) {
        active.forEach { it.release(row) }
        return
      }
      val transition = active.firstOrNull { it.phase.holdsRow && it.messageId == record.id }
        ?: active.firstOrNull { it.phase == Phase.WAITING && it.messageId == NO_MESSAGE && it.matches(record) }?.also { it.messageId = record.id }
      if (transition != null) {
        transition.hide(row)
      } else {
        active.forEach { it.release(row) }
      }
    }

    /** 输入框的透明度：还在等、在飞的那几次里最透明的那个；都完了就是 1。 */
    private fun applyComposeAlpha(compose: View) {
      compose.alpha = active.filter { it.compose === compose }.minOfOrNull { it.composeAlpha } ?: 1f
    }

    /** 页面关了：全部立刻收掉，藏着的行放回来。 */
    @JvmStatic
    @MainThread
    fun cancelAll() {
      active.toList().forEach { it.finish() }
    }

    @VisibleForTesting
    val activeCountForTesting: Int get() = active.size

    /**
     * 第 [t]（0..1，线性时间）帧：字的原点横向按 [X_INTERPOLATOR]、纵向按 [Y_INTERPOLATOR] 从输入框走到气泡；字号同横向进度缩放；
     * 气泡框的左右边跟横向进度、上下边跟纵向进度，从输入区的框变到气泡；底色在前 [TellomiMotion.SendFlight.BUBBLE_FADE_IN_MS] 淡入。
     */
    @VisibleForTesting
    fun frameAt(t: Float, fromBox: RectF, fromText: PointF, fromTextSize: Float, toBubble: RectF, toText: PointF, toTextSize: Float): Frame {
      val time = t.coerceIn(0f, 1f)
      val px = X_INTERPOLATOR.getInterpolation(time)
      val py = Y_INTERPOLATOR.getInterpolation(time)
      val bubble = RectF(
        lerp(fromBox.left, toBubble.left, px),
        lerp(fromBox.top, toBubble.top, py),
        lerp(fromBox.right, toBubble.right, px),
        lerp(fromBox.bottom, toBubble.bottom, py)
      )
      val textOrigin = PointF(lerp(fromText.x, toText.x, px), lerp(fromText.y, toText.y, py))
      val scale = lerp(fromTextSize / toTextSize, 1f, px)
      val fadeIn = TellomiMotion.SendFlight.BUBBLE_FADE_IN_MS.toFloat() / TellomiMotion.SendFlight.DURATION_MS
      val bubbleAlpha = (time / fadeIn).coerceIn(0f, 1f)
      return Frame(bubble, textOrigin, scale, bubbleAlpha)
    }

    private fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress

    private fun RectF.offsetBy(dx: Float, dy: Float): RectF = RectF(this).apply { offset(dx, dy) }

    /** [this] 所在的那一行（列表的直接子 View）。 */
    private fun View.rootViewIn(recycler: RecyclerView): View {
      var view: View = this
      while (view.parent !== recycler) {
        view = view.parent as? View ?: return this
      }
      return view
    }
  }
}
