/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.region.TellomiRegionAutoSwitch.Reason

/** REGION_PROFILE 第六节之二：规则与 Desktop `tellomiRegionTarget_test.node.ts` 逐条一致。 */
class TellomiRegionAutoSwitchTest {

  private val global = TellomiRegions.GLOBAL
  private val cn = TellomiRegions.CN

  private class World(var stored: String?, var target: String?, var failures: Int = 0, val up: MutableMap<TellomiRegionId, Boolean> = mutableMapOf()) {
    val probed = mutableListOf<TellomiRegionId>()
    val probedHosts = mutableListOf<String>()
    val switched = mutableListOf<TellomiRegionId>()
  }

  private fun auto(w: World, table: List<TellomiRegionProfile> = TellomiRegions.ALL) = TellomiRegionAutoSwitch(
    profiles = { table },
    storedId = { w.stored },
    targetValue = { w.target },
    loadFailures = { w.failures },
    storeFailures = { w.failures = it },
    probe = {
      w.probed += it.id
      w.probedHosts += TellomiRegions.hostOf(it.chat)
      w.up[it.id] ?: error("unexpected probe of ${it.id}")
    },
    switchTo = {
      w.switched += it
      w.stored = it.id
    }
  )

  @Test
  fun `parses the key - only global and cn are explicit`() {
    assertThat(TellomiRegionAutoSwitch.parseTarget(null)).isEqualTo(TellomiRegionAutoSwitch.Target(TellomiRegionId.GLOBAL, false))
    assertThat(TellomiRegionAutoSwitch.parseTarget("")).isEqualTo(TellomiRegionAutoSwitch.Target(TellomiRegionId.GLOBAL, false))
    assertThat(TellomiRegionAutoSwitch.parseTarget("CN")).isEqualTo(TellomiRegionAutoSwitch.Target(TellomiRegionId.GLOBAL, false))
    assertThat(TellomiRegionAutoSwitch.parseTarget("cn")).isEqualTo(TellomiRegionAutoSwitch.Target(TellomiRegionId.CN, true))
    assertThat(TellomiRegionAutoSwitch.parseTarget("global")).isEqualTo(TellomiRegionAutoSwitch.Target(TellomiRegionId.GLOBAL, true))
  }

  @Test
  fun `feature off - no key on global probes nothing and resolves no tellomi cn name`() {
    for (target in listOf(null, "", "nonsense")) {
      val w = World(stored = null, target = target)
      assertThat(auto(w).evaluate()).isEqualTo(Reason.OFF)
      assertThat(w.probed).isEmpty()
      assertThat(w.switched).isEmpty()
    }
    val explicitGlobal = World(stored = "global", target = "global", up = mutableMapOf(TellomiRegionId.GLOBAL to true))
    assertThat(auto(explicitGlobal).evaluate()).isEqualTo(Reason.STAY)
    assertThat(explicitGlobal.probedHosts.any { it.endsWith("tellomi.cn") }).isFalse()
  }

  @Test
  fun `moves to the target only after a successful probe`() {
    val down = World(stored = null, target = "cn", up = mutableMapOf(TellomiRegionId.CN to false))
    assertThat(auto(down).evaluate()).isEqualTo(Reason.TARGET_UNREACHABLE)
    assertThat(down.switched).isEmpty()
    assertThat(down.probed).isEqualTo(listOf(TellomiRegionId.CN))

    val upW = World(stored = null, target = "cn", up = mutableMapOf(TellomiRegionId.CN to true))
    assertThat(auto(upW).evaluate()).isEqualTo(Reason.SWITCH)
    assertThat(upW.switched).isEqualTo(listOf(TellomiRegionId.CN))
    assertThat(upW.stored).isEqualTo("cn")
  }

  @Test
  fun `reverting the key moves back to global after a probe`() {
    for (target in listOf("global", "", null)) {
      val w = World(stored = "cn", target = target, up = mutableMapOf(TellomiRegionId.GLOBAL to true))
      assertThat(auto(w).evaluate()).isEqualTo(Reason.SWITCH)
      assertThat(w.stored).isEqualTo("global")
      assertThat(w.probed).isEqualTo(listOf(TellomiRegionId.GLOBAL))
    }
  }

  @Test
  fun `falls back after repeated failures and returns when the target answers`() {
    val w = World(stored = "cn", target = "cn", up = mutableMapOf(TellomiRegionId.CN to false, TellomiRegionId.GLOBAL to true))
    val a = auto(w)
    repeat(TellomiRegionAutoSwitch.DEFAULT_FAILURE_THRESHOLD - 1) { i ->
      assertThat(a.evaluate()).isEqualTo(Reason.CURRENT_FAILING)
      assertThat(w.failures).isEqualTo(i + 1)
    }
    assertThat(a.evaluate()).isEqualTo(Reason.FAILOVER)
    assertThat(w.stored).isEqualTo("global")
    assertThat(w.failures).isEqualTo(0)

    w.up[TellomiRegionId.CN] = true
    assertThat(a.evaluate()).isEqualTo(Reason.SWITCH)
    assertThat(w.stored).isEqualTo("cn")
  }

  @Test
  fun `no alternative when both regions are down`() {
    val w = World(stored = "cn", target = "cn", failures = 2, up = mutableMapOf(TellomiRegionId.CN to false, TellomiRegionId.GLOBAL to false))
    assertThat(auto(w).evaluate()).isEqualTo(Reason.NO_ALTERNATIVE)
    assertThat(w.stored).isEqualTo("cn")
  }

  @Test
  fun `no failover without an explicit key`() {
    val w = World(stored = null, target = null, failures = 99)
    assertThat(auto(w).evaluate()).isEqualTo(Reason.OFF)
    assertThat(w.probed).isEmpty()
    assertThat(w.failures).isEqualTo(0)
  }

  @Test
  fun `a disabled cn is never a target`() {
    val w = World(stored = "cn", target = "cn", up = mutableMapOf(TellomiRegionId.GLOBAL to true))
    // stored cn but cn disabled resolves to global; the target falls back to global (not explicit): nothing to do
    assertThat(auto(w, listOf(global, cn.copy(enabled = false))).evaluate()).isEqualTo(Reason.OFF)
    assertThat(TellomiRegionId.CN in w.probed).isFalse()
  }

  @Test
  fun `server supplied hosts must be under the current region root`() {
    assertThat(TellomiRegions.tellomiRootOf("CHAT.tellomi.cn.")).isEqualTo("tellomi.cn")
    assertThat(TellomiRegions.tellomiRootOf("nottellomi.cn") == null).isTrue()
    assertThat(TellomiRegions.isHostInRegion("contentproxy.tellomi.cn", cn)).isTrue()
    assertThat(TellomiRegions.isHostInRegion("contentproxy.tellomi.app", cn)).isFalse()
    assertThat(TellomiRegions.isHostInRegion("contentproxy.tellomi.app", global)).isTrue()
    assertThat(TellomiRegions.isHostInRegion("proxy.example.com", global)).isFalse()
    assertThat(TellomiRegions.isHostInRegion("anything.example", cn.copy(chat = "https://chat.tellomi.test"))).isTrue()
  }
}
