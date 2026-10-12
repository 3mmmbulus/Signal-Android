/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

/**
 * 「必须更新」阻断页（tellomi/tellomi#1138）。官网版在页内完成 检查 → 下载（进度画在按钮里）→ 调系统安装器；
 * 其它渠道按钮直接去商店。
 */
class UpdateRequiredViewModel(
  private val repository: UpdateRequiredRepository,
  private val pollIntervalMs: Long = 500
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(UpdateRequiredViewModel::class)

    /** 清单检查成功、确有新版本，但这么久还没在 DownloadManager 里看到下载 → 按失败处理，让用户能重试。 */
    const val NO_DOWNLOAD_TIMEOUT_MS = 15_000L

    /**
     * 一条在下（或暂停着）的下载这么久没有新字节 → 按失败处理，给「重试」和「去官网下载」（taishi 审查 b14 要改 1）。
     * DownloadManager 自己的重试（首轮约 30 秒）会在这之前发生；「等 Wi-Fi」是明示的状态，不算卡住。
     */
    const val STALL_TIMEOUT_MS = 90_000L
  }

  private val _state = MutableStateFlow(
    repository.manifestInfo().let { manifest ->
      UpdateRequiredState(
        reason = repository.reason(),
        managesAppUpdates = repository.managesAppUpdates,
        // 下载前就能显示「版本 x · 大小」：上游的清单检查还没跑过时，先用更新清单里的（tellomi/tellomi#1491）
        newVersionName = repository.availableVersionName() ?: manifest?.newerVersionName,
        totalBytes = manifest?.sizeBytes,
        isOffline = !repository.isOnline(),
        notesUrl = manifest?.notesUrl
      )
    }
  )
  val state: StateFlow<UpdateRequiredState> = _state.asStateFlow()

  private val _actions = Channel<UpdateRequiredScreenAction>(Channel.UNLIMITED)
  val actions: Flow<UpdateRequiredScreenAction> = _actions.receiveAsFlow()

  private var downloadJob: Job? = null
  private var installRequestedFor: Long = -1

  init {
    if (repository.managesAppUpdates) {
      // 后台的定期检查、或上次打开时点出来的下载，可能正在下、暂停着、或已经下完：接上。
      // 不管哪种都不替用户点安装：下完显示「安装更新」，等用户点（taishi 审查 b14 要改 2——
      // 官网版有 UPDATE_PACKAGES_WITHOUT_USER_ACTION，Android 12+ 上可能不弹系统界面就直接装完重启）。
      // 只许 Wi-Fi、又没在下的，轮询里会显示「等 Wi-Fi」并给出「用移动数据下载」。
      downloadJob = viewModelScope.launch(SignalDispatchers.IO) {
        val existing = repository.currentDownload() ?: return@launch
        // 装上新版本以后，上一轮下好的包还记在 SignalStore 里，它已经不比现在装的新（availableVersionName 为 null）：
        // 不接，免得页面显示「安装更新」去重装同一个版本（2026-09-24 模拟器实测发现）。
        if (existing.status != UpdateDownloadSnapshot.Status.FAILED && repository.availableVersionName() != null) {
          pollDownload(installWhenDone = false)
        }
      }
    }
  }

  fun onEvent(event: UpdateRequiredScreenEvent) {
    when (event) {
      UpdateRequiredScreenEvent.ScreenResumed -> onScreenResumed()
      UpdateRequiredScreenEvent.PrimaryClicked -> onPrimaryClicked()
      UpdateRequiredScreenEvent.DownloadFromWebsiteClicked -> _actions.trySend(UpdateRequiredScreenAction.OpenDownloadPage)
      UpdateRequiredScreenEvent.LearnMoreClicked -> _state.value.notesUrl?.let { _actions.trySend(UpdateRequiredScreenAction.OpenNotes(it)) }
      UpdateRequiredScreenEvent.ViewChatsOnlyClicked -> _actions.trySend(if (repository.isBlocking()) UpdateRequiredScreenAction.ConfirmViewChatsOnly else UpdateRequiredScreenAction.LeavePage)
      UpdateRequiredScreenEvent.ViewChatsOnlyConfirmed -> onViewChatsOnlyConfirmed()
    }
  }

  /**
   * owner 2026-09-24：任何情况下都不能把用户锁在自己的聊天记录外面（记录只在这台手机上）。
   * 正在下的包不取消，下完照上游发「点此安装」通知。
   */
  private fun onViewChatsOnlyConfirmed() {
    repository.chooseReadOnly()
    _actions.trySend(UpdateRequiredScreenAction.EnterReadOnly)
  }

  private fun onScreenResumed() {
    _state.update { it.copy(isOffline = !repository.isOnline()) }

    // 从系统设置回来：权限给了就接着更新（需求 3.4「先说明再跳系统设置，回来后继续安装」）。
    if (_state.value.download == Download.NeedsInstallPermission && repository.canRequestPackageInstalls()) {
      startDownload()
    }

    // 每次回到这一页都重拉一次更新清单（tellomi/tellomi#1491）：「必须」撤销了就放行（需求 3.4「撤销」、判据 7），
    // 原因和「了解更新内容」也跟着最新的清单走。
    viewModelScope.launch(SignalDispatchers.IO) {
      if (!repository.refreshRequiredState()) {
        Log.i(TAG, "No update is required anymore.")
        _actions.trySend(UpdateRequiredScreenAction.Released)
        return@launch
      }

      val manifest = repository.manifestInfo()
      _state.update {
        it.copy(
          reason = repository.reason(),
          notesUrl = manifest?.notesUrl ?: it.notesUrl,
          newVersionName = it.newVersionName ?: manifest?.newerVersionName,
          totalBytes = it.totalBytes ?: manifest?.sizeBytes
        )
      }
    }
  }

  private fun onPrimaryClicked() {
    if (!repository.managesAppUpdates) {
      _actions.trySend(UpdateRequiredScreenAction.OpenDownloadPage)
      return
    }

    when (_state.value.download) {
      Download.Idle, Download.Failed, Download.NoNewerVersion, Download.WaitingForWifi -> {
        if (repository.canRequestPackageInstalls()) {
          // 从「重试」点进来的：卡住不动的那条流量下载也删掉重排，否则再跟 90 秒同一条又转失败（taishi 审查 b14 包 7 不阻塞 2）
          startDownload(restartStuckDownload = _state.value.download == Download.Failed)
        } else {
          _state.update { it.copy(download = Download.NeedsInstallPermission) }
        }
      }
      Download.NeedsInstallPermission -> _actions.trySend(UpdateRequiredScreenAction.OpenInstallPermissionSettings)
      is Download.InProgress -> Unit
      Download.ReadyToInstall -> retryInstall()
    }
  }

  private fun startDownload(restartStuckDownload: Boolean = false) {
    downloadJob?.cancel()
    _state.update { it.copy(download = Download.InProgress(percent = null)) }
    downloadJob = viewModelScope.launch(SignalDispatchers.IO) { checkAndDownload(restartStuckDownload) }
  }

  private suspend fun checkAndDownload(restartStuckDownload: Boolean = false) {
    val checked = repository.runRequiredUpdateCheck(restartStuckDownload)
    val newVersionName = repository.availableVersionName()
    _state.update { it.copy(newVersionName = newVersionName, isOffline = !repository.isOnline()) }

    if (!checked) {
      // 检查没成功（多半是没网），但之前已经完整下好的包仍然可以装——前提是它比现在装的新（同上）。
      if (repository.currentDownload()?.status == UpdateDownloadSnapshot.Status.SUCCESSFUL && repository.availableVersionName() != null) {
        pollDownload(installWhenDone = true)
      } else {
        Log.w(TAG, "Required update check failed.")
        _state.update { it.copy(download = Download.Failed) }
      }
      return
    }

    if (newVersionName == null) {
      Log.w(TAG, "The update manifest has no newer version than this build.")
      _state.update { it.copy(download = Download.NoNewerVersion) }
      return
    }

    pollDownload(installWhenDone = true)
  }

  private suspend fun pollDownload(installWhenDone: Boolean) {
    var waitedWithoutDownloadMs = 0L
    var stalledMs = 0L
    var lastDownloadId = -1L
    var lastBytes = -1L

    while (currentCoroutineContext().isActive) {
      val download = repository.currentDownload()

      if (download != null && (download.downloadId != lastDownloadId || download.bytesSoFar != lastBytes)) {
        lastDownloadId = download.downloadId
        lastBytes = download.bytesSoFar
        stalledMs = 0
      }

      when (download?.status) {
        null -> {
          waitedWithoutDownloadMs += pollIntervalMs
          if (waitedWithoutDownloadMs >= NO_DOWNLOAD_TIMEOUT_MS) {
            Log.w(TAG, "No download showed up after the update check.")
            _state.update { it.copy(download = Download.Failed) }
            return
          }
        }

        UpdateDownloadSnapshot.Status.SUCCESSFUL -> {
          _state.update { it.copy(download = Download.ReadyToInstall, totalBytes = download.totalBytes.takeIf { total -> total > 0 } ?: it.totalBytes) }
          if (installWhenDone && installRequestedFor != download.downloadId) {
            installRequestedFor = download.downloadId
            repository.install(download.downloadId)
          }
          return
        }

        UpdateDownloadSnapshot.Status.FAILED -> {
          Log.w(TAG, "Download ${download.downloadId} failed.")
          _state.update { it.copy(download = Download.Failed) }
          return
        }

        UpdateDownloadSnapshot.Status.PENDING,
        UpdateDownloadSnapshot.Status.PAUSED -> {
          if (!download.allowsMetered && download.waitingForNetwork) {
            // 只许 Wi-Fi 的下载没在下（没连 Wi-Fi，或者流量下载失败后上游改排了一条只许 Wi-Fi 的）：
            // 说清楚，主按钮给「用移动数据下载」（taishi 审查 b14 要改 1）。有 Wi-Fi 了它会自己开始下，轮询接着看。
            stalledMs = 0
            _state.update { it.copy(download = Download.WaitingForWifi, totalBytes = download.totalBytes.takeIf { total -> total > 0 } ?: it.totalBytes, isOffline = !repository.isOnline()) }
          } else if (onProgress(download, stalledMs)) {
            return
          }
        }

        UpdateDownloadSnapshot.Status.RUNNING -> {
          if (onProgress(download, stalledMs)) {
            return
          }
        }
      }

      stalledMs += pollIntervalMs

      delay(pollIntervalMs)
    }
  }

  /** 显示进度；卡住太久（没有新字节）就转失败并返回 true，结束轮询。 */
  private fun onProgress(download: UpdateDownloadSnapshot, stalledMs: Long): Boolean {
    if (stalledMs >= STALL_TIMEOUT_MS) {
      Log.w(TAG, "Download ${download.downloadId} made no progress for ${stalledMs}ms.")
      _state.update { it.copy(download = Download.Failed, isOffline = !repository.isOnline()) }
      return true
    }

    val total = download.totalBytes.takeIf { it > 0 }
    val percent = total?.let { (download.bytesSoFar * 100 / it).toInt().coerceIn(0, 100) }
    _state.update { it.copy(download = Download.InProgress(percent), totalBytes = total ?: it.totalBytes, isOffline = !repository.isOnline()) }
    return false
  }

  /** 用户在系统安装界面点了取消，或安装失败：再点一次按钮重新调起。包没了就从头检查、下载。 */
  private fun retryInstall() {
    downloadJob?.cancel()
    downloadJob = viewModelScope.launch(SignalDispatchers.IO) {
      val download = repository.currentDownload()
      if (download?.status == UpdateDownloadSnapshot.Status.SUCCESSFUL) {
        installRequestedFor = download.downloadId
        repository.install(download.downloadId)
      } else {
        _state.update { it.copy(download = Download.InProgress(percent = null)) }
        checkAndDownload()
      }
    }
  }
}
