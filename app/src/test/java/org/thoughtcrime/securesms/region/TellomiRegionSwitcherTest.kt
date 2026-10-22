/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import org.junit.Test

/**
 * tellomi/tellomi#1055 第三刀：切区（RegionProfile 契约第六节 `switchTo`）。
 */
class TellomiRegionSwitcherTest {

  private val global = TellomiRegions.GLOBAL
  private val cnOn = TellomiRegions.CN.copy(enabled = true)

  /** 记下切换器对外做的每一件事，按先后顺序。 */
  private class Recorder(var storedId: String? = null) {
    val events = mutableListOf<String>()
  }

  private fun switcher(profiles: List<TellomiRegionProfile>, recorder: Recorder, now: Long = 1_790_000_000_000L): TellomiRegionSwitcher {
    return TellomiRegionSwitcher(
      profiles = { profiles },
      storedId = { recorder.storedId },
      record = { id, at ->
        recorder.storedId = id.id
        recorder.events += "record ${id.id} at $at"
      },
      resetNetwork = { recorder.events += "reset network" },
      now = { now }
    )
  }

  @Test
  fun `unknown and disabled regions are refused before touching the network`() {
    // 关着的 CN：直接报错
    val packaged = Recorder()
    assertFailure { switcher(listOf(global, TellomiRegions.CN.copy(enabled = false)), packaged).switchTo(TellomiRegionId.CN) }
      .isInstanceOf<TellomiRegionSwitcher.SwitchException>()
      .prop(TellomiRegionSwitcher.SwitchException::reason).isEqualTo(TellomiRegionSwitcher.Reason.DISABLED)

    // 表里没有的区
    val onlyGlobal = Recorder()
    assertFailure { switcher(listOf(global), onlyGlobal).switchTo(TellomiRegionId.CN) }
      .isInstanceOf<TellomiRegionSwitcher.SwitchException>()
      .prop(TellomiRegionSwitcher.SwitchException::reason).isEqualTo(TellomiRegionSwitcher.Reason.UNKNOWN)

    // 两种都在碰网络之前就停了：没记区、没重建（#1055 判据 4 在切换器这一层）
    assertThat(packaged.events).isEmpty()
    assertThat(onlyGlobal.events).isEmpty()
    assertThat(packaged.storedId == null && onlyGlobal.storedId == null).isTrue()
  }

  @Test
  fun `switching to the current region does nothing`() {
    // 没有记录 = global
    val fresh = Recorder()
    assertThat(switcher(listOf(global, cnOn), fresh).switchTo(TellomiRegionId.GLOBAL)).isFalse()

    val onCn = Recorder(storedId = "cn")
    assertThat(switcher(listOf(global, cnOn), onCn).switchTo(TellomiRegionId.CN)).isFalse()

    assertThat(fresh.events).isEmpty()
    assertThat(onCn.events).isEmpty()
  }

  @Test
  fun `switching records the region and the time and then resets the network once`() {
    val recorder = Recorder()
    val switcher = switcher(listOf(global, cnOn), recorder, now = 1_790_000_000_123L)

    assertThat(switcher.switchTo(TellomiRegionId.CN)).isTrue()

    // 先记再重建：重建时各处读 TellomiRegions.current()，要读到新区
    assertThat(recorder.events).isEqualTo(listOf("record cn at 1790000000123", "reset network"))
    assertThat(recorder.storedId).isEqualTo("cn")

    // 已经在 CN：再切 CN 不动，切回 global 再重建一次
    assertThat(switcher.switchTo(TellomiRegionId.CN)).isFalse()
    assertThat(switcher.switchTo(TellomiRegionId.GLOBAL)).isTrue()
    assertThat(recorder.events).isEqualTo(
      listOf("record cn at 1790000000123", "reset network", "record global at 1790000000123", "reset network")
    )
  }

  @Test
  fun `a stored region that is no longer usable counts as global`() {
    // 记住的是 cn，但这个进程的表里 cn 关着（例如测试区只在别的进程开着）：当前区按规则回落 global，切 global 什么都不做
    val recorder = Recorder(storedId = "cn")
    assertThat(switcher(listOf(TellomiRegions.GLOBAL, TellomiRegions.CN.copy(enabled = false)), recorder).switchTo(TellomiRegionId.GLOBAL)).isFalse()
    assertThat(recorder.events).isEmpty()
  }
}
