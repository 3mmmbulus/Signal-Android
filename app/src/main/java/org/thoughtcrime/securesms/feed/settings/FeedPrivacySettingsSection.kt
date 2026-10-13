/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.settings

import android.content.Context
import android.text.format.DateUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.parseInstantMillis
import org.thoughtcrime.securesms.feed.people.FeedPeopleActivity
import org.thoughtcrime.securesms.feed.people.FeedPeopleMode

/**
 * 插进「设置 → 隐私」的动态一段（第三节第 29、30 条）：私密账号、防骚扰模式、不看 / 不让看的名单。
 * 隐私页本身不认识动态，只在动态入口开着时调这里。
 */
fun DSLConfiguration.feedPrivacySettingsPrefs(context: Context, state: FeedPrivacySettingsState, viewModel: FeedPrivacySettingsViewModel) {
  dividerPref()
  sectionHeaderPref(R.string.TellomiFeedPrivacy__header)

  val profile = state.profile
  when {
    profile == null && state.loadFailed -> clickPref(
      title = DSLSettingsText.from(R.string.TellomiFeedPrivacy__load_failed),
      summary = DSLSettingsText.from(R.string.TellomiFeedNotificationSettings__retry),
      onClick = viewModel::load
    )
    profile == null && state.loaded -> textPref(summary = DSLSettingsText.from(R.string.TellomiFeedPrivacy__no_profile))
    profile == null -> textPref(summary = DSLSettingsText.from(R.string.TellomiFeedNotificationSettings__loading))
    else -> {
      switchPref(
        title = DSLSettingsText.from(R.string.TellomiFeedPrivacy__private),
        summary = DSLSettingsText.from(R.string.TellomiFeedPrivacy__private_summary),
        isEnabled = !state.saving,
        isChecked = profile.isPrivate,
        onClick = { confirmPrivate(context, !profile.isPrivate, viewModel) }
      )

      val days = FeedMyProfile.GUARD_DAYS
      val until = profile.guardUntil?.let { parseInstantMillis(it) }
      radioListPref(
        title = DSLSettingsText.from(
          if (until != null) {
            context.getString(R.string.TellomiFeedPrivacy__guard_on, DateUtils.formatDateTime(context, until, DateUtils.FORMAT_SHOW_DATE))
          } else {
            context.getString(R.string.TellomiFeedPrivacy__guard)
          }
        ),
        dialogTitle = DSLSettingsText.from(R.string.TellomiFeedPrivacy__guard),
        isEnabled = !state.saving,
        listItems = days.map { if (it == 0) context.getString(R.string.TellomiFeedPrivacy__guard_off) else context.resources.getQuantityString(R.plurals.TellomiFeedKeywords__days, it, it) }.toTypedArray(),
        // 开着的时候不知道当初选的是几天，选中项留空；再选一次就是从现在起重新算
        selected = if (until == null) 0 else -1,
        onSelected = { viewModel.setGuardDays(days[it]) }
      )
      textPref(summary = DSLSettingsText.from(R.string.TellomiFeedPrivacy__guard_summary))

      // 不看 / 不让看（ADR-0071「设置：隐私与通知」）；快拍那一份名单还没有
      clickPref(
        title = DSLSettingsText.from(R.string.TellomiFeedPeople__muted_title),
        onClick = { context.startActivity(FeedPeopleActivity.intent(context, FeedPeopleMode.MUTED)) }
      )
      clickPref(
        title = DSLSettingsText.from(R.string.TellomiFeedPeople__hidden_from_title),
        onClick = { context.startActivity(FeedPeopleActivity.intent(context, FeedPeopleMode.HIDDEN_FROM)) }
      )
    }
  }
}

private fun confirmPrivate(context: Context, toPrivate: Boolean, viewModel: FeedPrivacySettingsViewModel) {
  MaterialAlertDialogBuilder(context)
    .setTitle(if (toPrivate) R.string.TellomiFeedPrivacy__to_private_title else R.string.TellomiFeedPrivacy__to_public_title)
    .setMessage(if (toPrivate) R.string.TellomiFeedPrivacy__to_private_body else R.string.TellomiFeedPrivacy__to_public_body)
    .setPositiveButton(if (toPrivate) R.string.TellomiFeedPrivacy__to_private_confirm else R.string.TellomiFeedPrivacy__to_public_confirm) { _, _ -> viewModel.setPrivate(toPrivate) }
    .setNegativeButton(android.R.string.cancel, null)
    .show()
}
