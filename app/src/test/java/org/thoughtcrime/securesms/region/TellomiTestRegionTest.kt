/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * tellomi/tellomi#1055 第三刀：测试区（只在 debug 包里）。CN 必须关着、`tellomi.cn` 下不能有 DNS 记录，
 * 所以验切区要一个「开着的假 CN」。
 */
class TellomiTestRegionTest {

  private val global = TellomiRegions.GLOBAL

  @Test
  fun `the test region is off unless a plausible domain is set`() {
    assertThat(TellomiRegions.testRegionProfiles(null)).isEqualTo(TellomiRegions.ALL)
    for (notADomain in listOf("", "localhost", ".tellomi.test", "tellomi.test.", "tellomi..test", "tellomi.test/x", "tel lomi.test", "tellomi.测试")) {
      assertThat(TellomiRegions.testRegionProfiles(notADomain), name = notADomain).isEqualTo(TellomiRegions.ALL)
    }
  }

  @Test
  fun `the real cn domain can not be the test region`() {
    // 测试区是「开着的假 CN」：域名填成 tellomi.cn 本身（或它的子域）就等于在 debug 包里把真 CN 打开，
    // 而 CN 关着的时候 tellomi.cn 下一个请求都不许发（契约第四节，App 备案）
    for (cn in listOf("tellomi.cn", "TELLOMI.CN", "Tellomi.Cn", "chat.tellomi.cn", "a.b.tellomi.cn")) {
      assertThat(TellomiRegions.testRegionProfiles(cn), name = cn).isEqualTo(TellomiRegions.ALL)
    }
    // 只是长得像的别的域不受影响
    assertThat(TellomiRegions.testRegionProfiles("nottellomi.cn")[1].enabled).isTrue()
    assertThat(TellomiRegions.testRegionProfiles("tellomi.cn.test")[1].enabled).isTrue()
  }

  @Test
  fun `the test region moves every cn host under the given domain and is enabled`() {
    val profiles = TellomiRegions.testRegionProfiles("tellomi.test")
    assertThat(profiles.map { it.id }).isEqualTo(listOf(TellomiRegionId.GLOBAL, TellomiRegionId.CN))
    assertThat(profiles[0]).isEqualTo(global)

    val testRegion = profiles[1]
    assertThat(testRegion.enabled).isTrue()
    // 同名标签，路径、端口不变：整张核
    assertThat(testRegion.endpoints()).isEqualTo(global.endpoints().map { it.replace(".tellomi.app", ".tellomi.test") })
    assertThat(testRegion.grpcChatHost).isEqualTo("grpc.chat.tellomi.test")
    assertThat(testRegion.contentProxyPort).isEqualTo(global.contentProxyPort)

    // 包里的表不受影响：CN 仍在 tellomi.cn 下，不变量照旧
    assertThat(TellomiRegions.CN.chat).isEqualTo("https://chat.tellomi.cn")
    assertThat(TellomiRegions.problems(TellomiRegions.ALL)).isEmpty()
  }

  @Test
  fun `only debug builds read the test region domain`() {
    var reads = 0
    val domain = {
      reads++
      "tellomi.test"
    }

    assertThat(TellomiRegions.profiles(isDebug = false, testRegionDomain = domain)).isEqualTo(TellomiRegions.ALL)
    assertThat(reads).isEqualTo(0)

    assertThat(TellomiRegions.profiles(isDebug = true, testRegionDomain = domain)[1].enabled).isTrue()
    assertThat(reads).isEqualTo(1)
  }

  @Test
  fun `a stored test region falls back to global once the domain is cleared`() {
    val profiles = TellomiRegions.testRegionProfiles("tellomi.test")
    assertThat(TellomiRegions.resolve("cn", profiles)).isEqualTo(profiles[1])

    // 清掉域名（或 release 包）之后，记住的 cn 回到包里的正式 CN（tellomi.cn），不再指向测试域名
    assertThat(TellomiRegions.resolve("cn", TellomiRegions.testRegionProfiles(null))).isEqualTo(TellomiRegions.CN)
  }
}
