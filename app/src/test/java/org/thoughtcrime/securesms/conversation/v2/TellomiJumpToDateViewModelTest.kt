/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.net.Uri
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tellomi（审计 7.2 / F03b）：月历 ViewModel 按月加载可选日与缩略图。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiJumpToDateViewModelTest {

  private val zone = ZoneId.of("UTC")
  private val dispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun onMonthVisibleLoadsSelectableDaysAndThumbs() = runTest(dispatcher) {
    val dayWithMsg = LocalDate.of(2026, 10, 18)
    val dayWithThumb = dayWithMsg
    val uri = mockk<Uri>()

    val vm = TellomiJumpToDateViewModel(
      threadId = 1L,
      initialDay = dayWithMsg,
      earliestDay = dayWithMsg,
      zoneId = zone,
      ioDispatcher = dispatcher,
      daysLookup = { starts ->
        starts.associateWith { it == dayWithMsg.atStartOfDay(zone).toInstant().toEpochMilli() }
      },
      thumbLookup = { days ->
        if (dayWithThumb in days) mapOf(dayWithThumb to uri) else emptyMap()
      }
    )

    vm.onMonthVisible(dayWithMsg.withDayOfMonth(1))
    // Unconfined + same dispatcher: work runs inline
    assertThat(vm.state.value.selectableDays).contains(dayWithMsg)
    assertThat(vm.state.value.thumbnails[dayWithThumb]).isEqualTo(uri)
    assertThat(vm.canSelect(dayWithMsg)).isTrue()
  }

  @Test
  fun onMonthVisibleIsIdempotent() = runTest(dispatcher) {
    var lookups = 0
    val day = LocalDate.of(2026, 6, 15)
    val vm = TellomiJumpToDateViewModel(
      threadId = 1L,
      initialDay = day,
      earliestDay = day,
      zoneId = zone,
      ioDispatcher = dispatcher,
      daysLookup = {
        lookups++
        emptyMap()
      },
      thumbLookup = { emptyMap() }
    )

    val month = day.withDayOfMonth(1)
    vm.onMonthVisible(month)
    vm.onMonthVisible(month)
    assertThat(lookups).isEqualTo(1)
    assertThat(vm.state.value.selectableDays).isEmpty()
  }
}
