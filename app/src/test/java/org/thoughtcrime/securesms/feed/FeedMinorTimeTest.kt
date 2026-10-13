/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.ui.CoreUiDependenciesRule
import org.signal.core.ui.compose.theme.SignalTheme
import java.time.LocalDate
import java.time.LocalDateTime

/** 未成年人模式的时间限制（第三节第 31 条）：每天 40 分钟、22:00–6:00 不能用。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedMinorTimeTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val coreUiDependenciesRule = CoreUiDependenciesRule(ApplicationProvider.getApplicationContext())

  private val day = LocalDate.of(2026, 10, 13)

  @Test
  fun `night hours lock, daytime does not until forty minutes are used`() {
    assertThat(FeedMinorTime.lock(day.atTime(21, 59), 0)).isNull()
    assertThat(FeedMinorTime.lock(day.atTime(22, 0), 0)).isEqualTo(FeedMinorTime.Lock.NIGHT)
    assertThat(FeedMinorTime.lock(day.atTime(5, 59), 0)).isEqualTo(FeedMinorTime.Lock.NIGHT)
    assertThat(FeedMinorTime.lock(day.atTime(6, 0), 0)).isNull()
    assertThat(FeedMinorTime.lock(day.atTime(12, 0), 40 * 60_000L - 1)).isNull()
    assertThat(FeedMinorTime.lock(day.atTime(12, 0), 40 * 60_000L)).isEqualTo(FeedMinorTime.Lock.DAILY)
  }

  @Test
  fun `usage adds up within a day and starts over the next`() {
    val usage = InMemoryFeedMinorUsage()
    usage.add(day, 10_000)
    usage.add(day, 5_000)
    assertThat(usage.usedMillis(day)).isEqualTo(15_000L)
    assertThat(usage.usedMillis(day.plusDays(1))).isEqualTo(0L)
    usage.add(day.plusDays(1), 1_000)
    assertThat(usage.usedMillis(day.plusDays(1))).isEqualTo(1_000L)
    assertThat(usage.usedMillis(day)).isEqualTo(0L)
  }

  @Test
  fun `the feed shows the lock page once the day's time is used`() {
    val used = InMemoryFeedMinorUsage().apply { add(day, FeedMinorTime.DAILY_LIMIT_MILLIS) }
    composeTestRule.setContent {
      SignalTheme {
        val lock = rememberFeedMinorLock(minorMode = true, usage = { used }, clock = { LocalDateTime.of(day, java.time.LocalTime.NOON) })
        if (lock != null) FeedMinorLockScreen(lock)
      }
    }
    composeTestRule.onNodeWithTag(FeedMinorTime.TEST_TAG_LOCK).assertExists()
    composeTestRule.onNodeWithText("You've used today's Feed time").assertExists()
  }

  @Test
  fun `not minor mode never locks`() {
    composeTestRule.setContent {
      SignalTheme {
        val lock = rememberFeedMinorLock(minorMode = false, clock = { LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT) })
        if (lock != null) FeedMinorLockScreen(lock)
      }
    }
    composeTestRule.onNodeWithTag(FeedMinorTime.TEST_TAG_LOCK).assertDoesNotExist()
  }
}
