package org.thoughtcrime.securesms.megaphone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.updaterequired.DefaultUpdateRequiredRepository
import org.thoughtcrime.securesms.updaterequired.UpdateRequired
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreen
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreenAction
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredScreenEvent
import org.thoughtcrime.securesms.updaterequired.UpdateRequiredViewModel
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.PlayStoreUtil
import org.thoughtcrime.securesms.util.viewModel

/**
 * Shown when a users build fully expires. Controlled by [Megaphones.Event.CLIENT_DEPRECATED].
 *
 * Tellomi（tellomi/tellomi#1138，需求 app-update-and-version-policy 第 3.4 节）：改成「必须更新」阻断页 [UpdateRequiredScreen]。
 * - 上游的「不要更新」保留成「暂不更新，只看聊天记录」（owner 2026-09-24 规则 1）：确认后从路由重新进 App，
 *   应用锁上着就先过锁，然后是上游的只读模式。返回键只把 App 退到后台。
 * - 不继承 PassphraseRequiredActivity：页面上没有任何私人内容，应用锁没解开也能先更新。
 * - 什么时候自动出现看 [UpdateRequired.shouldBlock]（只在服务端判定、且没选只读时）；只读横幅上的「立即更新」也会打开这里。
 */
class ClientDeprecatedActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(ClientDeprecatedActivity::class)

    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, ClientDeprecatedActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: UpdateRequiredViewModel by viewModel { UpdateRequiredViewModel(DefaultUpdateRequiredRepository(application)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    if (!UpdateRequired.isRequired()) {
      Log.w(TAG, "Opened while no update is required. Finishing.")
      finish()
      return
    }

    onBackPressedDispatcher.addCallback(this) {
      // Tellomi（taishi 审查 b14 包 8 不阻塞 1）：只有自动盖上的阻断页，返回键才把 App 退到后台；
      // 从只读横幅 / 输入框 / 通知主动点进来的，返回就关掉这一页，回到原来的地方。
      if (UpdateRequired.shouldBlock()) {
        moveTaskToBack(true)
      } else {
        finish()
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.actions.collect { action ->
          when (action) {
            UpdateRequiredScreenAction.OpenInstallPermissionSettings -> openInstallPermissionSettings()
            UpdateRequiredScreenAction.OpenDownloadPage -> PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(this@ClientDeprecatedActivity)
            UpdateRequiredScreenAction.ConfirmViewChatsOnly -> confirmViewChatsOnly()
            UpdateRequiredScreenAction.EnterReadOnly -> enterReadOnly()
            UpdateRequiredScreenAction.LeavePage -> finish()
            is UpdateRequiredScreenAction.OpenNotes -> CommunicationActions.openBrowserLink(this@ClientDeprecatedActivity, action.url)
            // 更新清单撤销了「必须」（tellomi/tellomi#1491）：和进只读一样从路由重新进 App，这次能收发
            UpdateRequiredScreenAction.Released -> enterReadOnly()
          }
        }
      }
    }

    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()

      SignalTheme {
        UpdateRequiredScreen(
          state = state,
          onEvent = viewModel::onEvent
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    viewModel.onEvent(UpdateRequiredScreenEvent.ScreenResumed)
  }

  /**
   * 上游「不要更新」的确认框：能看记录，更新之前不能收发。正文换成 Tellomi 的句子，不说「已过期」——
   * 服务端要求更新时版本并没有过期（taishi 审查包 8）；标题和按钮照旧用上游的。
   */
  private fun confirmViewChatsOnly() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.ClientDeprecatedActivity_warning)
      .setMessage(R.string.TellomiUpdateRequired__view_chats_only_confirm_message)
      .setPositiveButton(R.string.ClientDeprecatedActivity_dont_update) { _, _ -> viewModel.onEvent(UpdateRequiredScreenEvent.ViewChatsOnlyConfirmed) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** 从路由重新进：MainActivity 继承 PassphraseRequiredActivity，锁上着会先去解锁（owner 规则 1「进只读要先过应用锁」）。 */
  private fun enterReadOnly() {
    startActivity(MainActivity.clearTop(this))
    finish()
  }

  private fun openInstallPermissionSettings() {
    if (Build.VERSION.SDK_INT < 26) {
      return
    }

    try {
      startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No screen for the install-unknown-apps setting. Opening app details instead.", e)
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
  }
}
