/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

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
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.profile.FeedProfileActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 帖子详情。从信息流点卡片或评论按钮进来，带着那张卡片（先画出来，再向引擎要最新的）。
 */
class FeedPostDetailActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val viewModel: FeedPostDetailViewModel by viewModels {
    viewModelFactory {
      initializer {
        val json = intent.getStringExtra(EXTRA_CARD) ?: error("missing card")
        FeedPostDetailViewModel(initial = FeedJson.decodeFromString(FeedCard.serializer(), json))
      }
    }
  }

  /** 建好公开主页回来就把刚才那条评论发出去（草稿还在）。 */
  private val profileLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { saved ->
    if (saved) viewModel.send()
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        FeedPostDetailScreen(
          state = state,
          onBack = { finish() },
          onLikeClick = viewModel::toggleLike,
          onRetryComments = viewModel::refreshComments,
          onLoadMoreComments = viewModel::loadMoreComments,
          onReplyClick = viewModel::replyTo,
          onDraftChanged = viewModel::onDraftChanged,
          onSend = viewModel::send,
          onNoticeShown = viewModel::onNoticeShown,
          onProfileRequired = { profileLauncher.launch(Unit) },
          onAuthorClick = { startActivity(FeedProfilePageActivity.intent(this, it.profileId)) }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  companion object {
    private const val EXTRA_CARD = "feed_card"

    @JvmStatic
    fun intent(context: Context, card: FeedCard): Intent {
      return Intent(context, FeedPostDetailActivity::class.java)
        .putExtra(EXTRA_CARD, FeedJson.encodeToString(FeedCard.serializer(), card))
    }
  }
}
