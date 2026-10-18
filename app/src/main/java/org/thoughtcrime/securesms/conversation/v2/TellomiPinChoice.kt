/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2

/**
 * Tellomi（ADR-0081 §4.6，pinned-messages.md 3.1）：置顶时先问「给谁看」，最后一行是时长（默认永久）。
 *
 * 照 Telegram iOS 长按菜单原地换成的几项（只读机制，没搬代码）：单聊 `Conversation_PinMessagesFor`「为我和 ×× 置顶」/
 * `Conversation_PinMessagesForMe`「仅为我置顶」，没有默认项；群里 `Conversation_PinMessageAlert_PinAndNotifyMembers`「置顶并通知所有成员」/
 * `Conversation_PinMessageAlert_OnlyPin`（只置顶、不通知）。Tellomi 在群里另加「仅为我置顶」，群里没有置顶权限的人只有这一项（spec 3.1）；
 * 我的收藏直接置顶，不问。
 */
enum class TellomiPinChoice {
  /** 单聊：为我和对方置顶（发给对方）。 */
  FOR_BOTH,

  /** 群：为所有人置顶并通知。 */
  FOR_EVERYONE,

  /** 群：为所有人置顶，不通知（`TellomiDataMessage.pinSilent`）。 */
  FOR_EVERYONE_SILENT,

  /** 仅为我置顶：只同步给自己的设备（`TellomiDataMessage.pinForSelfOnly`），不发给对方。 */
  FOR_ME;

  companion object {
    /** 要问的几项，按显示顺序；null = 不问，直接置顶（我的收藏）。 */
    @JvmStatic
    fun choicesFor(isNoteToSelf: Boolean, isGroup: Boolean, canPinForEveryone: Boolean): List<TellomiPinChoice>? {
      return when {
        isNoteToSelf -> null
        !isGroup -> listOf(FOR_BOTH, FOR_ME)
        canPinForEveryone -> listOf(FOR_EVERYONE, FOR_EVERYONE_SILENT, FOR_ME)
        else -> listOf(FOR_ME)
      }
    }

    /**
     * 能不能取消这条置顶：仅为我置顶的随时能取消（只动我自己的设备，退了群也行）；
     * 别的置顶照 Signal，要群里的置顶权限（Signal 拿「编辑群信息」当置顶权限）且群还在。
     */
    @JvmStatic
    fun canUnpin(isPinnedForMe: Boolean, canPinForEveryone: Boolean, isInactiveGroup: Boolean): Boolean {
      return isPinnedForMe || (canPinForEveryone && !isInactiveGroup)
    }

    /**
     * 置顶列表底下是「取消所有置顶」还是「隐藏置顶消息」（pinned-messages.md 3.5）：每一条都能取消才是前者，
     * 否则只能在本机藏起置顶栏。两端 Telegram 同样按能不能置顶分（安卓 `ChatActivity` `UnpinAllMessages` / `HidePinnedMessages`，
     * iOS `ChatControllerLoadDisplayNode` `canManagePin()`）；Tellomi 多了「仅为我」的置顶，这几条总能取消。
     */
    @JvmStatic
    fun canUnpinAll(pinnedForMe: List<Boolean>, canPinForEveryone: Boolean, isInactiveGroup: Boolean): Boolean {
      return pinnedForMe.all { canUnpin(it, canPinForEveryone, isInactiveGroup) }
    }
  }
}
