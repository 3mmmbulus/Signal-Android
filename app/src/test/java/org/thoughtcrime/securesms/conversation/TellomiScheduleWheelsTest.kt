/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tellomi（tellomi/tellomi#1601，M-4，照 Telegram 安卓 `createScheduleDatePickerDialog`）：定时发送选时间的规则——
 * 最早现在 + 60 秒（按分钟往上取整）、最晚一年、默认最早那一刻、滚轮选到早于最早的推上去、按钮按今天 / 今年 / 别的年份写。
 */
class TellomiScheduleWheelsTest {

  private val zone = ZoneId.of("Asia/Shanghai")

  private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
    return LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
  }

  @Test
  fun `the earliest is a minute ahead, rounded up to a whole minute`() {
    assertEquals(ms(2026, 10, 14, 10, 4), TellomiScheduleWheels.minimumTime(ms(2026, 10, 14, 10, 2, 37)))
    assertEquals(ms(2026, 10, 14, 10, 3), TellomiScheduleWheels.minimumTime(ms(2026, 10, 14, 10, 2, 0)))
    assertEquals(TellomiScheduleWheels.minimumTime(ms(2026, 10, 14, 10, 2, 37)), TellomiScheduleWheels.defaultTime(ms(2026, 10, 14, 10, 2, 37)))
  }

  @Test
  fun `the latest is a year ahead`() {
    val now = ms(2026, 10, 14, 10, 2)
    assertEquals(ms(2027, 10, 14, 10, 2), TellomiScheduleWheels.maximumTime(now, zone))
    assertEquals(ms(2027, 10, 14, 10, 2), TellomiScheduleWheels.clamp(ms(2028, 1, 1, 0, 0), now, zone))
  }

  @Test
  fun `wheels map to a time and back`() {
    val now = ms(2026, 12, 31, 22, 0)
    val selection = TellomiScheduleWheels.Selection(dayOffset = 1, hour = 9, minute = 30)
    val time = TellomiScheduleWheels.time(selection, now, zone)
    assertEquals(ms(2027, 1, 1, 9, 30), time)
    assertEquals(selection, TellomiScheduleWheels.selection(time, now, zone))
  }

  @Test
  fun `turning the wheels to the past settles on the earliest`() {
    val now = ms(2026, 10, 14, 10, 2, 37)
    val earlier = TellomiScheduleWheels.Selection(dayOffset = 0, hour = 9, minute = 0)
    assertEquals(TellomiScheduleWheels.Selection(dayOffset = 0, hour = 10, minute = 4), TellomiScheduleWheels.settle(earlier, now, now, zone))
    val later = TellomiScheduleWheels.Selection(dayOffset = 2, hour = 8, minute = 15)
    assertEquals(later, TellomiScheduleWheels.settle(later, now, now, zone))
  }

  /** 开着跨过了午夜：天轮从打开那天算，选的「明天 00:01」还是那一刻，不会晚一天。 */
  @Test
  fun `days count from when the sheet opened, even past midnight`() {
    val opened = ms(2026, 10, 14, 23, 59, 10)
    val selection = TellomiScheduleWheels.selection(TellomiScheduleWheels.defaultTime(opened), opened, zone)
    assertEquals(TellomiScheduleWheels.Selection(dayOffset = 1, hour = 0, minute = 1), selection)

    val tappedAfterMidnight = ms(2026, 10, 15, 0, 0, 5)
    val chosen = TellomiScheduleWheels.clamp(TellomiScheduleWheels.time(selection, opened, zone), tappedAfterMidnight, zone)
    assertEquals(ms(2026, 10, 15, 0, 2), chosen)
    assertEquals(selection.copy(minute = 2), TellomiScheduleWheels.settle(selection, opened, tappedAfterMidnight, zone))
  }

  @Test
  fun `the button says today, a day this year, or a day in another year`() {
    val now = ms(2026, 10, 14, 10, 2)
    assertEquals(TellomiScheduleWheels.Day.TODAY, TellomiScheduleWheels.day(ms(2026, 10, 14, 23, 0), now, zone))
    assertEquals(TellomiScheduleWheels.Day.THIS_YEAR, TellomiScheduleWheels.day(ms(2026, 10, 15, 8, 0), now, zone))
    assertEquals(TellomiScheduleWheels.Day.OTHER_YEAR, TellomiScheduleWheels.day(ms(2027, 1, 3, 8, 0), now, zone))
  }
}
