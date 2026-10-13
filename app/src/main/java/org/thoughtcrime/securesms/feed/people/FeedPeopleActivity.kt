/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.people

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.feed.topic.FeedTopicActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 关注请求 / 屏蔽列表（[FeedPeopleMode]）。从「我的动态」右上角进。 */
class FeedPeopleActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  /** 从名单进了话题页：回来时重新拉，那边可能取消了关注。 */
  private var openedTopic = false

  private val mode: FeedPeopleMode by lazy {
    FeedPeopleMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: FeedPeopleMode.FOLLOW_REQUESTS
  }

  private val viewModel: FeedPeopleViewModel by viewModels {
    viewModelFactory { initializer { FeedPeopleViewModel(mode) } }
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedPeopleScreen(
          state = state,
          mode = mode,
          onBack = { finish() },
          onRetry = viewModel::refresh,
          onApprove = { viewModel.answer(it, approve = true) },
          onDecline = { viewModel.answer(it, approve = false) },
          onUnblock = viewModel::unblock,
          onOpenProfile = { startActivity(FeedProfilePageActivity.intent(this, it.id)) },
          onNoticeShown = viewModel::onNoticeShown,
          onRemove = viewModel::remove,
          onOpenTopic = {
            openedTopic = true
            startActivity(FeedTopicActivity.intent(this, it))
          }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    if (openedTopic) {
      openedTopic = false
      viewModel.refresh()
    }
  }

  companion object {
    private const val EXTRA_MODE = "feed_people_mode"

    @JvmStatic
    fun intent(context: Context, mode: FeedPeopleMode): Intent {
      return Intent(context, FeedPeopleActivity::class.java).putExtra(EXTRA_MODE, mode.name)
    }
  }
}
