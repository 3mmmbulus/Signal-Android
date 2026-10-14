package org.thoughtcrime.securesms.conversation

import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.util.toLocalDateTime
import org.thoughtcrime.securesms.util.toMillis
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

class ScheduleMessageContextMenu {

  companion object {

    private val presetHours = arrayOf(8, 12, 18, 21)

    /**
     * Tellomi（tellomi/tellomi#1601，M-4，O8 照 Telegram 两端）：菜单里只有一项「定时发送」（收藏里「设置提醒」），
     * 点了 `action(-1)` 出日 / 时 / 分滚轮；不再给上游的整点预设（[getNextScheduleTimes] 留着不用）。
     */
    @JvmStatic
    @JvmOverloads
    fun show(anchor: View, container: ViewGroup, isReminder: Boolean = false, action: (Long) -> Unit): SignalContextMenu {
      val title = anchor.context.getString(if (isReminder) R.string.TellomiScheduleTimePicker__set_reminder else R.string.ScheduleMessageTimePickerBottomSheet__dialog_title)
      val actionItems = listOf(
        ActionItem(R.drawable.symbol_calendar_24, title) {
          action(-1)
        }
      )

      return SignalContextMenu.Builder(anchor, container)
        .offsetX(12.dp)
        .offsetY(12.dp)
        .preferredVerticalPosition(SignalContextMenu.VerticalPosition.ABOVE)
        .show(actionItems)
    }

    @DrawableRes
    private fun getIconForTime(timeMs: Long): Int {
      val dateTime = timeMs.toLocalDateTime()
      return if (dateTime.hour >= 18) {
        R.drawable.ic_nighttime_26
      } else {
        R.drawable.ic_daytime_24
      }
    }

    private fun getNextScheduleTimes(currentTimeMs: Long): List<Long> {
      var currentDateTime = currentTimeMs.toLocalDateTime()

      val timestampList = ArrayList<Long>(5)
      var presetIndex = presetHours.indexOfFirst { it > currentDateTime.hour }
      if (presetIndex == -1) {
        currentDateTime = currentDateTime.plusDays(1)
        presetIndex = 0
      }
      currentDateTime = currentDateTime.withMinute(0).withSecond(0)
      while (timestampList.size < 3) {
        currentDateTime = currentDateTime.withHour(presetHours[presetIndex])
        timestampList += currentDateTime.toMillis()
        presetIndex++
        if (presetIndex >= presetHours.size) {
          presetIndex = 0
          currentDateTime = currentDateTime.plusDays(1)
        }
      }

      val now = currentTimeMs.toLocalDateTime()
      if (now.dayOfWeek == DayOfWeek.FRIDAY || now.dayOfWeek == DayOfWeek.SATURDAY) {
        val nextMonday = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
          .withHour(8)
          .withMinute(0)
          .withSecond(0)
        timestampList += nextMonday.toMillis()
      }

      timestampList += -1

      return timestampList.reversed()
    }
  }
}
