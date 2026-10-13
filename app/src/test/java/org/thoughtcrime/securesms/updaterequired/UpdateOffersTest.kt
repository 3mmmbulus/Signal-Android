/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

/**
 * Tellomi（tellomi/tellomi#1491 第二刀）：推荐档、可选档在哪里露面（需求 app-update-and-version-policy.md §3.4、判据 3 / 4）。
 */
class UpdateOffersTest {

  companion object {
    private const val NOW = 1_760_000_000_000L
    private const val DAY = 24L * 60 * 60 * 1000
  }

  private fun manifest(recommended: Long = 0, required: Long = 0, requiredFrom: Long? = null) = UpdateManifest(
    serial = 1,
    latest = UpdateManifest.Latest(version = "0.1.5", build = 175401, url = null, size = 120_000_000),
    recommendedMinBuild = recommended,
    requiredMinBuild = required,
    requiredReason = if (required > 0) UpdateManifest.RequiredReason.SECURITY else null,
    requiredFromMillis = requiredFrom,
    notesUrl = "https://www.tellomi.app/changelog/#android"
  )

  @Test
  fun `a newer version is an optional offer, a recommended threshold makes it recommended`() {
    val optional = UpdateOffers.offer(manifest(), build = 175301, nowMillis = NOW, readOnly = false)
    assertThat(optional).isEqualTo(
      UpdateOffers.Offer(UpdateManifest.Tier.OPTIONAL, version = "0.1.5", build = 175401, sizeBytes = 120_000_000, notesUrl = "https://www.tellomi.app/changelog/#android")
    )

    assertThat(UpdateOffers.offer(manifest(recommended = 175401), 175301, NOW, readOnly = false)?.tier).isEqualTo(UpdateManifest.Tier.RECOMMENDED)
    // 必须档还没到生效时间：先当推荐（需求 3.1「先推荐、默认 7 天后转必须」）
    assertThat(UpdateOffers.offer(manifest(required = 175401, requiredFrom = NOW + DAY), 175301, NOW, readOnly = false)?.tier).isEqualTo(UpdateManifest.Tier.RECOMMENDED)
  }

  @Test
  fun `no offer when up to date, when the update is required, or while read-only`() {
    assertThat(UpdateOffers.offer(manifest(), build = 175401, nowMillis = NOW, readOnly = false)).isNull()
    assertThat(UpdateOffers.offer(null, build = 175301, nowMillis = NOW, readOnly = false)).isNull()
    // 必须档说话的是阻断页，不出推荐卡片
    assertThat(UpdateOffers.offer(manifest(required = 175401), build = 175301, nowMillis = NOW, readOnly = false)).isNull()
    assertThat(UpdateOffers.offer(manifest(recommended = 175401), build = 175301, nowMillis = NOW, readOnly = true)).isNull()
  }

  @Test
  fun `the recommended card is shown once per version`() {
    val offer = UpdateOffers.offer(manifest(recommended = 175401), 175301, NOW, readOnly = false)

    assertThat(UpdateOffers.shouldShowRecommendedSheet(offer, shownForBuild = 0)).isTrue()
    assertThat(UpdateOffers.shouldShowRecommendedSheet(offer, shownForBuild = 175401)).isFalse()
    // 再出一个新版本，又弹一次
    assertThat(UpdateOffers.shouldShowRecommendedSheet(offer, shownForBuild = 175301)).isTrue()
    // 可选档不弹
    assertThat(UpdateOffers.shouldShowRecommendedSheet(UpdateOffers.offer(manifest(), 175301, NOW, readOnly = false), shownForBuild = 0)).isFalse()
  }

  @Test
  fun `the banner comes back three days after it was dismissed`() {
    val offer = UpdateOffers.offer(manifest(recommended = 175401), 175301, NOW, readOnly = false)

    assertThat(UpdateOffers.shouldShowBanner(offer, dismissedAtMillis = 0, nowMillis = NOW)).isTrue()
    assertThat(UpdateOffers.shouldShowBanner(offer, dismissedAtMillis = NOW - 3 * DAY + 1, nowMillis = NOW)).isFalse()
    assertThat(UpdateOffers.shouldShowBanner(offer, dismissedAtMillis = NOW - 3 * DAY, nowMillis = NOW)).isTrue()
    // 时钟往回拨过（关掉的时间在「未来」）：照常出现，免得一直等
    assertThat(UpdateOffers.shouldShowBanner(offer, dismissedAtMillis = NOW + DAY, nowMillis = NOW)).isTrue()
    // 可选档没有横幅（需求判据 4：只在「关于」页出现）
    assertThat(UpdateOffers.shouldShowBanner(UpdateOffers.offer(manifest(), 175301, NOW, readOnly = false), dismissedAtMillis = 0, nowMillis = NOW)).isFalse()
  }

  @Test
  fun `the settings dot stays until this version was seen in About`() {
    val offer = UpdateOffers.offer(manifest(), 175301, NOW, readOnly = false)

    assertThat(UpdateOffers.shouldShowSettingsDot(offer, seenBuild = 0)).isTrue()
    assertThat(UpdateOffers.shouldShowSettingsDot(offer, seenBuild = 175401)).isFalse()
    assertThat(UpdateOffers.shouldShowSettingsDot(null, seenBuild = 0)).isFalse()
  }
}
