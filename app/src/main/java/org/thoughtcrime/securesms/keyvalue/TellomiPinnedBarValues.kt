/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi（pinned-messages.md 3.4）：群里没有置顶权限的人点置顶栏的 ×，只在本机藏起置顶栏，直到又有新的置顶——记下藏的时候最新那条置顶，
 * 最新那条还是它就一直藏着（两端 Telegram 同样：iOS `closedPinnedMessageId`，安卓 `pin_<会话>`）。
 *
 * 只在本机，不进备份。
 */
class TellomiPinnedBarValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val HIDDEN_PREFIX = "tellomi_pinned_bar.hidden."
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** 藏起置顶栏时最新那条置顶（消息 id）；没藏是 0。 */
  fun hiddenPin(threadId: Long): Long = getLong(HIDDEN_PREFIX + threadId, 0)

  fun hide(threadId: Long, newestPinId: Long) {
    putLong(HIDDEN_PREFIX + threadId, newestPinId)
  }
}
