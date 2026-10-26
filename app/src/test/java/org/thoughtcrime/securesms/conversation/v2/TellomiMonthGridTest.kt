/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历排格。对齐 iOS `TellomiMonthGridTests`。
 */
class TellomiMonthGridTest {

  @Test
  fun leadingBlanksFollowFirstDayOfWeek() {
    // 2026-10-01 is Thursday.
    val sunday = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 18), DayOfWeek.SUNDAY)
    val monday = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 18), DayOfWeek.MONDAY)

    assertThat(sunday.leadingBlanks).isEqualTo(4)
    assertThat(monday.leadingBlanks).isEqualTo(3)
    assertThat(sunday.dayCount).isEqualTo(31)
    assertThat(sunday.itemCount).isEqualTo(35)
  }

  @Test
  fun dayAndItemRoundTrip() {
    val grid = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 18), DayOfWeek.MONDAY)

    assertThat(grid.dayAt(0)).isNull()
    assertThat(grid.dayAt(3)).isEqualTo(LocalDate.of(2026, 10, 1))
    assertThat(grid.itemOf(LocalDate.of(2026, 10, 18))).isEqualTo(20)
    assertThat(grid.dayAt(20)).isEqualTo(LocalDate.of(2026, 10, 18))
    assertThat(grid.dayAt(grid.itemCount)).isNull()
    assertThat(grid.itemOf(LocalDate.of(2026, 11, 1))).isNull()
  }

  @Test
  fun monthsSpanEarliestToLatest() {
    val months = TellomiMonthGrid.months(
      earliest = LocalDate.of(2025, 11, 30),
      latest = LocalDate.of(2026, 2, 1),
      firstDayOfWeek = DayOfWeek.SUNDAY
    )

    assertThat(months.map { it.monthStart }).containsExactly(
      LocalDate.of(2025, 11, 1),
      LocalDate.of(2025, 12, 1),
      LocalDate.of(2026, 1, 1),
      LocalDate.of(2026, 2, 1)
    )
    assertThat(months[3].dayCount).isEqualTo(28)
  }

  @Test
  fun weekdaySymbolsStartAtFirstDayOfWeek() {
    assertThat(
      TellomiMonthGrid.weekdaySymbols(Locale.US, DayOfWeek.MONDAY)
    ).containsExactly("M", "T", "W", "T", "F", "S", "S")
    assertThat(
      TellomiMonthGrid.weekdaySymbols(Locale.US, DayOfWeek.SUNDAY).first()
    ).isEqualTo("S")
  }
}
