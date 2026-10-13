/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.PassphraseRequiredActivity
import org.thoughtcrime.securesms.feed.net.FeedReportRequest
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme

/**
 * 举报。结果告诉调用方：举报发出去了，还是用户选了「只是不想看到」（调用方按不感兴趣处理）。
 */
class FeedReportActivity : PassphraseRequiredActivity() {

  private val theme = DynamicNoActionBarTheme()

  private val target: FeedReportTarget by lazy { requireNotNull(IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, FeedReportTarget::class.java)) }

  private val viewModel: FeedReportViewModel by viewModels {
    viewModelFactory { initializer { FeedReportViewModel(target) } }
  }

  override fun onPreCreate() {
    theme.onCreate(this)
  }

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
    setContent {
      val state by viewModel.state.collectAsStateWithLifecycle()
      LaunchedEffect(state.sent) {
        if (state.sent) finishWith(Result.SENT)
      }
      SignalTheme {
        FeedReportScreen(
          state = state,
          canHideInstead = target.type == FeedReportRequest.TARGET_CONTENT,
          onBack = { finish() },
          onNotInterested = { finishWith(Result.NOT_INTERESTED) },
          onPick = viewModel::pick,
          onBackToReasons = viewModel::backToReasons,
          onDetailChanged = viewModel::onDetailChanged,
          onRelationshipChanged = viewModel::onRelationshipChanged,
          onEvidenceAdded = viewModel::addEvidence,
          onEvidenceRemoved = viewModel::removeEvidence,
          onSubmit = viewModel::submit,
          onOpenHotline = { CommunicationActions.openBrowserLink(this, HOTLINE_URL) },
          onErrorShown = viewModel::onErrorShown
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  private fun finishWith(result: Result) {
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, result.name))
    finish()
  }

  enum class Result {
    SENT,
    NOT_INTERESTED
  }

  class Contract : ActivityResultContract<FeedReportTarget, Result?>() {
    override fun createIntent(context: Context, input: FeedReportTarget): Intent {
      return Intent(context, FeedReportActivity::class.java).putExtra(EXTRA_TARGET, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result? {
      if (resultCode != Activity.RESULT_OK) return null
      return intent?.getStringExtra(EXTRA_RESULT)?.let { name -> Result.entries.firstOrNull { it.name == name } }
    }
  }

  companion object {
    private const val EXTRA_TARGET = "feed_report_target"
    private const val EXTRA_RESULT = "feed_report_result"

    /** 违法和不良信息举报中心。 */
    const val HOTLINE_URL = "https://www.12377.cn/"
  }
}
