/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi：当前区域与上一次切区的时间（RegionProfile 契约第六节；tellomi/tellomi#1055）。
 *
 * 只存在本机，不进备份：换一台设备、换一个网络，应该重新探测，而不是沿用旧设备记住的区。
 */
class TellomiRegionValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val CURRENT_ID = "tellomi_region.current_id"
    private const val LAST_SWITCH_AT = "tellomi_region.last_switch_at"
    private const val TEST_REGION_DOMAIN = "tellomi_region.test_region_domain"
    private const val TARGET_FAILURES = "tellomi_region.target_failures"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** 记住的区 id（`global` / `cn`）；null = 没有记录。读的时候由 `TellomiRegions.current()` 兜底，坏值回落 global。 */
  var currentId: String? by stringValue(CURRENT_ID, null)

  /** 上一次切区的时间（毫秒）。0 = 没有记录，最小驻留视为已满足（冷启动时 RTT 更低的区直接胜出）。 */
  var lastSwitchAt: Long by longValue(LAST_SWITCH_AT, 0)

  /** 远程配置切区（REGION_PROFILE 第六节之二）：键显式时，当前区连续探测失败的次数。 */
  var targetFailures: Int by integerValue(TARGET_FAILURES, 0)

  /**
   * 测试区的域名（例如 `tellomi.test`）；null = 不开。**只在 debug 包里生效**（`TellomiRegions.profiles()`），
   * 在 Internal 设置里填。用来在 CN 保持关闭的前提下验切区（#1055 判据 2）。
   */
  var testRegionDomain: String? by stringValue(TEST_REGION_DOMAIN, null)
}
