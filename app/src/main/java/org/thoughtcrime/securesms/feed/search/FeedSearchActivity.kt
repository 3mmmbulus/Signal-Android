/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.FeedEventSurface
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.feed.report.FeedReportActivity
import org.thoughtcrime.securesms.feed.report.FeedReportTarget
import org.thoughtcrime.securesms.feed.setFeedContent
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 动态搜索。从动态页顶栏的放大镜进（在铃铛左边）。 */
class FeedSearchActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()
  private val viewModel: FeedSearchViewModel by viewModels()

  /** 搜索结果里的帖子也要能举报（能看到内容就必须有举报入口）。 */
  private val reportLauncher = registerForActivityResult(FeedReportActivity.Contract()) { }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setFeedContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedSearchScreen(
          state = state,
          onQueryChange = viewModel::onQueryChange,
          onSubmit = viewModel::submit,
          onCancel = { finish() },
          onSelectScope = viewModel::selectScope,
          onRecentQuery = viewModel::useRecentQuery,
          onRemoveRecentQuery = viewModel::removeRecentQuery,
          onClearRecentQueries = viewModel::clearRecentQueries,
          onClearRecentPeople = viewModel::clearRecentPeople,
          onOpenPerson = { person ->
            viewModel.onPersonOpened(person)
            startActivity(FeedProfilePageActivity.intent(this, person.id))
          },
          onOpenPost = { card ->
            viewModel.onPostOpened()
            startActivity(FeedPostDetailActivity.intent(this, card, FeedEventSurface.SEARCH))
          },
          onLikeClick = viewModel::toggleLike,
          onLoadMore = viewModel::loadMore,
          onRetry = viewModel::retry,
          onSaveClick = viewModel::toggleSave,
          onRepostClick = viewModel::toggleRepost,
          onReportClick = { reportLauncher.launch(FeedReportTarget.content(it.id)) },
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
    fun intent(context: Context): Intent = Intent(context, FeedSearchActivity::class.java)
  }
}
