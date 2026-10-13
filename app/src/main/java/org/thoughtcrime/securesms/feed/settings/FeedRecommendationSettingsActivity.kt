/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 「动态设置」（第三节第 12、28、47 条）。从「我的动态」右上角菜单进。 */
class FeedRecommendationSettingsActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedRecommendationSettingsViewModel by viewModels()

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedRecommendationSettingsScreen(
          state = state,
          onBack = { finish() },
          onRetry = viewModel::load,
          onPersonalizedChanged = viewModel::setPersonalized,
          onDeleteInterest = viewModel::deleteInterest,
          onReset = viewModel::reset,
          onNoticeShown = viewModel::onNoticeShown
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  companion object {
    @JvmStatic
    fun intent(context: Context): Intent = Intent(context, FeedRecommendationSettingsActivity::class.java)
  }
}
