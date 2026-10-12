/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.updaterequired

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.signal.core.util.Util

/**
 * Tellomi（tellomi/tellomi#1138）：阻断页的原因文案——构建过期说「版本过旧」，其它（服务端 499 等）说「无法与服务器通信」。
 */
class UpdateRequiredTest {

  private val buildTimestamp = 1_790_000_000_000L

  @Test
  fun `a build past its lifespan is too old`() {
    val reason = UpdateRequired.reason(serverTime = buildTimestamp + Util.getBuildLifespan(), buildTimestamp = buildTimestamp)

    assertThat(reason).isEqualTo(UpdateRequiredState.Reason.BUILD_EXPIRED)
  }

  @Test
  fun `a build within its lifespan was turned away by the server`() {
    val reason = UpdateRequired.reason(serverTime = buildTimestamp + Util.getBuildLifespan() - 1, buildTimestamp = buildTimestamp)

    assertThat(reason).isEqualTo(UpdateRequiredState.Reason.SERVER_REJECTED)
  }

  // ==================== owner 2026-09-24：阻断页的三条规则 ====================

  @Test
  fun `only a version the server turned away gets the blocking page`() {
    assertThat(UpdateRequired.shouldBlock(isRequired = true, reason = UpdateRequiredState.Reason.SERVER_REJECTED, readOnlyChosenVersionCode = 0, currentVersionCode = 175101)).isTrue()

    // 规则 2：本机构建到期只降成只读 + 横幅，不盖全屏。
    assertThat(UpdateRequired.shouldBlock(isRequired = true, reason = UpdateRequiredState.Reason.BUILD_EXPIRED, readOnlyChosenVersionCode = 0, currentVersionCode = 175101)).isFalse()

    assertThat(UpdateRequired.shouldBlock(isRequired = false, reason = UpdateRequiredState.Reason.SERVER_REJECTED, readOnlyChosenVersionCode = 0, currentVersionCode = 175101)).isFalse()
  }

  @Test
  fun `choosing to only view chats stops the blocking page for this version, not the next one`() {
    // 规则 1：选了「只看聊天记录」就不再自动盖；装上新版本（versionCode 变了）这个选择作废。
    assertThat(UpdateRequired.shouldBlock(isRequired = true, reason = UpdateRequiredState.Reason.SERVER_REJECTED, readOnlyChosenVersionCode = 175101, currentVersionCode = 175101)).isFalse()
    assertThat(UpdateRequired.shouldBlock(isRequired = true, reason = UpdateRequiredState.Reason.SERVER_REJECTED, readOnlyChosenVersionCode = 175101, currentVersionCode = 175201)).isTrue()
  }

  // ==================== tellomi/tellomi#1491：更新清单写的原因 ====================

  @Test
  fun `the manifest's reason picks the sentence on the blocking page`() {
    val serverRejected = UpdateRequiredState.Reason.SERVER_REJECTED

    assertThat(UpdateRequired.displayReason(serverRejected, UpdateManifest.RequiredReason.SECURITY)).isEqualTo(UpdateRequiredState.Reason.SECURITY)
    assertThat(UpdateRequired.displayReason(serverRejected, UpdateManifest.RequiredReason.OTHER)).isEqualTo(UpdateRequiredState.Reason.OTHER)
    // 协议和服务端 499 是同一句「此版本已无法与服务器正常通信」
    assertThat(UpdateRequired.displayReason(serverRejected, UpdateManifest.RequiredReason.PROTOCOL)).isEqualTo(serverRejected)
    assertThat(UpdateRequired.displayReason(serverRejected, null)).isEqualTo(serverRejected)
  }

  @Test
  fun `an expired build says too old whatever the manifest says`() {
    assertThat(UpdateRequired.displayReason(UpdateRequiredState.Reason.BUILD_EXPIRED, UpdateManifest.RequiredReason.SECURITY))
      .isEqualTo(UpdateRequiredState.Reason.BUILD_EXPIRED)
  }
}
