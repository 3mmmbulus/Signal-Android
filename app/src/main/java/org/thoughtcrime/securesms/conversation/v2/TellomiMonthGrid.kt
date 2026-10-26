/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历里的一个月——1 号前空几格、有几天。
 * 一行 7 格，从当地 `firstDayOfWeek` 开始。对齐 iOS `TellomiMonthGrid`。
 */
data class TellomiMonthGrid(
  /** 这个月 1 号。 */
  val monthStart: LocalDate,
  /** 1 号前面空几格（0…6）。 */
  val leadingBlanks: Int,
  /** 这个月有几天。 */
  val dayCount: Int
) {
  val itemCount: Int get() = leadingBlanks + dayCount

  /**
   * 第 [item] 格是哪天；空格返回 null。
   */
  fun dayAt(item: Int): LocalDate? {
    val dayIndex = item - leadingBlanks
    if (dayIndex < 0 || dayIndex >= dayCount) return null
    return monthStart.plusDays(dayIndex.toLong())
  }

  /**
   * [day] 在这个月的第几格；不在这个月返回 null。
   */
  fun itemOf(day: LocalDate): Int? {
    if (YearMonth.from(day) != YearMonth.from(monthStart)) return null
    return leadingBlanks + day.dayOfMonth - 1
  }

  companion object {
    fun containing(date: LocalDate, firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek): TellomiMonthGrid {
      val monthStart = date.withDayOfMonth(1)
      val leading = ((monthStart.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
      return TellomiMonthGrid(
        monthStart = monthStart,
        leadingBlanks = leading,
        dayCount = monthStart.lengthOfMonth()
      )
    }

    /**
     * 从 [earliest] 所在月到 [latest] 所在月，按先后排。
     */
    fun months(
      earliest: LocalDate,
      latest: LocalDate,
      firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    ): List<TellomiMonthGrid> {
      val start = minOf(earliest, latest).withDayOfMonth(1)
      val end = maxOf(earliest, latest).withDayOfMonth(1)
      val result = mutableListOf<TellomiMonthGrid>()
      var cursor = start
      while (!cursor.isAfter(end)) {
        result += containing(cursor, firstDayOfWeek)
        cursor = cursor.plusMonths(1)
      }
      return result
    }

    /**
     * 表头一行星期缩写，从 [firstDayOfWeek] 开始。
     */
    fun weekdaySymbols(
      locale: Locale = Locale.getDefault(),
      firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    ): List<String> {
      return (0 until 7).map { offset ->
        firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale)
      }
    }
  }
}
