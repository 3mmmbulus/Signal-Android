/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfileActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 动态的发布页（`dynamic-feed.md` 第三节第 22、23、39 条）。全屏，从动态页右下角的发布按钮进来。
 *
 * 发出去之后带着服务端给的 `state` 关掉，动态页据此提示「已发布」或「审核中」。
 * 带着 [FeedEditTarget] 打开时是编辑那一条（第三节第 23 条），保存后同样带 `state` 关掉。
 */
class FeedComposeActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedComposeViewModel by viewModels {
    viewModelFactory {
      initializer { FeedComposeViewModel(editTarget = IntentCompat.getParcelableExtra(intent, EXTRA_EDIT, FeedEditTarget::class.java)) }
    }
  }

  /** 建好公开主页回来就接着发。 */
  private val profileLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { saved ->
    if (saved) viewModel.publish()
  }

  /** 选人页回来的名单：换成 ACI（没有 ACI 的人签不了名单，去掉）再交给 ViewModel。 */
  private val audienceLauncher = registerForActivityResult(FeedAudiencePickerActivity.Contract()) { ids ->
    val visibility = viewModel.pickingAudienceFor ?: return@registerForActivityResult
    viewModel.pickingAudienceFor = null
    if (ids == null) return@registerForActivityResult
    lifecycleScope.launch {
      val members = withContext(Dispatchers.IO) {
        Recipient.resolvedList(ids.map { RecipientId.from(it) }).mapNotNull { recipient ->
          recipient.aci.orNull()?.let { FeedAudienceMember(recipient.id.toLong(), it.rawUuid.toString()) }
        }
      }
      viewModel.onAudiencePicked(visibility, members)
    }
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()

      LaunchedEffect(state.publishedState) {
        val published = state.publishedState
        if (published != null) {
          setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_STATE, published))
          finish()
        }
      }

      SignalTheme {
        LaunchedEffect(state.profileRequired) {
          if (state.profileRequired) {
            viewModel.onProfileRequiredShown()
            profileLauncher.launch(Unit)
          }
        }
        FeedComposeScreen(
          state = state,
          onClose = { finish() },
          onKindChanged = viewModel::onKindChanged,
          onTitleChanged = viewModel::onTitleChanged,
          onTextChanged = viewModel::onTextChanged,
          onImagesPicked = viewModel::onImagesPicked,
          onImageRemoved = viewModel::onImageRemoved,
          onVisibilityChanged = viewModel::onVisibilityChanged,
          onPublish = viewModel::publish,
          onFailureShown = viewModel::onFailureShown,
          onPickAudience = { visibility ->
            viewModel.pickingAudienceFor = visibility
            audienceLauncher.launch(state.draft.audience.map { it.recipientId }.toLongArray())
          },
          pollActions = remember(viewModel) {
            FeedComposePollActions(
              onAdd = viewModel::onPollAdded,
              onRemove = viewModel::onPollRemoved,
              onOptionChanged = viewModel::onPollOptionChanged,
              onOptionAdded = viewModel::onPollOptionAdded,
              onOptionRemoved = viewModel::onPollOptionRemoved,
              onMultiChanged = viewModel::onPollMultiChanged,
              onDurationChanged = viewModel::onPollDurationChanged
            )
          },
          onReplySettingsChanged = viewModel::onReplySettingsChanged
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  /** 输入 null 是发新的，非 null 是编辑那一条；结果是服务端给的 `state`，用户放弃时是 null。 */
  class Contract : ActivityResultContract<FeedEditTarget?, String?>() {
    override fun createIntent(context: Context, input: FeedEditTarget?): Intent {
      return Intent(context, FeedComposeActivity::class.java).apply {
        if (input != null) putExtra(EXTRA_EDIT, input)
      }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
      return if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_STATE) else null
    }
  }

  companion object {
    private const val EXTRA_STATE = "feed_publish_state"
    private const val EXTRA_EDIT = "feed_edit_target"
  }
}
