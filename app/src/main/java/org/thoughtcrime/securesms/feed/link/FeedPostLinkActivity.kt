/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.link

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.TellomiFeed
import org.thoughtcrime.securesms.feed.detail.FeedPostDetailActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.TellomiLinks
import org.signal.core.ui.R as CoreUiR

/**
 * `https://tell.cc/m#<id>` / `tellomi://tell.cc/m#<id>` 的落点：取到卡片就换成帖子详情，取不到说「这条动态已不可见」。
 */
class FeedPostLinkActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val viewModel: FeedPostLinkViewModel by viewModels {
    viewModelFactory {
      initializer {
        FeedPostLinkViewModel(
          contentId = TellomiLinks.parsePostId(intent.dataString),
          feedEnabled = TellomiFeed.isTabEnabled(),
          source = { FeedSources.current() }
        )
      }
    }
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      LaunchedEffect(state) {
        val ready = state as? FeedPostLinkViewModel.State.Ready ?: return@LaunchedEffect
        startActivity(FeedPostDetailActivity.intent(this@FeedPostLinkActivity, ready.card))
        finish()
      }
      SignalTheme {
        FeedPostLinkScreen(state = state, onBack = { finish() }, onRetry = viewModel::load)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }
}

object FeedPostLinkTestTags {
  const val GONE = "feed_post_link_gone"
  const val RETRY = "feed_post_link_retry"
}

@Composable
fun FeedPostLinkScreen(state: FeedPostLinkViewModel.State, onBack: () -> Unit, onRetry: () -> Unit) {
  Scaffolds.Settings(
    title = "",
    onNavigationClick = onBack,
    navigationIcon = ImageVector.vectorResource(CoreUiR.drawable.symbol_arrow_start_24),
    navigationContentDescription = stringResource(R.string.TellomiFeedMine__back)
  ) { padding ->
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
      when (state) {
        FeedPostLinkViewModel.State.Gone -> Text(
          text = stringResource(R.string.TellomiFeedDetail__gone),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag(FeedPostLinkTestTags.GONE)
        )
        FeedPostLinkViewModel.State.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = stringResource(R.string.TellomiFeed__load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
          Buttons.MediumTonal(onClick = onRetry, modifier = Modifier.testTag(FeedPostLinkTestTags.RETRY)) {
            Text(stringResource(R.string.TellomiFeed__retry))
          }
        }
        else -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
      }
    }
  }
}
