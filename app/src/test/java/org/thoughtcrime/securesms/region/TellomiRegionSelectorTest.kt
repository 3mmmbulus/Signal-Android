/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.region.TellomiRegionSelector.ProbeResult
import org.thoughtcrime.securesms.region.TellomiRegionSelector.Reason
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext

/**
 * tellomi/tellomi#1055 第四刀：选路器（RegionProfile 契约第六节）。用例照 Desktop 的 `RegionSelector` 搬，和 iOS 同一组。
 */
class TellomiRegionSelectorTest {

  private val global = TellomiRegions.GLOBAL
  private val bothEnabled = listOf(global, TellomiRegions.CN.copy(enabled = true))
  private val start = 1_790_000_000_000L
  private val defaults = TellomiRegionSelector.Thresholds.DEFAULT

  /** 探测替身：按区给定结果，记下被探了哪些区、哪些 chat 主机。 */
  private class ProbeLog {
    val ids = mutableListOf<TellomiRegionId>()
    val hosts = mutableListOf<String>()
  }

  private fun selector(
    profiles: List<TellomiRegionProfile>,
    results: Map<TellomiRegionId, ProbeResult>,
    current: () -> TellomiRegionId = { TellomiRegionId.GLOBAL },
    lastSwitchAt: () -> Long? = { null },
    now: () -> Long = { start }
  ): Pair<TellomiRegionSelector, ProbeLog> {
    val log = ProbeLog()
    val selector = TellomiRegionSelector(
      profiles = { profiles },
      currentRegion = current,
      lastSwitchAt = lastSwitchAt,
      probeRegion = { region, _ ->
        synchronized(log) {
          log.ids += region.id
          log.hosts += TellomiRegions.hostOf(region.chat)
        }
        results[region.id] ?: ProbeResult.Failed("unexpected probe of ${region.id.id}")
      },
      now = now,
      executor = Executor { it.run() }
    )
    return selector to log
  }

  @Test
  fun `probes only enabled regions`() {
    val cnOff = listOf(global, TellomiRegions.CN.copy(enabled = false))
    assertThat(cnOff.filter { it.enabled }.map { it.id }).isEqualTo(listOf(TellomiRegionId.GLOBAL))
    val (selector, log) = selector(cnOff, mapOf(TellomiRegionId.GLOBAL to ProbeResult.Ok(40)))

    val decision = selector.probe()

    assertThat(log.ids).isEqualTo(listOf(TellomiRegionId.GLOBAL))
    assertThat(decision.results.keys).isEqualTo(setOf(TellomiRegionId.GLOBAL))
    assertThat(decision.recommended).isEqualTo(TellomiRegionId.GLOBAL)
  }

  @Test
  fun `no tellomi cn host is probed while cn is disabled`() {
    // #1055 判据 4 在选路器这一层：CN 关着时，探测连 tellomi.cn 的主机名都碰不到
    val cnOff = listOf(global, TellomiRegions.CN.copy(enabled = false))
    val (selector, log) = selector(cnOff, mapOf(TellomiRegionId.GLOBAL to ProbeResult.Failed("offline in tests")))

    val decision = selector.probe()

    assertThat(log.hosts).isEqualTo(listOf("chat.tellomi.app"))
    assertThat(decision.reason).isEqualTo(Reason.NO_ALTERNATIVE)
  }

  @Test
  fun `stays unless the other region is faster by the threshold`() {
    val (selector, _) = selector(
      bothEnabled,
      mapOf(TellomiRegionId.GLOBAL to ProbeResult.Ok(100), TellomiRegionId.CN to ProbeResult.Ok(100 - defaults.latencyAdvantageMs)),
      now = { start + 60 * 60_000 }
    )

    assertThat(selector.probe().reason).isEqualTo(Reason.STAY)
  }

  @Test
  fun `the faster region wins at once when no switch is on record`() {
    val (selector, _) = selector(bothEnabled, mapOf(TellomiRegionId.GLOBAL to ProbeResult.Ok(200), TellomiRegionId.CN to ProbeResult.Ok(20)))

    val cold = selector.probe()

    assertThat(cold.reason).isEqualTo(Reason.FASTER)
    assertThat(cold.recommended).isEqualTo(TellomiRegionId.CN)
  }

  @Test
  fun `a faster region is held back for the minimum dwell`() {
    // 驻留从记下的上一次切区算起，不从进程启动算起
    var current = TellomiRegionId.GLOBAL
    var lastSwitchAt: Long? = start
    var now = start
    val (selector, _) = selector(
      bothEnabled,
      mapOf(TellomiRegionId.GLOBAL to ProbeResult.Ok(200), TellomiRegionId.CN to ProbeResult.Ok(20)),
      current = { current },
      lastSwitchAt = { lastSwitchAt },
      now = { now }
    )

    now = start + defaults.minDwellMs - 1
    val early = selector.probe()
    assertThat(early.reason).isEqualTo(Reason.DWELL)
    assertThat(early.recommended).isEqualTo(TellomiRegionId.GLOBAL)

    now = start + defaults.minDwellMs
    val later = selector.probe()
    assertThat(later.reason).isEqualTo(Reason.FASTER)
    assertThat(later.recommended).isEqualTo(TellomiRegionId.CN)

    // 调用方照建议切了区：生效区换了、切区时间记下了
    current = TellomiRegionId.CN
    lastSwitchAt = now
    val again = selector.probe()
    assertThat(again.current).isEqualTo(TellomiRegionId.CN)
    assertThat(again.reason).isEqualTo(Reason.STAY)
  }

  @Test
  fun `fails over only after consecutive failures`() {
    val (selector, _) = selector(bothEnabled, mapOf(TellomiRegionId.GLOBAL to ProbeResult.Failed("timeout"), TellomiRegionId.CN to ProbeResult.Ok(30)))

    assertThat(selector.probe().reason).isEqualTo(Reason.CURRENT_FAILING)
    assertThat(selector.probe().reason).isEqualTo(Reason.CURRENT_FAILING)
    val third = selector.probe()
    assertThat(third.reason).isEqualTo(Reason.FAILOVER)
    assertThat(third.recommended).isEqualTo(TellomiRegionId.CN)
  }

  @Test
  fun `reported connection failures count toward the failover`() {
    val (selector, _) = selector(bothEnabled, mapOf(TellomiRegionId.GLOBAL to ProbeResult.Failed("timeout"), TellomiRegionId.CN to ProbeResult.Ok(30)))
    assertThat(selector.reportConnectionFailure()).isFalse()
    assertThat(selector.reportConnectionFailure()).isFalse()
    // 探测时当前区自己的失败是第三次
    assertThat(selector.probe().reason).isEqualTo(Reason.FAILOVER)

    selector.reportConnectionSuccess()
    assertThat(selector.reportConnectionFailure()).isFalse()
    assertThat(selector.reportConnectionFailure()).isFalse()
    assertThat(selector.reportConnectionFailure()).isTrue()
  }

  @Test
  fun `a disabled region is never recommended`() {
    // 关着的 CN：就算它会探得很快（假如探了的话），也不探、不推荐；当前区失败也只能 no-alternative
    val (selector, log) = selector(listOf(global, TellomiRegions.CN.copy(enabled = false)), mapOf(TellomiRegionId.GLOBAL to ProbeResult.Failed("timeout"), TellomiRegionId.CN to ProbeResult.Ok(1)))

    repeat(3) {
      val decision = selector.probe()
      assertThat(decision.recommended).isEqualTo(TellomiRegionId.GLOBAL)
      assertThat(decision.reason).isEqualTo(Reason.NO_ALTERNATIVE)
    }
    assertThat(TellomiRegionId.CN in log.ids).isFalse()
  }

  @Test
  fun `the failure count starts over after the region changes`() {
    var current = TellomiRegionId.GLOBAL
    val (selector, _) = selector(
      bothEnabled,
      mapOf(TellomiRegionId.GLOBAL to ProbeResult.Ok(30), TellomiRegionId.CN to ProbeResult.Failed("timeout")),
      current = { current }
    )
    // global 上攒了两次失败
    assertThat(selector.reportConnectionFailure()).isFalse()
    assertThat(selector.reportConnectionFailure()).isFalse()

    // 切到了 CN（生效区变了），CN 失败一次：从 1 数起，不 failover
    current = TellomiRegionId.CN
    val decision = selector.probe()
    assertThat(decision.reason).isEqualTo(Reason.CURRENT_FAILING)
    assertThat(decision.recommended).isEqualTo(TellomiRegionId.CN)
  }

  @Test
  fun `the chat probe fails fast on an unresolvable host`() {
    // 真的探测器：.invalid 永远解析不到（就算被劫持到别处，证书也对不上），不能被当成健康；而且不等超时
    val unresolvable = TellomiRegions.testRegionProfiles("tellomi.invalid")[1]
    assertThat(TellomiRegions.hostOf(unresolvable.chat)).isEqualTo("chat.tellomi.invalid")

    val startedAt = System.currentTimeMillis()
    // 纯 JVM 单测里 SignalExecutors 的线程会因为 android.os.Process 是桩而起不来，所以给一个普通线程
    val result = TellomiChatProbe.probe(unresolvable, 5_000, SSLContext.getDefault().socketFactory) { Thread(it).start() }
    val elapsed = System.currentTimeMillis() - startedAt

    assertThat(result).isInstanceOf<ProbeResult.Failed>()
    assertThat(elapsed).isLessThan(5_000L)
  }
}
