/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.closepeople

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.feed.setFeedContent
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 「关心的人」（第三节第 42 条）。从「编辑公开主页」进，也从「对方设你为关心的人」的通知进。 */
class FeedClosePeopleActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedClosePeopleViewModel by viewModels()

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setFeedContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedClosePeopleScreen(
          state = state,
          onBack = { finish() },
          onRetry = viewModel::refresh,
          onQueryChanged = viewModel::onQueryChanged,
          onAdd = viewModel::add,
          onRemove = viewModel::remove,
          onAnswer = viewModel::answer,
          onOpenProfile = { startActivity(FeedProfilePageActivity.intent(this, it)) },
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
    fun intent(context: Context): Intent = Intent(context, FeedClosePeopleActivity::class.java)
  }
}
