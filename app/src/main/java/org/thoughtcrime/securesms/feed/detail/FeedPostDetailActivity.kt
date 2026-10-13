/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.compose.FeedComposeActivity
import org.thoughtcrime.securesms.feed.net.FeedCard
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.profile.FeedProfileActivity
import org.thoughtcrime.securesms.feed.profile.FeedProfilePageActivity
import org.thoughtcrime.securesms.feed.report.FeedReportActivity
import org.thoughtcrime.securesms.feed.report.FeedReportTarget
import org.thoughtcrime.securesms.feed.setFeedContent
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

  /** 举报页里选了「只是不想看到」就按不感兴趣处理（第三节第 32 条先分流）。 */
  private val reportLauncher = registerForActivityResult(FeedReportActivity.Contract()) { result ->
    if (result == FeedReportActivity.Result.NOT_INTERESTED) viewModel.notInterested()
  }

  /** 编辑自己的帖子，保存回来刷新这条。 */
  private val editLauncher = registerForActivityResult(FeedComposeActivity.Contract()) { publishState ->
    if (publishState != null) viewModel.onEdited(publishState)
  }

  /** 建好公开主页回来就把刚才那条评论发出去（草稿还在）。 */
  private val profileLauncher = registerForActivityResult(FeedProfileActivity.Contract()) { saved ->
    if (saved) viewModel.send()
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setFeedContent {
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
          onAuthorClick = { startActivity(FeedProfilePageActivity.intent(this, it.profileId)) },
          onVote = viewModel::vote,
          onApproveComment = viewModel::approveComment,
          onDeclineComment = viewModel::declineComment,
          onOwnPostAction = viewModel::onOwnPostAction,
          onDeletePost = viewModel::deletePost,
          onReportPost = { reportLauncher.launch(FeedReportTarget.content(state.post.id)) },
          onInsightsDismissed = viewModel::onInsightsDismissed,
          onSaveClick = viewModel::toggleSave,
          onRepostClick = viewModel::toggleRepost,
          onNotInterested = viewModel::notInterested,
          onMuteAuthor = viewModel::muteAuthor,
          onBlockAuthor = viewModel::blockAuthor,
          onEditPost = { editLauncher.launch(it) },
          onLeave = { leave ->
            // 页面马上关，提示用 Toast 带回上一页
            val message = when (leave) {
              FeedPostDetailState.Leave.NOT_INTERESTED -> R.string.TellomiFeedDetail__not_interested_done
              FeedPostDetailState.Leave.MUTED -> R.string.TellomiFeed__muted_done
              FeedPostDetailState.Leave.BLOCKED -> R.string.TellomiFeedPage__blocked_done
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
          }
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
