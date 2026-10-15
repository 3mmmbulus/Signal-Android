/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.ui.motion.TellomiHaptics
import org.signal.core.util.dp
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tellomi（交互审计 A-15「不能拖着连选」）：多选模式里长按一条消息，它切换选中并「拿起」拖选；手指不抬往上 / 往下拖，
 * 从起点到手指下那条之间的消息都设成同一个状态（起点原来没选就全选上，原来选了就全取消），退出范围的消息恢复原状；
 * 手指靠近列表上下边缘时自动滚。每多改一条轻震一下。
 *
 * Telegram 两端都有：安卓长按时 `ChatActivity.startMultiselect` → `RecyclerListView.startMultiselect`，iOS
 * `ChatHistoryListNode.selectionPanGesture`。Signal 不在多选模式时长按是回应浮层（它自己要拖着选表情），所以只在多选模式里拿起。
 */
class TellomiDragSelect(
  private val recycler: RecyclerView,
  private val adapter: ConversationAdapterV2,
  private val onSelectionChanged: (finished: Boolean) -> Unit
) : RecyclerView.OnItemTouchListener {

  private val edge = 72.dp
  private val maxStep = 18.dp

  private var active = false
  private var target = true
  private var startPosition = RecyclerView.NO_POSITION
  private var lastPosition = RecyclerView.NO_POSITION
  private var lastY = 0f

  /** 拖选开始前每条（按位置）是不是整条选中，退出范围时还原。 */
  private val original = HashMap<Int, Boolean>()

  private val autoScroll = object : Runnable {
    override fun run() {
      if (!active) return
      val step = scrollStep(lastY, recycler.height.toFloat(), edge.toFloat(), maxStep.toFloat())
      if (step != 0) {
        recycler.scrollBy(0, step)
        update(lastY)
        recycler.postOnAnimation(this)
      }
    }
  }

  /** 长按了 [itemView]（多选模式里）：切换这一条并开始拖选；这一条不能选时返回 false。 */
  fun start(itemView: View): Boolean {
    val row = recycler.findContainingItemView(itemView) ?: return false
    val position = positionOf(row)
    if (position == RecyclerView.NO_POSITION) return false
    val message = adapter.getConversationMessage(position) ?: return false
    val parts = selectableParts(message) ?: return false

    val wasSelected = adapter.selectedItems.containsAll(parts)
    target = !wasSelected
    original.clear()
    original[position] = wasSelected
    startPosition = position
    lastPosition = position
    active = true

    adapter.setSelected(parts, target)
    TellomiHaptics.perform(recycler, TellomiHaptics.Event.DRAG_START)
    onSelectionChanged(false)
    return true
  }

  override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
    if (!active) return false

    return when (e.actionMasked) {
      MotionEvent.ACTION_MOVE -> {
        update(e.y)
        true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        finish()
        false
      }
      else -> false
    }
  }

  override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
    when (e.actionMasked) {
      MotionEvent.ACTION_MOVE -> update(e.y)
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
    }
  }

  override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

  private fun update(y: Float) {
    lastY = y

    val child = recycler.findChildViewUnder(recycler.width / 2f, y.coerceIn(0f, recycler.height - 1f))
    val position = child?.let { positionOf(it) } ?: RecyclerView.NO_POSITION
    if (position != RecyclerView.NO_POSITION && position != lastPosition) {
      applyRange(position)
    }

    recycler.removeCallbacks(autoScroll)
    if (scrollStep(y, recycler.height.toFloat(), edge.toFloat(), maxStep.toFloat()) != 0) {
      recycler.postOnAnimation(autoScroll)
    }
  }

  private fun applyRange(position: Int) {
    val oldRange = min(startPosition, lastPosition)..max(startPosition, lastPosition)
    val newRange = min(startPosition, position)..max(startPosition, position)
    var changed = false

    for (p in oldRange) {
      if (p !in newRange) {
        changed = setRow(p, original[p] ?: continue) || changed
      }
    }
    for (p in newRange) {
      val message = adapter.getConversationMessage(p) ?: continue
      val parts = selectableParts(message) ?: continue
      if (p !in original) {
        original[p] = adapter.selectedItems.containsAll(parts)
      }
      changed = setRow(p, target) || changed
    }

    lastPosition = position
    if (changed) {
      TellomiHaptics.perform(recycler, TellomiHaptics.Event.SELECTION_TICK)
      onSelectionChanged(false)
    }
  }

  /** 列表是 ConcatAdapter（输入中提示 + 消息），这里只认消息适配器里的位置。 */
  private fun positionOf(row: View): Int {
    val holder = recycler.getChildViewHolder(row) ?: return RecyclerView.NO_POSITION
    return if (holder.bindingAdapter === adapter) holder.bindingAdapterPosition else RecyclerView.NO_POSITION
  }

  /** 把第 [position] 条设成 [selected]；真的变了返回 true。 */
  private fun setRow(position: Int, selected: Boolean): Boolean {
    val message = adapter.getConversationMessage(position) ?: return false
    val parts = selectableParts(message) ?: return false
    if (adapter.selectedItems.containsAll(parts) == selected && (selected || adapter.selectedItems.none { it in parts })) {
      return false
    }
    adapter.setSelected(parts, selected)
    return true
  }

  private fun finish() {
    if (!active) return
    active = false
    recycler.removeCallbacks(autoScroll)
    original.clear()
    onSelectionChanged(true)
  }

  companion object {
    /**
     * 拖选能动的部分：普通消息的全部部分。折叠的事件组、更新类消息、只在内存里的消息不参与（点一下照旧）。
     */
    @JvmStatic
    fun selectableParts(message: ConversationMessage): Set<MultiselectPart>? {
      val record = message.messageRecord
      if (record.isUpdate || record.isInMemoryMessageRecord || message.isActiveCollapsedHead) return null
      val parts = message.multiselectCollection.toSet()
      if (parts.any { it is MultiselectPart.CollapsedHead }) return null
      return parts
    }

    /** 手指离上 / 下边缘 [edge] 以内时每帧滚多少（越靠边越快，最多 [maxStep]）；不在边缘返回 0。 */
    @JvmStatic
    fun scrollStep(y: Float, height: Float, edge: Float, maxStep: Float): Int {
      if (height <= 0f || edge <= 0f) return 0
      return when {
        y < edge -> -(maxStep * ((edge - y) / edge).coerceIn(0f, 1f)).roundToInt()
        y > height - edge -> (maxStep * ((y - (height - edge)) / edge).coerceIn(0f, 1f)).roundToInt()
        else -> 0
      }
    }
  }
}
