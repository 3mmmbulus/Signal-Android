/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi（tellomi/tellomi ADR-0078 §3.4）：服务端最近一次告诉这台设备的人工处置（`TellomiRestrictions`）。
 *
 * 只在本机，不进备份：处置随时会变，换设备、恢复备份后重新问服务端。
 */
class TellomiRestrictionValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val CURRENT = "tellomi_restriction.current"
    private const val LAST_REFRESH_AT = "tellomi_restriction.last_refresh_at"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** 服务端给的 `tellomiRestriction` 原样（JSON）；null = 没有处置。过期的由 `TellomiRestrictions` 清掉。 */
  var currentJson: String? by stringValue(CURRENT, null)

  /** 上一次问服务端的时间（毫秒），回前台至多每 6 小时问一次。 */
  var lastRefreshAt: Long by longValue(LAST_REFRESH_AT, 0)
}
