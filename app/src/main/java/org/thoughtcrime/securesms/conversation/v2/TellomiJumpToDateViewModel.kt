/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.database.SignalDatabase
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历的状态——有消息的日子、每天的媒体缩略图 URI。
 * 按月第一次露出来时才查（对齐 iOS `requestThumbnailsIfNeeded`）。
 */
class TellomiJumpToDateViewModel(
  private val threadId: Long,
  private val initialDay: LocalDate,
  private val earliestDay: LocalDate,
  private val zoneId: ZoneId = ZoneId.systemDefault(),
  private val locale: Locale = Locale.getDefault(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val daysLookup: (Collection<Long>) -> Map<Long, Boolean> = { starts ->
    SignalDatabase.messages.messageExistsOnDays(threadId, starts)
  },
  private val thumbLookup: (Collection<LocalDate>) -> Map<LocalDate, Uri> = { days ->
    TellomiConversationDayThumbnails.firstVisualMediaUris(threadId, days, zoneId)
  }
) : ViewModel() {

  data class State(
    val months: List<TellomiMonthGrid> = emptyList(),
    val weekdaySymbols: List<String> = emptyList(),
    val selectableDays: Set<LocalDate> = emptySet(),
    val thumbnails: Map<LocalDate, Uri> = emptyMap(),
    val initialDay: LocalDate,
    val today: LocalDate
  )

  private val firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
  private val requestedMonths = mutableSetOf<LocalDate>()
  private val today = LocalDate.now(zoneId)

  private val _state = MutableStateFlow(
    State(
      months = TellomiMonthGrid.months(
        earliest = minOf(earliestDay, initialDay, today),
        latest = maxOf(earliestDay, initialDay, today),
        firstDayOfWeek = firstDayOfWeek
      ),
      weekdaySymbols = TellomiMonthGrid.weekdaySymbols(locale, firstDayOfWeek),
      initialDay = initialDay,
      today = today
    )
  )
  val state: StateFlow<State> = _state

  fun onMonthVisible(monthStart: LocalDate) {
    if (!requestedMonths.add(monthStart)) return
    val grid = _state.value.months.firstOrNull { it.monthStart == monthStart } ?: return
    val daysInMonth = (0 until grid.itemCount).mapNotNull { grid.dayAt(it) }
    if (daysInMonth.isEmpty()) return

    viewModelScope.launch {
      val dayStarts = daysInMonth.map { it.atStartOfDay(zoneId).toInstant().toEpochMilli() }
      val existence = withContext(ioDispatcher) { daysLookup(dayStarts) }
      val selectable = daysInMonth.filter { day ->
        val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        existence[start] == true
      }.toSet()

      val thumbs = if (selectable.isEmpty()) {
        emptyMap()
      } else {
        withContext(ioDispatcher) { thumbLookup(selectable) }
      }

      _state.update { current ->
        current.copy(
          selectableDays = current.selectableDays + selectable,
          thumbnails = current.thumbnails + thumbs
        )
      }
    }
  }

  /** 这一天能不能选：会话里那天有消息。 */
  fun canSelect(day: LocalDate): Boolean = day in _state.value.selectableDays

  class Factory(
    private val threadId: Long,
    private val initialDayMs: Long,
    private val earliestDayMs: Long,
    private val zoneId: ZoneId = ZoneId.systemDefault()
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      val initial = Instant.ofEpochMilli(initialDayMs).atZone(zoneId).toLocalDate()
      val earliest = if (earliestDayMs > 0) {
        Instant.ofEpochMilli(earliestDayMs).atZone(zoneId).toLocalDate()
      } else {
        initial
      }
      return TellomiJumpToDateViewModel(
        threadId = threadId,
        initialDay = initial,
        earliestDay = earliest,
        zoneId = zoneId
      ) as T
    }
  }
}
