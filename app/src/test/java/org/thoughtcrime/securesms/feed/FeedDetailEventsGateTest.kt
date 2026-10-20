/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class FeedDetailEventsGateTest {

  @Test
  fun `reports only when the internal switch is on and the engine has events`() {
    assertThat(feedDetailEventsEnabled(internalSwitch = true, entries = FeedEntries.ALL)).isTrue()
  }

  @Test
  fun `internal switch off never reports`() {
    assertThat(feedDetailEventsEnabled(internalSwitch = false, entries = FeedEntries.ALL)).isFalse()
  }

  @Test
  fun `engine without events never reports even with the switch on`() {
    assertThat(feedDetailEventsEnabled(internalSwitch = true, entries = FeedEntries.ALL.copy(events = false))).isFalse()
    assertThat(feedDetailEventsEnabled(internalSwitch = true, entries = FeedEntries.NONE)).isFalse()
  }
}
