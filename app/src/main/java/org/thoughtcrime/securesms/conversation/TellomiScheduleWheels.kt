/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi#1601，M-4，O8）：「定时发送」选时间的规则，照 Telegram 安卓 `AlertsCreator.createScheduleDatePickerDialog`
 * （只读，代码是自己写的）：日 / 时 / 分三个滚轮；最早 = 现在 + 60 秒（按分钟往上取整），最晚 = 一年（365 天）后，按分钟选；
 * 打开时默认最早那一刻（TG 安卓：今天、分钟 0，再推到最早）；选到早于最早的就推上去，点按钮时再推一次、不提示（`checkScheduleDate`）。
 */
object TellomiScheduleWheels {

  const val MAX_DAYS = 365

  private val MIN_LEAD_MS = TimeUnit.SECONDS.toMillis(60)

  /** 滚轮上选中的：第几天（0 = 今天）、几点、几分。 */
  data class Selection(val dayOffset: Int, val hour: Int, val minute: Int)

  /** 按钮怎么写：今天 / 今年某天 / 别的年份某天（TG 安卓 `SendTodayAt` / `SendDayAt` / `SendDayYearAt`，没有「明天」）。 */
  enum class Day { TODAY, THIS_YEAR, OTHER_YEAR }

  fun minimumTime(nowMs: Long): Long {
    val lead = nowMs + MIN_LEAD_MS
    val minute = TimeUnit.MINUTES.toMillis(1)
    return if (lead % minute == 0L) lead else (lead / minute + 1) * minute
  }

  fun maximumTime(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).plusDays(MAX_DAYS.toLong()).toInstant().toEpochMilli()
  }

  fun defaultTime(nowMs: Long): Long = minimumTime(nowMs)

  fun clamp(timeMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    return timeMs.coerceIn(minimumTime(nowMs), maximumTime(nowMs, zone))
  }

  /**
   * 滚轮的「第几天」都从 [baseMs]（打开选时间的那一刻）那天算起，和天轮上的字一致；
   * 开着跨过了午夜也不会差一天。
   */
  fun selection(timeMs: Long, baseMs: Long, zone: ZoneId = ZoneId.systemDefault()): Selection {
    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeMs), zone)
    val baseDay = Instant.ofEpochMilli(baseMs).atZone(zone).toLocalDate()
    val dayOffset = ChronoUnit.DAYS.between(baseDay, time.toLocalDate()).toInt().coerceIn(0, MAX_DAYS)
    return Selection(dayOffset = dayOffset, hour = time.hour, minute = time.minute)
  }

  fun time(selection: Selection, baseMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val day = Instant.ofEpochMilli(baseMs).atZone(zone).toLocalDate().plusDays(selection.dayOffset.toLong())
    return day.atTime(selection.hour, selection.minute).atZone(zone).toInstant().toEpochMilli()
  }

  /** 滚轮转到的那一刻（天从 [baseMs] 算），推进 [nowMs] 时的最早 / 最晚之间后，滚轮该停在哪。 */
  fun settle(selection: Selection, baseMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Selection {
    return selection(clamp(time(selection, baseMs, zone), nowMs, zone), baseMs, zone)
  }

  fun day(timeMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Day {
    val time = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
      time == today -> Day.TODAY
      time.year == today.year -> Day.THIS_YEAR
      else -> Day.OTHER_YEAR
    }
  }
}
