/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import assertk.assertThat
import assertk.assertions.any
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test
import org.thoughtcrime.securesms.BuildConfig

/**
 * tellomi/tellomi#1055：区域表与 RegionProfile 契约 v2（超级仓库 docs/signal/REGION_PROFILE.md）的不变量。
 */
class TellomiRegionsTest {

  private val global = TellomiRegions.GLOBAL
  private val cn = TellomiRegions.CN

  @Test
  fun `global region is exactly the contract table`() {
    assertThat(global.enabled).isTrue()
    assertThat(global.chat).isEqualTo("https://chat.tellomi.app")
    assertThat(global.grpcChatHost).isEqualTo("grpc.chat.tellomi.app")
    assertThat(global.storage).isEqualTo("https://storage.tellomi.app")
    assertThat(global.cdn0).isEqualTo("https://cdn.tellomi.app")
    assertThat(global.cdn2).isEqualTo("https://cdn2.tellomi.app")
    assertThat(global.cdn3).isEqualTo("https://cdn3.tellomi.app")
    assertThat(global.updatesHost).isEqualTo("updates.tellomi.app")
    assertThat(global.badgeStaticRoot).isEqualTo("https://updates.tellomi.app/static/badges/")
    assertThat(global.contentProxyHost).isEqualTo("contentproxy.tellomi.app")
    assertThat(global.contentProxyPort).isEqualTo(443)
    assertThat(global.captchaRegistration).isEqualTo("https://chat.tellomi.app/captcha-tellomi/registration/generate.html")
    assertThat(global.captchaChallenge).isEqualTo("https://chat.tellomi.app/captcha-tellomi/challenge/generate.html")
    assertThat(global.sfu).isEqualTo("https://chat.tellomi.app/callingService")
    assertThat(global.uptimeHost).isEqualTo("uptime.tellomi.app")
    assertThat(global.debugLog).isEqualTo("https://chat.tellomi.app/debuglogs")
    assertThat(global.feed).isEqualTo("https://feed.tellomi.app")
  }

  @Test
  fun `global region is the build constants byte for byte`() {
    assertThat(global.chat).isEqualTo(BuildConfig.SIGNAL_URL)
    assertThat(global.grpcChatHost).isEqualTo(BuildConfig.LIBSIGNAL_CUSTOM_SERVER_HOST)
    assertThat(global.cdn3).isEqualTo(BuildConfig.SIGNAL_CDN3_URL)
    assertThat(global.sfu).isEqualTo(BuildConfig.SIGNAL_SFU_URL)
    // 只有 website 档有 APK 更新清单，其余档是 null
    assertThat(global.apkUpdateManifestUrl).isEqualTo(BuildConfig.APK_UPDATE_MANIFEST_URL)
  }

  @Test
  fun `cn region only swaps the host into tellomi cn`() {
    assertThat(cn.enabled).isFalse()
    assertThat(cn.chat).isEqualTo("https://chat.tellomi.cn")
    assertThat(cn.grpcChatHost).isEqualTo("grpc.chat.tellomi.cn")
    assertThat(cn.captchaRegistration).isEqualTo("https://chat.tellomi.cn/captcha-tellomi/registration/generate.html")
    assertThat(cn.badgeStaticRoot).isEqualTo("https://updates.tellomi.cn/static/badges/")
    assertThat(cn.sfu).isEqualTo("https://chat.tellomi.cn/callingService")
    assertThat(cn.contentProxyHost).isEqualTo("contentproxy.tellomi.cn")
    assertThat(cn.contentProxyPort).isEqualTo(443)
    assertThat(cn.debugLog).isEqualTo("https://chat.tellomi.cn/debuglogs")
    assertThat(cn.feed).isEqualTo("https://feed.tellomi.cn")

    // App 备案要填运行时连接的全部域名：CN 档一条都不能留在 tellomi.app
    assertThat(cn.endpoints().none { it.contains("tellomi.app") }).isTrue()
    assertThat(cn.endpoints().size).isEqualTo(global.endpoints().size)
  }

  @Test
  fun `every cn endpoint is the global one with the host moved to tellomi cn`() {
    // 整张核（不只抽查）：今天 global 的端点路径里都不含 tellomi.app，所以整串替换就是正确答案
    assertThat(cn.endpoints()).isEqualTo(global.endpoints().map { it.replace(".tellomi.app", ".tellomi.cn") })
    assertThat(cn.contentProxyPort).isEqualTo(global.contentProxyPort)
    assertThat(cn.apkUpdateManifestUrl == null).isEqualTo(global.apkUpdateManifestUrl == null)
  }

  @Test
  fun `host rewrite keeps scheme port and path and leaves other domains alone`() {
    assertThat(TellomiRegions.toCnHost("https://updates.tellomi.app/android/latest.json")).isEqualTo("https://updates.tellomi.cn/android/latest.json")
    assertThat(TellomiRegions.toCnHost("contentproxy.tellomi.app:443")).isEqualTo("contentproxy.tellomi.cn:443")
    assertThat(TellomiRegions.toCnHost("https://api.stripe.com/v1")).isEqualTo("https://api.stripe.com/v1")
    assertThat(TellomiRegions.toCnHost("https://tellomi.app/download")).isEqualTo("https://tellomi.app/download")
    // 只换主机：端口、路径、query、fragment 里的 tellomi.app 都不动
    assertThat(TellomiRegions.toCnHost("https://chat.tellomi.app:8443/x.tellomi.app/y?q=z.tellomi.app#f.tellomi.app"))
      .isEqualTo("https://chat.tellomi.cn:8443/x.tellomi.app/y?q=z.tellomi.app#f.tellomi.app")

    assertThat(TellomiRegions.hostOf("https://chat.tellomi.app/callingService")).isEqualTo("chat.tellomi.app")
    assertThat(TellomiRegions.hostOf("grpc.chat.tellomi.app")).isEqualTo("grpc.chat.tellomi.app")
  }

  @Test
  fun `current region falls back to global for anything unusable`() {
    assertThat(TellomiRegions.resolve(null)).isEqualTo(global)
    assertThat(TellomiRegions.resolve("global")).isEqualTo(global)
    // CN 关着：就算记住的是 cn，也回落 global
    assertThat(TellomiRegions.resolve("cn")).isEqualTo(global)
    assertThat(TellomiRegions.resolve("mars")).isEqualTo(global)
    assertThat(TellomiRegions.resolve("")).isEqualTo(global)
  }

  @Test
  fun `an enabled cn region is used once it is switched on`() {
    val enabledCn = cn.copy(enabled = true)

    assertThat(TellomiRegions.resolve("cn", listOf(global, enabledCn))).isEqualTo(enabledCn)
    assertThat(TellomiRegions.resolve("global", listOf(global, enabledCn))).isEqualTo(global)
  }

  @Test
  fun `packaged regions satisfy the invariants`() {
    assertThat(TellomiRegions.problems(TellomiRegions.ALL)).isEmpty()
  }

  @Test
  fun `invariant check catches a broken table`() {
    assertThat(TellomiRegions.problems(listOf(global.copy(enabled = false), cn))).any { it.contains("global region must be enabled") }
    assertThat(TellomiRegions.problems(listOf(cn))).any { it.contains("missing the global region") }
    assertThat(TellomiRegions.problems(listOf(global, cn.copy(enabled = true)))).any { it.contains("must stay disabled") }
    assertThat(TellomiRegions.problems(listOf(global, cn.copy(uptimeHost = "uptime.tellomi.app")))).any { it.contains("uptime.tellomi.app") }
    assertThat(TellomiRegions.problems(listOf(global, global))).any { it.contains("duplicate") }
  }
}
