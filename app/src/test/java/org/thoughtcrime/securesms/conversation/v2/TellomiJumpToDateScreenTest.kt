/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * Tellomi（审计 7.2 / F03b）：跳日期月历界面——有消息的日子可点，没消息的灰掉不可点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp")
class TellomiJumpToDateScreenTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  @Test
  fun tappingSelectableDayPicksIt_greyDayDoesNot() {
    val month = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 18), DayOfWeek.MONDAY)
    val selectable = LocalDate.of(2026, 10, 18)
    val grey = LocalDate.of(2026, 10, 17)
    val picked = AtomicReference<LocalDate?>(null)
    val visibleMonths = mutableListOf<LocalDate>()

    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiJumpToDateScreen(
          state = TellomiJumpToDateViewModel.State(
            months = listOf(month),
            weekdaySymbols = TellomiMonthGrid.weekdaySymbols(firstDayOfWeek = DayOfWeek.MONDAY),
            selectableDays = setOf(selectable),
            thumbnails = emptyMap(),
            initialDay = selectable,
            today = LocalDate.of(2026, 10, 26)
          ),
          onMonthVisible = { visibleMonths += it },
          onPick = { picked.set(it) },
          onCancel = {}
        )
      }
    }

    composeTestRule.onNodeWithTag(TellomiJumpToDateTestTags.SCREEN).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiJumpToDateTestTags.day(grey)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TellomiJumpToDateTestTags.day(selectable)).performClick()
    assertThat(picked.get()).isEqualTo(selectable)

    picked.set(null)
    composeTestRule.onNodeWithTag(TellomiJumpToDateTestTags.day(grey)).performClick()
    assertThat(picked.get()).isEqualTo(null)

    assertThat(visibleMonths.distinct()).containsExactly(month.monthStart)
  }

  @Test
  fun cancelInvokesCallback() {
    val month = TellomiMonthGrid.containing(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
    var cancelled = false

    composeTestRule.setContent {
      SignalTheme(isDarkMode = false) {
        TellomiJumpToDateScreen(
          state = TellomiJumpToDateViewModel.State(
            months = listOf(month),
            weekdaySymbols = TellomiMonthGrid.weekdaySymbols(firstDayOfWeek = DayOfWeek.MONDAY),
            selectableDays = emptySet(),
            thumbnails = emptyMap(),
            initialDay = LocalDate.of(2026, 10, 1),
            today = LocalDate.of(2026, 10, 26)
          ),
          onMonthVisible = {},
          onPick = {},
          onCancel = { cancelled = true }
        )
      }
    }

    composeTestRule.onNodeWithTag(TellomiJumpToDateTestTags.CANCEL).performClick()
    assertThat(cancelled).isEqualTo(true)
  }
}
