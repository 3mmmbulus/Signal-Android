/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.topic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.FeedEventSurface
import org.thoughtcrime.securesms.feed.FeedListShareEvents
import org.thoughtcrime.securesms.feed.LocalFeedShareListener
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.feed.feedTopicLabel
import org.thoughtcrime.securesms.feed.report.FeedReportActivity
import org.thoughtcrime.securesms.feed.report.FeedReportTarget
import org.thoughtcrime.securesms.feed.setFeedContent
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/** 话题页。从卡片上的 #话题、「关注的话题」名单进来。 */
class FeedTopicActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val slug: String by lazy { intent.getStringExtra(EXTRA_SLUG) ?: error("missing topic slug") }

  private val viewModel: FeedTopicViewModel by viewModels {
    viewModelFactory {
      initializer { FeedTopicViewModel(slug = slug) }
    }
  }

  /** 举报页里选「只是不想看到」时没有对应的动作，按没举报处理。 */
  private val reportLauncher = registerForActivityResult(FeedReportActivity.Contract()) { }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    val givenLabel = intent.getStringExtra(EXTRA_LABEL)
    setFeedContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      SignalTheme {
        CompositionLocalProvider(LocalFeedShareListener provides FeedListShareEvents.listener(FeedEventSurface.SEARCH)) {
          FeedTopicScreen(
            label = givenLabel?.takeIf { it.isNotBlank() } ?: feedTopicLabel(slug),
            state = state,
            onBack = { finish() },
            onRetry = viewModel::refresh,
            onToggleFollow = viewModel::toggleFollow,
            onLoadMore = viewModel::loadMore,
            onLikeClick = viewModel::toggleLike,
            onOpenPost = { startActivity(FeedPostDetailActivity.intent(this, it, FeedEventSurface.SEARCH)) },
            onReportPost = { reportLauncher.launch(FeedReportTarget.content(it.id)) },
            onTopicClick = { if (it.slug != slug) startActivity(intent(this, it.slug, it.label)) },
            onNoticeShown = viewModel::onNoticeShown,
            onSaveClick = viewModel::toggleSave,
            onRepostClick = viewModel::toggleRepost
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  companion object {
    private const val EXTRA_SLUG = "feed_topic_slug"
    private const val EXTRA_LABEL = "feed_topic_label"

    /** [label] 是卡片上已经有的显示名；没有就按 slug 查本地话题表。 */
    @JvmStatic
    fun intent(context: Context, slug: String, label: String? = null): Intent {
      return Intent(context, FeedTopicActivity::class.java).putExtra(EXTRA_SLUG, slug).putExtra(EXTRA_LABEL, label)
    }
  }
}
