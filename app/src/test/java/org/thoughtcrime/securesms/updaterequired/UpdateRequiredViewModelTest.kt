/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import androidx.lifecycle.viewModelScope
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.testing.CoroutineDispatcherRule
import org.thoughtcrime.securesms.updaterequired.UpdateDownloadSnapshot.Status
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredState.Download

/**
 * Tellomi（tellomi/tellomi#1138）：「必须更新」阻断页的状态机。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequiredViewModelTest {

  companion object {
    private const val POLL_MS = 500L
    private const val DOWNLOAD_ID = 7L
  }

  private val testDispatcher = StandardTestDispatcher()

  @get:Rule
  val dispatcherRule = CoroutineDispatcherRule(testDispatcher)

  private val repository = FakeRepository()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `without the install permission it explains first, then opens settings`() = runViewModelTest {
    repository.canInstall = false
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.NeedsInstallPermission)
    assertThat(actions).isEmpty()
    assertThat(repository.checks).isEqualTo(0)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.OpenInstallPermissionSettings)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `coming back from settings with the permission granted carries on with the update`() = runViewModelTest {
    repository.canInstall = false
    val viewModel = createViewModel()
    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    repository.canInstall = true
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    runCurrent()

    assertThat(repository.checks).isEqualTo(1)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `download progress is drawn in the button and the installer opens once it finishes`() = runViewModelTest {
    val viewModel = createViewModel()
    repository.script(
      snapshot(Status.PENDING, 0, -1),
      snapshot(Status.RUNNING, 42, 100),
      snapshot(Status.SUCCESSFUL, 100, 100)
    )

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 42))
    assertThat(viewModel.state.value.totalBytes).isEqualTo(100L)

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `tapping install again after cancelling the system installer does not download again`() = runViewModelTest {
    val viewModel = createViewModel()
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(repository.checks).isEqualTo(1)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID, DOWNLOAD_ID)
  }

  @Test
  fun `a failed check turns the button into retry`() = runViewModelTest {
    repository.checkResult = false
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(repository.checks).isEqualTo(2)
  }

  @Test
  fun `a failed download turns the button into retry`() = runViewModelTest {
    val viewModel = createViewModel()
    repository.script(snapshot(Status.RUNNING, 10, 100), snapshot(Status.FAILED, 10, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    advanceTimeBy(POLL_MS)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
    assertThat(repository.installs).isEmpty()
  }

  @Test
  fun `a manifest with nothing newer says so instead of spinning`() = runViewModelTest {
    repository.availableVersion = null
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.NoNewerVersion)
  }

  @Test
  fun `a download that never shows up ends in retry`() = runViewModelTest {
    val viewModel = createViewModel()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = null))

    advanceTimeBy(UpdateRequiredViewModel.NO_DOWNLOAD_TIMEOUT_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
  }

  @Test
  fun `a package the background job already finished waits for the user before installing`() = runViewModelTest {
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    val viewModel = createViewModel()
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).isEmpty()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `builds that do not update themselves send the user to the download page`() = runViewModelTest {
    repository.managesAppUpdates = false
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    viewModel.onEvent(UpdateRequiredScreenEvent.DownloadFromWebsiteClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.OpenDownloadPage, UpdateRequiredScreenAction.OpenDownloadPage)
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `the offline hint follows the connection`() = runViewModelTest {
    repository.online = false
    val viewModel = createViewModel()
    assertThat(viewModel.state.value.isOffline).isEqualTo(true)

    repository.online = true
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    assertThat(viewModel.state.value.isOffline).isEqualTo(false)
  }

  // ==================== taishi 审查 b14（包 4）====================

  @Test
  fun `when a Wi-Fi-only download takes over, the page offers mobile data instead of hanging`() = runViewModelTest {
    // 要改 1 (a)：流量下载失败后，上游收到广播就改排了一条只许 Wi-Fi 的下载，SignalStore 里的 id 换成了它。
    val viewModel = createViewModel()
    repository.script(
      snapshot(Status.RUNNING, 10, 100),
      snapshot(Status.PENDING, 0, -1, id = 8, allowsMetered = false)
    )

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 10))

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.WaitingForWifi)

    // 按钮点了有用：重新走一次允许流量的检查（任务里会把那条只许 Wi-Fi 的改排）。
    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(repository.checks).isEqualTo(2)
  }

  @Test
  fun `a background download that pauses off Wi-Fi offers mobile data`() = runViewModelTest {
    // 要改 1 (b)：打开页面时后台的 Wi-Fi 下载正在跑，之后离开了 Wi-Fi。
    // 初始化读一次、轮询第一轮再读一次，所以 RUNNING 放两份。
    repository.script(
      snapshot(Status.RUNNING, 30, 100, allowsMetered = false),
      snapshot(Status.RUNNING, 30, 100, allowsMetered = false),
      snapshot(Status.PAUSED, 30, 100, allowsMetered = false, waitingForNetwork = true)
    )
    val viewModel = createViewModel()
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 30))

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.WaitingForWifi)
  }

  @Test
  fun `a download picked up on open is not installed until the user taps`() = runViewModelTest {
    // 要改 2：打开页面时后台下载在跑，下完不能替用户点安装。
    repository.script(
      snapshot(Status.RUNNING, 50, 100, allowsMetered = false),
      snapshot(Status.SUCCESSFUL, 100, 100, allowsMetered = false)
    )
    val viewModel = createViewModel()
    runCurrent()
    advanceTimeBy(POLL_MS)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).isEmpty()

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `a paused mobile-data download is picked up on open instead of starting over`() = runViewModelTest {
    // 要改 3：上次点出来的流量下载因为信号差暂停着，重开 App 后接着显示进度，不需要再点、也不会被从 0 重下。
    repository.script(snapshot(Status.PAUSED, 40, 100, allowsMetered = true))
    val viewModel = createViewModel()
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 40))
    assertThat(repository.checks).isEqualTo(0)
  }

  @Test
  fun `a download that stops moving turns into retry`() = runViewModelTest {
    val viewModel = createViewModel()
    repository.script(snapshot(Status.RUNNING, 20, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 20))

    advanceTimeBy(UpdateRequiredViewModel.STALL_TIMEOUT_MS - POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 20))

    advanceTimeBy(POLL_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
  }

  @Test
  fun `a package left over from before the last update is not picked up`() = runViewModelTest {
    // 已经装上那个版本：清单里没有更新的版本了，上一轮下好的包还记着。
    repository.availableVersion = null
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))
    val viewModel = createViewModel()
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.Idle)
    assertThat(repository.installs).isEmpty()
  }

  @Test
  fun `when the check fails offline, a leftover package that is not newer is not installed`() = runViewModelTest {
    repository.checkResult = false
    repository.availableVersion = null
    val viewModel = createViewModel()
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
    assertThat(repository.installs).isEmpty()
  }

  // ==================== taishi 审查 b14（包 7、包 8）====================

  @Test
  fun `retrying after a stall asks the check to restart the stuck download`() = runViewModelTest {
    // 包 7 不阻塞 2：卡住转「重试」以后再点，要把那条允许流量、却不动的下载删掉重排，不然 90 秒后又是同一条失败。
    val viewModel = createViewModel()
    repository.script(snapshot(Status.RUNNING, 20, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    advanceTimeBy(UpdateRequiredViewModel.STALL_TIMEOUT_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(repository.restartFlags).containsExactly(false, true)
  }

  @Test
  fun `a Wi-Fi-only download paused for a server retry is not called waiting for Wi-Fi`() = runViewModelTest {
    // 包 7 不阻塞 3：连着 Wi-Fi、服务器出错进入 WAITING_TO_RETRY 的只许 Wi-Fi 的下载，不该说「原本只在连上 Wi-Fi 时下载」。
    val viewModel = createViewModel()
    repository.script(snapshot(Status.PAUSED, 30, 100, allowsMetered = false, waitingForNetwork = false))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.InProgress(percent = 30))

    advanceTimeBy(UpdateRequiredViewModel.STALL_TIMEOUT_MS)
    runCurrent()
    assertThat(viewModel.state.value.download).isEqualTo(Download.Failed)
  }

  @Test
  fun `when the check fails offline, a finished package that is newer is installed`() = runViewModelTest {
    // 包 8 不阻塞 5 后半：检查失败分支原来只有反向用例；比现在装的新、已经下好的包，没网也照样装。
    repository.checkResult = false
    val viewModel = createViewModel()
    repository.script(snapshot(Status.SUCCESSFUL, 100, 100))

    viewModel.onEvent(UpdateRequiredScreenEvent.PrimaryClicked)
    runCurrent()

    assertThat(viewModel.state.value.download).isEqualTo(Download.ReadyToInstall)
    assertThat(repository.installs).containsExactly(DOWNLOAD_ID)
  }

  @Test
  fun `when it is not blocking, only viewing the chats just leaves the page`() = runViewModelTest {
    // 包 8 不阻塞 1：从只读横幅主动点进来的（已经选过只读，或者构建到期），出口不用再确认，关掉这一页就行。
    repository.blocking = false
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.ViewChatsOnlyClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.LeavePage)
    assertThat(repository.readOnlyChoices).isEqualTo(0)
  }

  // ==================== owner 2026-09-24 规则 1：只读出口 ====================

  @Test
  fun `only viewing the chats asks first, then remembers the choice and leaves for the chats`() = runViewModelTest {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.ViewChatsOnlyClicked)
    runCurrent()
    assertThat(actions).containsExactly(UpdateRequiredScreenAction.ConfirmViewChatsOnly)
    assertThat(repository.readOnlyChoices).isEqualTo(0)

    viewModel.onEvent(UpdateRequiredScreenEvent.ViewChatsOnlyConfirmed)
    runCurrent()
    assertThat(actions).containsExactly(UpdateRequiredScreenAction.ConfirmViewChatsOnly, UpdateRequiredScreenAction.EnterReadOnly)
    assertThat(repository.readOnlyChoices).isEqualTo(1)
    assertThat(repository.checks).isEqualTo(0)
  }

  private val viewModels = mutableListOf<UpdateRequiredViewModel>()

  /**
   * 页面开着时轮询不会自己停：「等 Wi-Fi」要一直看着，连上了就接着显示进度。
   * runTest 在用例体跑完后会把调度器推到空闲，轮询还在就永远推不完，所以先关掉 ViewModel 的作用域，和页面关掉时一样。
   */
  // ==================== tellomi/tellomi#1491：更新清单 ====================

  @Test
  fun `before any download the page shows the manifest's version, size and release notes link`() = runViewModelTest {
    repository.availableVersion = null
    repository.manifest = UpdateManifestInfo(newerVersionName = "0.1.5", sizeBytes = 120_000_000, notesUrl = "https://www.tellomi.app/changelog/#android")

    val state = createViewModel().state.value

    assertThat(state.newVersionName).isEqualTo("0.1.5")
    assertThat(state.totalBytes).isEqualTo(120_000_000L)
    assertThat(state.notesUrl).isEqualTo("https://www.tellomi.app/changelog/#android")
  }

  @Test
  fun `what's in this update opens the release notes`() = runViewModelTest {
    repository.manifest = UpdateManifestInfo(newerVersionName = "0.1.5", sizeBytes = null, notesUrl = "https://www.tellomi.app/changelog/#android")
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)

    viewModel.onEvent(UpdateRequiredScreenEvent.LearnMoreClicked)
    runCurrent()

    assertThat(actions).containsExactly(UpdateRequiredScreenAction.OpenNotes("https://www.tellomi.app/changelog/#android"))
  }

  @Test
  fun `coming back after the required update was withdrawn lets the user back in`() = runViewModelTest {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)
    repository.stillRequired = false

    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    runCurrent()

    assertThat(repository.refreshes).isEqualTo(1)
    assertThat(actions).containsExactly(UpdateRequiredScreenAction.Released)
  }

  @Test
  fun `coming back while still required refreshes the reason from the latest manifest`() = runViewModelTest {
    val viewModel = createViewModel()
    val actions = collectActions(viewModel)
    repository.reasonValue = UpdateRequiredState.Reason.SECURITY

    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
    runCurrent()

    assertThat(viewModel.state.value.reason).isEqualTo(UpdateRequiredState.Reason.SECURITY)
    assertThat(actions).isEmpty()
  }

  private fun runViewModelTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
    try {
      body()
    } finally {
      viewModels.forEach { it.viewModelScope.cancel() }
    }
  }

  private fun createViewModel(): UpdateRequiredViewModel {
    return UpdateRequiredViewModel(repository, pollIntervalMs = POLL_MS).also { viewModels += it }
  }

  private fun TestScope.collectActions(viewModel: UpdateRequiredViewModel): List<UpdateRequiredScreenAction> {
    val actions = mutableListOf<UpdateRequiredScreenAction>()
    backgroundScope.launch { viewModel.actions.toList(actions) }
    runCurrent()
    return actions
  }

  /**
   * 默认是阻断页点出来的流量下载；后台检查排的只许 Wi-Fi 的下载传 allowsMetered = false。
   * [waitingForNetwork] 照真实仓库的算法：PENDING 算在等网络；PAUSED 要看原因，默认不算（服务器出错后的等重试）。
   */
  private fun snapshot(status: Status, bytesSoFar: Long, totalBytes: Long, id: Long = DOWNLOAD_ID, allowsMetered: Boolean = true, waitingForNetwork: Boolean = status == Status.PENDING): UpdateDownloadSnapshot {
    return UpdateDownloadSnapshot(id, status, bytesSoFar, totalBytes, allowsMetered, waitingForNetwork)
  }

  private class FakeRepository : UpdateRequiredRepository {
    override var managesAppUpdates: Boolean = true
    var canInstall = true
    var online = true
    var checkResult = true
    var availableVersion: String? = "0.1.3"
    var checks = 0
    val restartFlags = mutableListOf<Boolean>()
    var blocking = true
    var readOnlyChoices = 0
    val installs = mutableListOf<Long>()
    var reasonValue = UpdateRequiredState.Reason.SERVER_REJECTED
    var manifest: UpdateManifestInfo? = null
    var stillRequired = true
    var refreshes = 0

    /** 每次 [currentDownload] 取下一个；取完之后一直返回最后一个。 */
    private val scripted = ArrayDeque<UpdateDownloadSnapshot>()
    private var last: UpdateDownloadSnapshot? = null

    fun script(vararg snapshots: UpdateDownloadSnapshot) {
      scripted.addAll(snapshots)
    }

    override fun reason(): UpdateRequiredState.Reason = reasonValue
    override fun canRequestPackageInstalls(): Boolean = canInstall
    override fun manifestInfo(): UpdateManifestInfo? = manifest

    override fun refreshRequiredState(): Boolean {
      refreshes++
      return stillRequired
    }
    override fun isOnline(): Boolean = online

    override fun runRequiredUpdateCheck(restartStuckDownload: Boolean): Boolean {
      checks++
      restartFlags += restartStuckDownload
      return checkResult
    }

    override fun isBlocking(): Boolean = blocking

    override fun availableVersionName(): String? = availableVersion

    override fun currentDownload(): UpdateDownloadSnapshot? {
      if (scripted.isNotEmpty()) {
        last = scripted.removeFirst()
      }
      return last
    }

    override fun install(downloadId: Long) {
      installs += downloadId
    }

    override fun chooseReadOnly() {
      readOnlyChoices++
    }
  }
}
