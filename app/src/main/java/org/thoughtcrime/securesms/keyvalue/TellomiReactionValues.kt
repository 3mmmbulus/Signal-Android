/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.keyvalue

/**
 * Tellomi（chat-telegram-parity F01）：别人回应了我的消息、我还没在聊天里看到的那些消息，规则见
 * [org.thoughtcrime.securesms.reactions.TellomiUnseenReactions]。
 *
 * 只在本机，不进备份：换设备、恢复备份后从空开始。
 */
class TellomiReactionValues(store: KeyValueStore) : SignalStoreValues(store) {
  companion object {
    private const val UNSEEN = "tellomi_reactions.unseen"
  }

  public override fun onFirstEverAppLaunch() = Unit
  public override fun getKeysToIncludeInBackup(): List<String> = emptyList()

  /** `会话 id:消息 id,消息 id;会话 id:…`，读写都经过 `TellomiUnseenReactions`。 */
  var unseenSerialized: String by stringValue(UNSEEN, "")
}
