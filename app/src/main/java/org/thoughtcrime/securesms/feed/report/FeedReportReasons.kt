/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.report

import androidx.annotation.StringRes
import org.thoughtcrime.securesms.R

/** 举报的一项原因：标题和一句说明（第三节第 32 条「每项一句说明」）。 */
data class FeedReportReason(
  val group: String,
  val code: String,
  @StringRes val label: Int,
  @StringRes val description: Int
) {
  /** 诈骗和「我的权益」下的每一项要截图（和引擎 `needsEvidence` 一致）。 */
  val needsEvidence: Boolean
    get() = code == "fraud" || group == GROUP_MY_RIGHTS

  /** 「我的权益」要写和被侵权人的关系。 */
  val needsRelationship: Boolean
    get() = group == GROUP_MY_RIGHTS

  /** 人身安全相关：先提示报警（第 32 条「紧急类先提示报警」）。 */
  val isEmergency: Boolean
    get() = code in EMERGENCY

  companion object {
    const val GROUP_HARM_OTHERS = "harm_others"
    const val GROUP_ILLEGAL = "illegal"
    const val GROUP_FALSE = "false_or_impersonation"
    const val GROUP_MY_RIGHTS = "my_rights"

    const val MAX_DETAIL_CHARS = 500
    const val MAX_RELATIONSHIP_CHARS = 40
    const val MAX_EVIDENCE = 9

    private val EMERGENCY = setOf("violence_threat", "self_harm", "minor_safety")
  }
}

/** 一组原因（四组：伤害他人 / 违法违规 / 不实或冒充 / 我的权益）。 */
data class FeedReportGroup(val code: String, @StringRes val label: Int, val reasons: List<FeedReportReason>)

object FeedReportReasons {

  private val GROUP_LABELS = linkedMapOf(
    FeedReportReason.GROUP_HARM_OTHERS to R.string.TellomiFeedReport__group_harm_others,
    FeedReportReason.GROUP_ILLEGAL to R.string.TellomiFeedReport__group_illegal,
    FeedReportReason.GROUP_FALSE to R.string.TellomiFeedReport__group_false,
    FeedReportReason.GROUP_MY_RIGHTS to R.string.TellomiFeedReport__group_my_rights
  )

  /** 客户端认识的原因和文案；引擎的原因表（`config.report_reasons`）决定实际显示哪些。 */
  private val KNOWN: Map<String, Pair<Int, Int>> = mapOf(
    "harassment" to (R.string.TellomiFeedReport__harassment to R.string.TellomiFeedReport__harassment_desc),
    "hate" to (R.string.TellomiFeedReport__hate to R.string.TellomiFeedReport__hate_desc),
    "violence_threat" to (R.string.TellomiFeedReport__violence_threat to R.string.TellomiFeedReport__violence_threat_desc),
    "self_harm" to (R.string.TellomiFeedReport__self_harm to R.string.TellomiFeedReport__self_harm_desc),
    "minor_safety" to (R.string.TellomiFeedReport__minor_safety to R.string.TellomiFeedReport__minor_safety_desc),
    "fraud" to (R.string.TellomiFeedReport__fraud to R.string.TellomiFeedReport__fraud_desc),
    "gambling" to (R.string.TellomiFeedReport__gambling to R.string.TellomiFeedReport__gambling_desc),
    "drugs" to (R.string.TellomiFeedReport__drugs to R.string.TellomiFeedReport__drugs_desc),
    "pornography" to (R.string.TellomiFeedReport__pornography to R.string.TellomiFeedReport__pornography_desc),
    "other_illegal" to (R.string.TellomiFeedReport__other_illegal to R.string.TellomiFeedReport__other_illegal_desc),
    "misinformation" to (R.string.TellomiFeedReport__misinformation to R.string.TellomiFeedReport__misinformation_desc),
    "impersonation" to (R.string.TellomiFeedReport__impersonation to R.string.TellomiFeedReport__impersonation_desc),
    "spam" to (R.string.TellomiFeedReport__spam to R.string.TellomiFeedReport__spam_desc),
    "copyright" to (R.string.TellomiFeedReport__copyright to R.string.TellomiFeedReport__copyright_desc),
    "portrait" to (R.string.TellomiFeedReport__portrait to R.string.TellomiFeedReport__portrait_desc),
    "privacy" to (R.string.TellomiFeedReport__privacy to R.string.TellomiFeedReport__privacy_desc),
    "defamation" to (R.string.TellomiFeedReport__defamation to R.string.TellomiFeedReport__defamation_desc)
  )

  /** 引擎没给原因表（老版本、本机样例）时用的，和引擎 `ReportReasons` 一致。 */
  val DEFAULT: Map<String, List<String>> = linkedMapOf(
    FeedReportReason.GROUP_HARM_OTHERS to listOf("harassment", "hate", "violence_threat", "self_harm", "minor_safety"),
    FeedReportReason.GROUP_ILLEGAL to listOf("fraud", "gambling", "drugs", "pornography", "other_illegal"),
    FeedReportReason.GROUP_FALSE to listOf("misinformation", "impersonation", "spam"),
    FeedReportReason.GROUP_MY_RIGHTS to listOf("copyright", "portrait", "privacy", "defamation")
  )

  /**
   * 按引擎给的表排出来：组按固定顺序，组内按引擎的顺序；客户端没有文案的原因（引擎新加的）先不显示，
   * 免得给用户看代码。
   */
  fun groups(fromConfig: Map<String, List<String>>): List<FeedReportGroup> {
    val table = fromConfig.ifEmpty { DEFAULT }
    return GROUP_LABELS.mapNotNull { (group, label) ->
      val reasons = table[group].orEmpty().mapNotNull { code ->
        KNOWN[code]?.let { (title, description) -> FeedReportReason(group, code, title, description) }
      }
      if (reasons.isEmpty()) null else FeedReportGroup(group, label, reasons)
    }
  }
}
