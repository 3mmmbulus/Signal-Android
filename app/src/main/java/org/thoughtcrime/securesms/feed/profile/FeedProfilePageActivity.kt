/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.FeedEventSurface
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.report.FeedReportActivity
import org.thoughtcrime.securesms.feed.report.FeedReportTarget
import org.thoughtcrime.securesms.feed.setFeedContent
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 一个人的公开主页。从信息流、详情里点头像或名字进来。
 */
class FeedProfilePageActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val viewModel: FeedProfilePageViewModel by viewModels {
    viewModelFactory {
      initializer { FeedProfilePageViewModel(profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: error("missing profile id")) }
    }
  }

  /** 关注前要先有自己的公开主页：建好回来接着关注。 */
  private val profileLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { saved ->
    if (saved) viewModel.follow()
  }

  /** 自己的主页：改完名字、简介回来刷新。 */
  private val editLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { saved ->
    if (saved) viewModel.refresh()
  }

  /** 举报页里选「只是不想看到」时没有对应的动作（主页不是一条内容），按没举报处理。 */
  private val reportLauncher = registerForActivityResult(FeedReportActivity.Contract()) { }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: error("missing profile id")
    setFeedContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedProfilePageScreen(
          state = state,
          onBack = { finish() },
          onRetry = viewModel::refresh,
          onFollow = viewModel::follow,
          onUnfollow = viewModel::unfollow,
          onSetBlocked = viewModel::setBlocked,
          onReport = { reportLauncher.launch(FeedReportTarget.author(profileId)) },
          onEditProfile = { editLauncher.launch(Unit) },
          onLoadMorePosts = viewModel::loadMorePosts,
          onLikeClick = viewModel::toggleLike,
          onOpenPost = { startActivity(FeedPostDetailActivity.intent(this, it, FeedEventSurface.PROFILE)) },
          onReportPost = { reportLauncher.launch(FeedReportTarget.content(it.id)) },
          onNoticeShown = viewModel::onNoticeShown,
          onProfileRequired = { profileLauncher.launch(Unit) },
          onOwnPostAction = viewModel::onOwnPostAction,
          onInsightsDismissed = viewModel::onInsightsDismissed,
          onSetHidden = viewModel::setHidden,
          onSaveClick = viewModel::toggleSave,
          onRepostClick = viewModel::toggleRepost
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  companion object {
    private const val EXTRA_PROFILE_ID = "feed_profile_id"

    @JvmStatic
    fun intent(context: Context, profileId: String): Intent {
      return Intent(context, FeedProfilePageActivity::class.java).putExtra(EXTRA_PROFILE_ID, profileId)
    }
  }
}
