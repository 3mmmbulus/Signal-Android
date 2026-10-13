/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.notifications

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.mine.FeedMyPostsActivity
import org.thoughtcrime.securesms.feed.net.FeedNotification
import org.thoughtcrime.securesms.feed.people.FeedPeopleActivity
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 动态通知。现在从动态页顶栏的铃铛进（附录 10「动态页顶部」：右边搜索 + 通知）。 */
class FeedNotificationsActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedNotificationsViewModel by viewModels()

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      LaunchedEffect(state.openCard) {
        val card = state.openCard ?: return@LaunchedEffect
        viewModel.onCardOpened()
        startActivity(FeedPostDetailActivity.intent(this@FeedNotificationsActivity, card))
      }
      SignalTheme {
        FeedNotificationsScreen(
          state = state,
          onBack = { finish() },
          onSelectTab = viewModel::selectTab,
          onRetry = viewModel::refresh,
          onLoadMore = viewModel::loadMore,
          onOpen = ::open,
          onDelete = viewModel::delete,
          onMute = viewModel::mute,
          onNoticeShown = viewModel::onNoticeShown
        )
      }
    }
  }

  private fun open(row: FeedNotificationRow) {
    when (row.kind) {
      FeedNotification.KIND_LIKE, FeedNotification.KIND_COMMENT, FeedNotification.KIND_REPLY -> row.contentId?.let(viewModel::openPost)
      FeedNotification.KIND_FOLLOW -> row.actorIds.firstOrNull()?.let { startActivity(FeedProfilePageActivity.intent(this, it)) }
      FeedNotification.KIND_FOLLOW_REQUEST -> startActivity(FeedPeopleActivity.intent(this, FeedPeopleMode.FOLLOW_REQUESTS))
      FeedNotification.KIND_SYSTEM -> if (row.systemType == FeedNotification.SYSTEM_CLOSE_PERSON_REQUEST) {
        row.profileId?.let { startActivity(FeedProfilePageActivity.intent(this, it)) }
      } else {
        // 审核结果、申诉都在「我的动态」里看原因、改了再提交
        startActivity(FeedMyPostsActivity.intent(this))
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  companion object {
    @JvmStatic
    fun intent(context: Context): Intent = Intent(context, FeedNotificationsActivity::class.java)
  }
}
