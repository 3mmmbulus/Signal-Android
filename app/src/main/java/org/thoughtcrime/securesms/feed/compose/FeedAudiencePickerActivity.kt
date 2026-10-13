/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.FragmentWrapperActivity
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.settings.select.BaseStoryRecipientSelectionFragment

/**
 * 「部分可见 / 不给谁看」选人（第三节第 39 条：选人复用快拍「选定的人」组件）。
 * 就是快拍「选择观看者」那一页，不建分组，选完把 recipient id 交回发布页。
 */
class FeedAudiencePickerActivity : FragmentWrapperActivity(), BaseStoryRecipientSelectionFragment.Callback {

  override fun getFragment(): Fragment {
    return FeedAudiencePickerFragment().apply {
      arguments = bundleOf(ARG_SELECTED to intent.getLongArrayExtra(EXTRA_SELECTED))
    }
  }

  override fun exitFlow() {
    finish()
  }

  fun finishWith(recipients: Set<RecipientId>) {
    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, recipients.map { it.toLong() }.toLongArray()))
    finish()
  }

  /** 输入是已经选了的人（recipient id）；结果是新的名单，取消时是 null。 */
  class Contract : ActivityResultContract<LongArray, LongArray?>() {
    override fun createIntent(context: Context, input: LongArray): Intent {
      return Intent(context, FeedAudiencePickerActivity::class.java).putExtra(EXTRA_SELECTED, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): LongArray? {
      return if (resultCode == Activity.RESULT_OK) intent?.getLongArrayExtra(EXTRA_RESULT) else null
    }
  }

  class FeedAudiencePickerFragment : BaseStoryRecipientSelectionFragment() {
    override val actionButtonLabel: Int = R.string.HideStoryFromFragment__done
    override val distributionListId: DistributionListId? = null
    override val startingSelection: Set<RecipientId>
      get() = requireArguments().getLongArray(ARG_SELECTED)?.map { RecipientId.from(it) }?.toSet().orEmpty()

    override fun goToNextScreen(recipients: Set<RecipientId>) {
      (requireActivity() as FeedAudiencePickerActivity).finishWith(recipients)
    }
  }

  companion object {
    private const val EXTRA_SELECTED = "feed_audience_selected"
    private const val EXTRA_RESULT = "feed_audience_result"
    private const val ARG_SELECTED = "selected"
  }
}
