/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.banner.banners

import android.text.format.DateUtils
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.Banner
import org.thoughtcrime.securesms.banner.ui.compose.DefaultBanner
import org.thoughtcrime.securesms.banner.ui.compose.Importance
import org.thoughtcrime.securesms.restriction.TellomiRestriction
import org.thoughtcrime.securesms.restriction.TellomiRestrictions
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi ADR-0078 §3.4）：账号被人工处置时，聊天列表顶上说明为什么发不出去、到什么时候、去哪申诉。
 * 不能关；截止时间到了、或者服务端说没有了就自己消失（[stateUpdates]）。文案定稿见 ADR-0078 §3.4。
 */
class TellomiRestrictionBanner : Banner<TellomiRestriction>() {

  override val enabled: Boolean
    get() = TellomiRestrictions.active() != null

  override val dataFlow: Flow<TellomiRestriction>
    get() = TellomiRestrictions.current.mapNotNull { it }

  /** 处置变了发一次；有处置时截止时间到了再发一次（让 [enabled] 重新读、横幅消失）。 */
  @OptIn(ExperimentalCoroutinesApi::class)
  override val stateUpdates: Flow<Unit>
    get() = TellomiRestrictions.current.flatMapLatest { restriction ->
      flow {
        emit(Unit)
        if (restriction != null) {
          delay((restriction.untilMillis - System.currentTimeMillis()).coerceAtLeast(0) + 1000)
          TellomiRestrictions.active()
          emit(Unit)
        }
      }
    }

  @Composable
  override fun DisplayBanner(model: TellomiRestriction, contentPadding: PaddingValues) {
    RestrictionBanner(model, contentPadding)
  }
}

@Composable
private fun RestrictionBanner(restriction: TellomiRestriction, contentPadding: PaddingValues) {
  val context = LocalContext.current
  val sameYear = Calendar.getInstance().apply { timeInMillis = restriction.untilMillis }.get(Calendar.YEAR) ==
    Calendar.getInstance().get(Calendar.YEAR)
  val until = DateUtils.formatDateTime(
    context,
    restriction.untilMillis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or
      (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
  )
  val body = when (restriction.level) {
    TellomiRestriction.Level.STRANGER -> stringResource(R.string.TellomiRestriction__stranger, until)
    else -> stringResource(R.string.TellomiRestriction__all, until)
  }
  val appeal = if (restriction.caseId != null) {
    stringResource(R.string.TellomiRestriction__appeal_case, restriction.appeal, restriction.caseId)
  } else {
    stringResource(R.string.TellomiRestriction__appeal, restriction.appeal)
  }
  DefaultBanner(
    title = null,
    body = "$body $appeal",
    importance = Importance.ERROR,
    paddingValues = contentPadding
  )
}

@DayNightPreviews
@Composable
private fun RestrictionBannerPreview() {
  Previews.Preview {
    RestrictionBanner(
      TellomiRestriction(
        level = TellomiRestriction.Level.STRANGER,
        untilMillis = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7),
        appeal = "abuse@tellomi.app",
        caseId = "20261017-abcd1234"
      ),
      PaddingValues(0.dp)
    )
  }
}
