/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.profiles

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.recipients.RecipientId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Tellomi (tellomi/tellomi#1366): how often an incoming 1:1 message refetches the sender's profile. */
class TellomiProfileRefreshTest {

  private val alice = RecipientId.from(1)
  private val bob = RecipientId.from(2)
  private val start = 1_800_000_000_000L

  @Before
  fun setUp() {
    TellomiProfileRefresh.clearForTesting()
  }

  @After
  fun tearDown() {
    TellomiProfileRefresh.clearForTesting()
  }

  @Test
  fun `first message from someone refetches, then not again within the hour`() {
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start))
    assertFalse(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 1.minutes.inWholeMilliseconds))
    assertFalse(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 59.minutes.inWholeMilliseconds))
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 1.hours.inWholeMilliseconds))
  }

  @Test
  fun `the hour counts from the last refetch, not from the first message`() {
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start))
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 61.minutes.inWholeMilliseconds))
    assertFalse(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 100.minutes.inWholeMilliseconds))
  }

  @Test
  fun `each sender has their own interval`() {
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start))
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(bob, start + 1.minutes.inWholeMilliseconds))
    assertFalse(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start + 2.minutes.inWholeMilliseconds))
  }

  @Test
  fun `a clock that went back counts as due`() {
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start))
    assertTrue(TellomiProfileRefresh.shouldRefreshOnMessage(alice, start - 1.days.inWholeMilliseconds))
  }

  @Test
  fun `routine refresh is more often than upstream`() {
    assertTrue(TellomiProfileRefresh.ROUTINE_INTERVAL < 12.hours)
    assertTrue(TellomiProfileRefresh.ROUTINE_STALE_AFTER < 1.days)
    assertTrue(TellomiProfileRefresh.ROUTINE_INTERVAL <= TellomiProfileRefresh.ROUTINE_STALE_AFTER)
  }
}
