/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.chats

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Tellomi (interaction audit A-26): opening a chat waits for its first frame at most 200 ms (it used to be 5 s), like Telegram Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class TellomiFirstFrameWaitTest {

  @Test
  fun `a chat that renders quickly starts the transition as soon as it is ready`() = runTest {
    val ready = MutableStateFlow(false)
    val result = async { TellomiFirstFrameWait.await(ready) }

    advanceTimeBy(80)
    ready.value = true
    assertThat(result.await()).isTrue()
    assertThat(currentTime).isEqualTo(80L)
  }

  @Test
  fun `a slow chat does not hold the list frozen for more than 200 ms`() = runTest {
    val result = TellomiFirstFrameWait.await(MutableStateFlow(false))

    assertThat(result).isFalse()
    assertThat(currentTime).isEqualTo(200L)
    assertThat(TellomiFirstFrameWait.MAX_WAIT.inWholeMilliseconds).isEqualTo(200L)
  }

  @Test
  fun `an already rendered chat does not wait at all`() = runTest {
    assertThat(TellomiFirstFrameWait.await(MutableStateFlow(true))).isTrue()
    assertThat(currentTime).isEqualTo(0L)
  }
}
