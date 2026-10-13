/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.mine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.compose.FeedComposeActivity
import org.thoughtcrime.securesms.feed.people.FeedPeopleActivity
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode
import org.thoughtcrime.securesms.feed.profile.FeedProfileActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 「我的动态」。现在从动态页顶栏右侧进；一级导航改成「动态 · 聊天 · 通话 · 好友 · 我」（第三节第 17 条）之后挪到「我」里。
 */
class FeedMyPostsActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedMyPostsViewModel by viewModels()

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  private val editLauncher = registerForActivityResult(FeedComposeActivity.Contract()) { publishState ->
    if (publishState != null) viewModel.onEdited(publishState)
  }

  private val profileLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedMyPostsScreen(
          state = state,
          onBack = { finish() },
          onRefresh = viewModel::refresh,
          onLoadMore = viewModel::loadMore,
          onDelete = viewModel::delete,
          onDeleteFailureShown = viewModel::onDeleteFailureShown,
          onEdit = { editLauncher.launch(it) },
          onEditNoticeShown = viewModel::onEditNoticeShown,
          onProfileClick = { profileLauncher.launch(Unit) },
          onFollowRequestsClick = { startActivity(FeedPeopleActivity.intent(this, FeedPeopleMode.FOLLOW_REQUESTS)) },
          onBlockedClick = { startActivity(FeedPeopleActivity.intent(this, FeedPeopleMode.BLOCKED)) },
          onAppeal = viewModel::appeal,
          onAppealNoticeShown = viewModel::onAppealNoticeShown
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
    fun intent(context: Context): Intent = Intent(context, FeedMyPostsActivity::class.java)
  }
}
