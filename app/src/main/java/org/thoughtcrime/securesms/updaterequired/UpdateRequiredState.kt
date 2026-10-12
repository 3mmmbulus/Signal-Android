/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

/**
 * View state for [UpdateRequiredScreen]（tellomi/tellomi#1138，需求说明 app-update-and-version-policy 第 3.4 节「必须更新」）。
 */
data class UpdateRequiredState(
  val reason: Reason = Reason.SERVER_REJECTED,
  /** 官网版在 App 内下载安装；其它渠道（Play 等）按钮直接去商店。 */
  val managesAppUpdates: Boolean = true,
  val download: Download = Download.Idle,
  /** 清单里比当前安装的新的版本号；不知道时为 null。 */
  val newVersionName: String? = null,
  /** 安装包大小（字节）；下载开始前不知道时为 null（有更新清单时先用清单里的）。 */
  val totalBytes: Long? = null,
  val isOffline: Boolean = false,
  /** 「了解更新内容」打开的网页（更新清单的 notesUrl，tellomi/tellomi#1491）；没有就不显示那个链接。 */
  val notesUrl: String? = null
) {

  enum class Reason {
    /** 服务端拒绝了这个版本（499），或远程配置宣布它到期；更新清单写的原因是 protocol 时也是这一句。 */
    SERVER_REJECTED,

    /** 构建自身过了有效期（Signal 自带的构建过期兜底）。 */
    BUILD_EXPIRED,

    /** 更新清单要求更新，原因写的是 security（需求 3.4「安全」那句，tellomi/tellomi#1491）。 */
    SECURITY,

    /** 更新清单要求更新，原因写的是 other。 */
    OTHER
  }

  sealed interface Download {
    /** 还没开始：主按钮「立即更新」。 */
    data object Idle : Download

    /** 没有「安装未知应用」权限：先说明，再去系统设置。 */
    data object NeedsInstallPermission : Download

    /** 检查清单 / 排队中 / 下载中；[percent] 为 null 表示还不知道进度。 */
    data class InProgress(val percent: Int?) : Download

    /**
     * 跟着的是一条只许 Wi-Fi 的下载（上游后台检查排的），现在没在下：说清楚，主按钮给「用移动数据下载」。
     * 不把它当成下载中，否则按钮点了没反应、页面一直卡着（taishi 审查 b14 要改 1）。
     */
    data object WaitingForWifi : Download

    /** 下载完成并已调起系统安装器；用户在系统安装界面取消时可以再点一次。 */
    data object ReadyToInstall : Download

    /** 清单里没有比当前安装更新的版本。 */
    data object NoNewerVersion : Download

    /** 检查或下载失败：主按钮「重试」，下方「去官网下载」。 */
    data object Failed : Download
  }
}

sealed interface UpdateRequiredScreenEvent {
  data object ScreenResumed : UpdateRequiredScreenEvent
  data object PrimaryClicked : UpdateRequiredScreenEvent
  data object DownloadFromWebsiteClicked : UpdateRequiredScreenEvent

  /** 「了解更新内容」（需求 3.4 布局里的次级文字链）。 */
  data object LearnMoreClicked : UpdateRequiredScreenEvent

  /** 「暂不更新，只看聊天记录」（owner 2026-09-24 规则 1）。先确认。 */
  data object ViewChatsOnlyClicked : UpdateRequiredScreenEvent

  /** 确认框里点了「不要更新」。 */
  data object ViewChatsOnlyConfirmed : UpdateRequiredScreenEvent
}

/** 需要 Activity 才能做的一次性动作。 */
sealed interface UpdateRequiredScreenAction {
  data object OpenInstallPermissionSettings : UpdateRequiredScreenAction

  /** 官网下载页（官网版）或应用商店（其它渠道）。 */
  data object OpenDownloadPage : UpdateRequiredScreenAction

  /** 说明只读的后果，让用户确认（上游「不要更新」的确认框）。 */
  data object ConfirmViewChatsOnly : UpdateRequiredScreenAction

  /** 离开阻断页、从路由重新进 App：应用锁上着就先过锁，然后是上游的只读模式。 */
  data object EnterReadOnly : UpdateRequiredScreenAction

  /** 已经是只读了（选过「只看聊天记录」，或者本机构建到期）：出口直接关掉这一页，不再弹确认框（taishi 审查 b14 包 8 不阻塞 1）。 */
  data object LeavePage : UpdateRequiredScreenAction

  /** 打开更新说明的网页（「了解更新内容」）。 */
  data class OpenNotes(val url: String) : UpdateRequiredScreenAction

  /**
   * 不用更新了：更新清单撤销了「必须」（需求 3.4「撤销」、判据 7，tellomi/tellomi#1491）。和 [EnterReadOnly] 一样从路由重新进 App
   * （应用锁上着先过锁），只是这次能收发。
   */
  data object Released : UpdateRequiredScreenAction
}
