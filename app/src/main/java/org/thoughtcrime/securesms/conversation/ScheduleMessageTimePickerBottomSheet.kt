package org.thoughtcrime.securesms.conversation

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.FixedRoundedCornerBottomSheetDialogFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.fragments.findListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bottom sheet dialog that allows selecting a timestamp after the current time for
 * scheduling a message send.
 *
 * Will call [ScheduleCallback.onScheduleSend] with the selected time, if called with [showSchedule]
 * Will call [RescheduleCallback.onReschedule] with the selected time, if called with [showReschedule]
 *
 * Tellomi（tellomi/tellomi#1601，M-4，O8 照 Telegram 两端）：上游是「日期」「时间」两行、各自弹系统的日期 / 时间选择器，
 * 加时区说明和「选了过去的时间」确认；换成 Telegram 安卓 `AlertsCreator.createScheduleDatePickerDialog` 的样子
 * （只读，代码是自己写的）：标题（收藏里「设置提醒」）、日 / 时 / 分三个滚轮、整宽按钮「今天 HH:mm 发送 / 某天 HH:mm 发送」；
 * 早于最早时间的选不到，点按钮时再推一次、不提示。规则见 [TellomiScheduleWheels]。
 */
class ScheduleMessageTimePickerBottomSheet : FixedRoundedCornerBottomSheetDialogFragment() {
  override val peekHeightPercentage: Float = 0.66f
  override val themeResId: Int = R.style.Widget_Signal_FixedRoundedCorners_Messages

  private lateinit var dayPicker: NumberPicker
  private lateinit var hourPicker: NumberPicker
  private lateinit var minutePicker: NumberPicker
  private lateinit var confirmButton: MaterialButton

  /** 打开时的那一刻：天轮上的「今天」就是这一天（跨过午夜也不变，转屏 / 进程重建后从保存的状态恢复）。 */
  private var baseTime: Long = 0

  private val isReminder: Boolean
    get() = arguments?.getBoolean(KEY_REMINDER) == true

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.inflate(R.layout.tellomi_schedule_time_wheels_bottom_sheet, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    baseTime = savedInstanceState?.getLong(STATE_BASE_TIME) ?: System.currentTimeMillis()
    val now = baseTime
    view.findViewById<TextView>(R.id.tellomi_schedule_title).setText(
      if (isReminder) R.string.TellomiScheduleTimePicker__set_reminder else R.string.ScheduleMessageTimePickerBottomSheet__dialog_title
    )
    dayPicker = view.findViewById(R.id.tellomi_schedule_day)
    hourPicker = view.findViewById(R.id.tellomi_schedule_hour)
    minutePicker = view.findViewById(R.id.tellomi_schedule_minute)
    confirmButton = view.findViewById(R.id.tellomi_schedule_confirm)

    dayPicker.minValue = 0
    dayPicker.maxValue = TellomiScheduleWheels.MAX_DAYS
    dayPicker.wrapSelectorWheel = false
    dayPicker.displayedValues = dayLabels(now)
    hourPicker.minValue = 0
    hourPicker.maxValue = 23
    hourPicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
    minutePicker.minValue = 0
    minutePicker.maxValue = 59
    minutePicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }

    // 滚轮里的输入框 id 都一样，让系统自己存会把三个滚轮的字存乱：自己存选中的那一刻（onSaveInstanceState）。
    listOf(dayPicker, hourPicker, minutePicker).forEach { it.isSaveFromParentEnabled = false }

    val restored = savedInstanceState?.takeIf { it.containsKey(STATE_SELECTED_TIME) }?.getLong(STATE_SELECTED_TIME)
    val initialTime = restored ?: arguments?.takeIf { it.containsKey(KEY_INITIAL_TIME) }?.getLong(KEY_INITIAL_TIME)
    val startTime = TellomiScheduleWheels.clamp(initialTime ?: TellomiScheduleWheels.defaultTime(System.currentTimeMillis()), System.currentTimeMillis())
    show(TellomiScheduleWheels.selection(startTime, baseTime))

    val onChange = NumberPicker.OnValueChangeListener { _, _, _ ->
      show(TellomiScheduleWheels.settle(currentSelection(), baseTime, System.currentTimeMillis()))
    }
    dayPicker.setOnValueChangedListener(onChange)
    hourPicker.setOnValueChangedListener(onChange)
    minutePicker.setOnValueChangedListener(onChange)

    confirmButton.setOnClickListener { scheduleMessageSend() }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    if (::dayPicker.isInitialized) {
      outState.putLong(STATE_BASE_TIME, baseTime)
      outState.putLong(STATE_SELECTED_TIME, TellomiScheduleWheels.time(currentSelection(), baseTime))
    }
  }

  private fun currentSelection(): TellomiScheduleWheels.Selection {
    return TellomiScheduleWheels.Selection(dayOffset = dayPicker.value, hour = hourPicker.value, minute = minutePicker.value)
  }

  private fun show(selection: TellomiScheduleWheels.Selection) {
    dayPicker.value = selection.dayOffset
    hourPicker.value = selection.hour
    minutePicker.value = selection.minute
    confirmButton.text = confirmText(TellomiScheduleWheels.time(selection, baseTime), System.currentTimeMillis())
  }

  /** 天轮上的字：「今天」，然后「周四 10月15日」，跨年的带年份（同 TG 安卓）。格式各建一次。 */
  private fun dayLabels(baseMs: Long): Array<String> {
    val locale = Locale.getDefault()
    val baseDay = Instant.ofEpochMilli(baseMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val sameYear = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
    val otherYear = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdyyyy"), locale)
    val today = getString(R.string.DateUtils_today)
    return Array(TellomiScheduleWheels.MAX_DAYS + 1) { offset ->
      if (offset == 0) {
        today
      } else {
        val day = baseDay.plusDays(offset.toLong())
        (if (day.year == baseDay.year) sameYear else otherYear).format(day)
      }
    }
  }

  private fun confirmText(timeMs: Long, now: Long): String {
    val time = DateFormat.getTimeFormat(requireContext()).format(timeMs)
    val dateTime = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
    return when (TellomiScheduleWheels.day(timeMs, now)) {
      TellomiScheduleWheels.Day.TODAY -> getString(if (isReminder) R.string.TellomiScheduleTimePicker__remind_today_at else R.string.TellomiScheduleTimePicker__send_today_at, time)
      TellomiScheduleWheels.Day.THIS_YEAR, TellomiScheduleWheels.Day.OTHER_YEAR -> {
        val skeleton = if (TellomiScheduleWheels.day(timeMs, now) == TellomiScheduleWheels.Day.THIS_YEAR) "MMMd" else "MMMdyyyy"
        val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), Locale.getDefault()).format(dateTime)
        getString(if (isReminder) R.string.TellomiScheduleTimePicker__remind_on_at else R.string.TellomiScheduleTimePicker__send_on_at, date, time)
      }
    }
  }

  private fun getSelectedTimestamp(): Long {
    return TellomiScheduleWheels.clamp(TellomiScheduleWheels.time(currentSelection(), baseTime), System.currentTimeMillis())
  }

  private fun scheduleMessageSend() {
    dismissAllowingStateLoss()
    val messageId = arguments?.takeIf { it.containsKey(KEY_MESSAGE_ID) }?.getLong(KEY_MESSAGE_ID)
    if (messageId == null) {
      findListener<ScheduleCallback>()?.onScheduleSend(getSelectedTimestamp())
    } else {
      val selectedTime = getSelectedTimestamp()
      if (selectedTime != arguments?.getLong(KEY_INITIAL_TIME)) {
        findListener<RescheduleCallback>()?.onReschedule(selectedTime, messageId)
      }
    }
  }

  interface ScheduleCallback {
    fun onScheduleSend(scheduledTime: Long)
  }

  interface RescheduleCallback {
    fun onReschedule(scheduledTime: Long, messageId: Long)
  }

  companion object {

    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_INITIAL_TIME = "initial_time"
    private const val KEY_REMINDER = "tellomi_reminder"
    private const val STATE_BASE_TIME = "tellomi_base_time"
    private const val STATE_SELECTED_TIME = "tellomi_selected_time"

    /** @param isReminder 收藏（给自己）里：标题「设置提醒」、按钮「今天 HH:mm 提醒」（同 Telegram 两端）。 */
    @JvmStatic
    @JvmOverloads
    fun showSchedule(fragmentManager: FragmentManager, isReminder: Boolean = false) {
      val fragment = ScheduleMessageTimePickerBottomSheet().apply {
        arguments = Bundle().apply { putBoolean(KEY_REMINDER, isReminder) }
      }

      fragment.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }

    @JvmStatic
    @JvmOverloads
    fun showReschedule(fragmentManager: FragmentManager, messageId: Long, initialTime: Long, isReminder: Boolean = false) {
      val args = Bundle().apply {
        putLong(KEY_MESSAGE_ID, messageId)
        putLong(KEY_INITIAL_TIME, initialTime)
        putBoolean(KEY_REMINDER, isReminder)
      }

      val fragment = ScheduleMessageTimePickerBottomSheet().apply {
        arguments = args
      }

      fragment.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }
}
