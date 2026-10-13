/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.feed.net.FeedReportRequest

/** 举报的对象：一条内容，或一个作者（公开主页 id）。 */
@Parcelize
data class FeedReportTarget(val type: String, val id: String) : Parcelable {
  companion object {
    fun content(id: String) = FeedReportTarget(FeedReportRequest.TARGET_CONTENT, id)
    fun author(profileId: String) = FeedReportTarget(FeedReportRequest.TARGET_AUTHOR, profileId)
  }
}
