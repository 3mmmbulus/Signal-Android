/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.edit

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.DropdownMenus
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalIcon
import org.signal.core.ui.compose.SignalIcons
import org.signal.mediasend.R
import org.signal.mediasend.test.TestTags
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1601，M-4）：发给自己（收藏）时「定时发送」叫「设置提醒」（同 Telegram 两端 `SetReminder`）。
 * 由宿主按这次发给谁提供；默认不是。
 */
val LocalTellomiScheduleIsReminder = compositionLocalOf { false }

/**
 * Tellomi（#1601，O8 照 Telegram 两端）：长按发送菜单里只有一项「定时发送」，点了直接出日 / 时 / 分滚轮选时间；
 * 不再给上游的三个整点预设（[ScheduleSendOption.forCurrentTime] 留着不用）。
 */
internal val TellomiScheduleSendOptions: List<ScheduleSendOption> = listOf(ScheduleSendOption.PickTime)

/**
 * Renders the label for a scheduled send time, e.g. "Tomorrow at 8:00 AM". Injected because localized date formatting
 * lives with the host app.
 */
val LocalScheduledSendTimeFormatter = compositionLocalOf<(Long) -> String> {
  { timeMs ->
    DateTimeFormatter
      .ofLocalizedDateTime(FormatStyle.SHORT)
      .format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
  }
}

/**
 * A time the user can schedule a send for, offered by [ScheduleSendMenu].
 */
sealed interface ScheduleSendOption {

  /**
   * One of the suggested times, as a timestamp matching [System.currentTimeMillis].
   */
  data class PresetTime(val timeMs: Long) : ScheduleSendOption

  /**
   * The user wants to choose a date and time themselves.
   */
  data object PickTime : ScheduleSendOption

  companion object {
    private val PRESET_HOURS = intArrayOf(8, 12, 18, 21)

    /**
     * The options to offer at [currentTimeMs], in the order they are rendered top-to-bottom: the soonest suggestion
     * first, then the rest in chronological order, with [PickTime] last.
     *
     * Suggestions are the next three [PRESET_HOURS] on the clock, plus Monday morning when the week is already over.
     */
    fun forCurrentTime(currentTimeMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): List<ScheduleSendOption> {
      var dateTime: LocalDateTime = currentTimeMs.toLocalDateTime(zoneId)

      var presetIndex = PRESET_HOURS.indexOfFirst { it > dateTime.hour }
      if (presetIndex == -1) {
        dateTime = dateTime.plusDays(1)
        presetIndex = 0
      }

      dateTime = dateTime.withMinute(0).withSecond(0)

      val times = ArrayList<Long>(5)
      while (times.size < 3) {
        dateTime = dateTime.withHour(PRESET_HOURS[presetIndex])
        times += dateTime.toEpochMillis(zoneId)
        presetIndex++

        if (presetIndex >= PRESET_HOURS.size) {
          presetIndex = 0
          dateTime = dateTime.plusDays(1)
        }
      }

      val now = currentTimeMs.toLocalDateTime(zoneId)
      if (now.dayOfWeek == DayOfWeek.FRIDAY || now.dayOfWeek == DayOfWeek.SATURDAY) {
        times += now
          .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
          .withHour(8)
          .withMinute(0)
          .withSecond(0)
          .toEpochMillis(zoneId)
      }

      return times.map { PresetTime(it) } + PickTime
    }

    private fun Long.toLocalDateTime(zoneId: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zoneId)

    private fun LocalDateTime.toEpochMillis(zoneId: ZoneId): Long = TimeUnit.SECONDS.toMillis(atZone(zoneId).toEpochSecond())
  }
}

/**
 * Menu of times the user can schedule their send for. Options are computed each time the menu is shown, so that a menu
 * reopened after a preset hour has passed does not offer a time in the past.
 *
 * Tellomi（tellomi/tellomi#1263，ADR-0068 §4.7 P3）：[captionAboveMedia] 不为 null 时，最上面先是「说明文字上移 / 下移」，
 * 再一条分隔线（照 Telegram 两端长按发送菜单）；点了切换、菜单不收，字跟着变。
 */
@Composable
fun ScheduleSendMenu(
  controller: DropdownMenus.MenuController,
  onOptionClick: (ScheduleSendOption) -> Unit,
  modifier: Modifier = Modifier,
  showScheduleOptions: Boolean = true,
  captionAboveMedia: Boolean? = null,
  onToggleCaptionPosition: () -> Unit = {}
) {
  DropdownMenus.Menu(
    controller = controller,
    offsetX = 0.dp,
    modifier = modifier
  ) {
    if (captionAboveMedia != null) {
      CaptionPositionMenuItem(captionAboveMedia = captionAboveMedia, onClick = onToggleCaptionPosition)
      if (showScheduleOptions) {
        HorizontalDivider()
      }
    }
    if (showScheduleOptions) {
      ScheduleSendOptions(
        options = TellomiScheduleSendOptions,
        onOptionClick = {
          controller.hide()
          onOptionClick(it)
        }
      )
    }
  }
}

@Composable
internal fun CaptionPositionMenuItem(captionAboveMedia: Boolean, onClick: () -> Unit) {
  DropdownMenus.Item(
    modifier = Modifier.testTag(TestTags.SEND_MENU_CAPTION_POSITION),
    text = {
      Row(
        horizontalArrangement = spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = ImageVector.vectorResource(if (captionAboveMedia) R.drawable.tellomi_symbol_arrow_down_24 else R.drawable.tellomi_symbol_arrow_up_24),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface
        )

        Text(text = stringResource(if (captionAboveMedia) R.string.MediaSelectScreen__move_caption_down else R.string.MediaSelectScreen__move_caption_up))
      }
    },
    onClick = onClick
  )
}

@Composable
internal fun ScheduleSendOptions(
  options: List<ScheduleSendOption>,
  onOptionClick: (ScheduleSendOption) -> Unit
) {
  options.forEach { option ->
    ScheduleSendMenuItem(
      option = option,
      onClick = { onOptionClick(option) }
    )
  }
}

@Composable
private fun ScheduleSendMenuItem(
  option: ScheduleSendOption,
  onClick: () -> Unit
) {
  val label = when (option) {
    is ScheduleSendOption.PresetTime -> LocalScheduledSendTimeFormatter.current(option.timeMs)
    // Tellomi（#1601）：只剩这一项，照 TG 写「定时发送」（收藏里「设置提醒」）。
    ScheduleSendOption.PickTime -> stringResource(if (LocalTellomiScheduleIsReminder.current) R.string.ScheduleSendMenu__tellomi_set_reminder else R.string.ScheduleSendMenu__tellomi_schedule_message)
  }

  val testTag = when (option) {
    is ScheduleSendOption.PresetTime -> TestTags.scheduleSendPresetOption(option.timeMs)
    ScheduleSendOption.PickTime -> TestTags.SCHEDULE_SEND_PICK_TIME_OPTION
  }

  DropdownMenus.Item(
    modifier = Modifier.testTag(testTag),
    text = {
      Row(
        horizontalArrangement = spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          painter = option.icon.painter,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface
        )

        Text(text = label)
      }
    },
    onClick = onClick
  )
}

private val ScheduleSendOption.icon: SignalIcon
  get() = when (this) {
    is ScheduleSendOption.PresetTime -> if (LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault()).hour >= 18) SignalIcons.Nighttime else SignalIcons.Daytime
    ScheduleSendOption.PickTime -> SignalIcons.Calendar
  }

@DayNightPreviews
@Composable
private fun ScheduleSendMenuPreview() {
  Previews.Preview {
    ScheduleSendMenu(
      controller = remember { DropdownMenus.MenuController().apply { show() } },
      onOptionClick = {}
    )
  }
}
