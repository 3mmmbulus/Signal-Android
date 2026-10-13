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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 动态的发布页（`dynamic-feed.md` 第三节第 22、23、39 条）。全屏，从动态页右下角的发布按钮进来。
 *
 * 发出去之后带着服务端给的 `state` 关掉，动态页据此提示「已发布」或「审核中」。
 */
class FeedComposeActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedComposeViewModel by viewModels()

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
          onFailureShown = viewModel::onFailureShown
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  /** 结果是服务端给的 `state`；用户放弃时是 null。 */
  class Contract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
      return Intent(context, FeedComposeActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
      return if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_STATE) else null
    }
  }

  companion object {
    private const val EXTRA_STATE = "feed_publish_state"
  }
}
