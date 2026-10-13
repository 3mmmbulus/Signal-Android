/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 动态的公开主页设置。发帖、评论时引擎说还没建主页，就先开这一页；建好后回去接着发。
 */
class FeedProfileActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val viewModel: FeedProfileViewModel by viewModels {
    viewModelFactory {
      initializer { FeedProfileViewModel(suggestedName = Recipient.self().profileName.toString()) }
    }
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      LaunchedEffect(state.saved) {
        if (state.saved) {
          setResult(Activity.RESULT_OK)
          finish()
        }
      }
      SignalTheme {
        FeedProfileScreen(
          state = state,
          onBack = { finish() },
          onNameChanged = viewModel::onNameChanged,
          onBioChanged = viewModel::onBioChanged,
          onSave = viewModel::save,
          onRetry = viewModel::load,
          onErrorShown = viewModel::onErrorShown
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  /** 结果：true = 主页建好 / 改好了。 */
  class Contract : ActivityResultContract<Unit, Boolean>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(context, FeedProfileActivity::class.java)
    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
  }
}
