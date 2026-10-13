/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed

import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.RemoteConfig

/**
 * M11 动态（ADR-0071、`docs/product/specs/dynamic-feed.md`、tellomi/tellomi#1355）的总开关。
 *
 * 客户端不做任何区域判断（ADR-0071 §十五）：显不显示入口只看服务端下发的 `global.feed.enabled`。
 * 内部用户可以在「内部设置 → Force feed tab」里先打开，方便在服务端下发之前开发和内测。
 */
object TellomiFeed {

  @JvmStatic
  fun isTabEnabled(): Boolean {
    return isTabEnabled(remoteEnabled = RemoteConfig.feedEnabled, forcedByInternalUser = SignalStore.internal.forceFeedTab)
  }

  /** 纯函数版本，单测用。 */
  @JvmStatic
  fun isTabEnabled(remoteEnabled: Boolean, forcedByInternalUser: Boolean): Boolean {
    return remoteEnabled || forcedByInternalUser
  }
}
