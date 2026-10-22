/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import androidx.annotation.WorkerThread
import org.signal.core.util.logging.Log

/**
 * Tellomi（REGION_PROFILE 第六节之二）：远程配置 `global.tellomi.region.target` 驱动的切区。
 *
 * - 键不存在 / 空 / 认不出：功能关闭，目标 = global，不做失败回落。当前已在 global 就什么都不探。
 * - 当前区 ≠ 目标：探目标的 chat（TCP + TLS，App 自己的信任根）；通了才切（[TellomiRegionSwitcher]，记住 = last good）。
 * - 当前区 = 目标且键是显式的：探当前区；连续 [failureThreshold] 次不通就探另一个开着的区，通了就切过去；
 *   下次评估时当前区 ≠ 目标，会再探目标，通了就切回来。
 *
 * 接线（单例、后台线程、跨境告知的闸）在 [TellomiRegionAutoSwitchRunner]。评估时机：远程配置刷新之后（[org.thoughtcrime.securesms.jobs.RemoteConfigRefreshJob]）、App 回到前台。
 * 跨境告知没点之前（[TellomiCrossBorderNetworkGate]）一概不探。规则和 Desktop `tellomiRegionTarget.std.ts`、
 * iOS `TellomiRegionAutoSwitch.swift` 逐条一致。
 */
class TellomiRegionAutoSwitch(
  private val profiles: () -> List<TellomiRegionProfile>,
  private val storedId: () -> String?,
  private val targetValue: () -> String?,
  private val loadFailures: () -> Int,
  private val storeFailures: (Int) -> Unit,
  private val probe: (TellomiRegionProfile) -> Boolean,
  private val switchTo: (TellomiRegionId) -> Unit,
  private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD
) {

  data class Target(val id: TellomiRegionId, val explicit: Boolean)

  enum class Reason {
    OFF,
    SWITCH,
    TARGET_UNREACHABLE,
    STAY,
    CURRENT_FAILING,
    FAILOVER,
    NO_ALTERNATIVE
  }

  @WorkerThread
  @Synchronized
  fun evaluate(): Reason {
    val table = profiles()
    val parsed = parseTarget(targetValue())
    val targetProfile = table.firstOrNull { it.id == parsed.id && it.enabled }
      ?: table.first { it.id == TellomiRegionId.GLOBAL }
    val explicit = parsed.explicit && targetProfile.id == parsed.id
    val current = TellomiRegions.resolve(storedId(), table)

    if (current.id != targetProfile.id) {
      if (safeProbe(targetProfile)) {
        storeFailures(0)
        switchTo(targetProfile.id)
        return Reason.SWITCH
      }
      return Reason.TARGET_UNREACHABLE
    }

    if (!explicit) {
      if (loadFailures() != 0) storeFailures(0)
      return Reason.OFF
    }

    if (safeProbe(current)) {
      if (loadFailures() != 0) storeFailures(0)
      return Reason.STAY
    }

    val failures = loadFailures() + 1
    if (failures < failureThreshold) {
      storeFailures(failures)
      return Reason.CURRENT_FAILING
    }

    val other = table.firstOrNull { it.enabled && it.id != current.id }
    if (other != null && safeProbe(other)) {
      storeFailures(0)
      switchTo(other.id)
      return Reason.FAILOVER
    }
    storeFailures(failures)
    return Reason.NO_ALTERNATIVE
  }

  private fun safeProbe(profile: TellomiRegionProfile): Boolean {
    return try {
      probe(profile)
    } catch (e: Exception) {
      Log.w(TAG, "probe of ${profile.id.id} threw", e)
      false
    }
  }

  companion object {
    private val TAG = Log.tag(TellomiRegionAutoSwitch::class.java)

    const val KEY = "global.tellomi.region.target"
    const val DEFAULT_FAILURE_THRESHOLD = 3

    @JvmStatic
    fun parseTarget(value: String?): Target {
      val id = TellomiRegionId.entries.firstOrNull { it.id == value }
      return if (id == null) Target(TellomiRegionId.GLOBAL, explicit = false) else Target(id, explicit = true)
    }
  }
}
