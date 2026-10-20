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
 * 客户端不做任何区域判断（ADR-0071 §十五）：显不显示入口看服务端下发的 `global.feed.enabled`，
 * 再加上内容引擎说的能不能看（内测名单、紧急开关，`GET /v1/feed/status`，契约 3.1，见 [FeedEntryCheck]）。
 * 内部用户可以在「内部设置 → Force feed tab」里先打开，方便在服务端下发之前开发和内测。
 */
object TellomiFeed {

  @JvmStatic
  fun isTabEnabled(): Boolean {
    return isTabEnabled(
      remoteEnabled = RemoteConfig.feedEnabled,
      engineVisible = SignalStore.tellomiFeed.engineVisible,
      forcedByInternalUser = SignalStore.internal.forceFeedTab,
      feedDeployed = FeedCapabilityStore.entries().feed
    )
  }

  /**
   * 纯函数版本，单测用。引擎说不能看（不在内测名单、紧急开关关了）时隐藏入口，不显示空列表（契约 1.1）。
   * [feedDeployed]：`GET /v1/capabilities` 里有 `feed`（契约 3.0）；拿不到按没部署，入口隐藏。内部用户强制打开不看它。
   */
  @JvmStatic
  @JvmOverloads
  fun isTabEnabled(remoteEnabled: Boolean, engineVisible: Boolean, forcedByInternalUser: Boolean, feedDeployed: Boolean = true): Boolean {
    return forcedByInternalUser || (remoteEnabled && engineVisible && feedDeployed)
  }

  /**
   * 内部用户强制打开动态标签、并且没关掉「Feed sample data」时，信息流用本机样例（[SampleFeedSource]），不连内容引擎。
   * 关掉之后强制打开的标签连当前区的内容引擎，用来联调。
   */
  @JvmStatic
  fun isSampleDataEnabled(): Boolean {
    return SignalStore.internal.forceFeedTab && SignalStore.tellomiFeed.useSampleData
  }

  /** 要不要去问内容引擎能不能看：入口可能出现（远程开关开了），或者内部用户要连引擎联调。 */
  @JvmStatic
  fun shouldAskEngine(): Boolean {
    return RemoteConfig.feedEnabled || (SignalStore.internal.forceFeedTab && !SignalStore.tellomiFeed.useSampleData)
  }
}
