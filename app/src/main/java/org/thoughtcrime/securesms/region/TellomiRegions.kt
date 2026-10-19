/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.region

import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * Tellomi：区域 id（RegionProfile 契约 v2 第二节，超级仓库 `docs/signal/REGION_PROFILE.md`；tellomi/tellomi#1055）。
 * 和 Desktop、ADR-0062 / ADR-0064、policy 引擎的 `Region::{Global, Cn}` 是同一套取值。
 */
enum class TellomiRegionId(val id: String) {
  GLOBAL("global"),
  CN("cn")
}

/**
 * 一个区域 = 一组端点 + 一个 `enabled` 位（契约第一、三节）。
 * 客户端连的每一个服务端地址都属于且只属于一个区域；各字段对应契约第三节表里的一行。
 * URL 字段带 scheme；名字以 `Host` 结尾的只写主机名。
 *
 * 不进区域的：`svr2` / `cdsi`（没有自建，见 `ENCLAVES.md`）、zk 参数、UD 信任根、CA——两个区连的是同一套服务端。
 *
 * 契约第三节有、这里没有字段的：grpcChat 的端口（`BuildConfig.LIBSIGNAL_CUSTOM_SERVER_PORT`），两个区相同，暂不进表。
 */
data class TellomiRegionProfile(
  val id: TellomiRegionId,
  val enabled: Boolean,
  /** REST + WebSocket；captcha 页、`/callingService`、`/debuglogs` 也挂在这台主机上。 */
  val chat: String,
  /** libsignal `Network` 的 custom server，只写主机名。 */
  val grpcChatHost: String,
  val storage: String,
  /** 头像 / 群头像 / 贴纸。 */
  val cdn0: String,
  /** 遗留；客户端配置校验要求它存在。 */
  val cdn2: String,
  /** 附件；每个区恰好一个（契约第五节第 3 条，见 [TellomiServiceConfigurations.requireSingleCdn3]）。 */
  val cdn3: String,
  /** 动态资源、自动更新的主机（`UPDATES2_HOST`）。 */
  val updatesHost: String,
  val badgeStaticRoot: String,
  /** 只有 website 档有（大陆的分发渠道），其余档是 null。 */
  val apkUpdateManifestUrl: String?,
  /** GIF 内容代理。三端都先和代理建 TLS 再 CONNECT。 */
  val contentProxyHost: String,
  val contentProxyPort: Int,
  val captchaRegistration: String,
  val captchaChallenge: String,
  /** 群通话会合点。两个区的入口可以不同，会合点只能有一个（契约第五节第 6 条）。 */
  val sfu: String,
  /** 只做 DNS 解析，不连接（#1101）。 */
  val uptimeHost: String,
  /** 调试日志上传（#931）。 */
  val debugLog: String,
  /**
   * 动态的内容引擎（ADR-0071 §5.3、`docs/adr/0071/client-api-2026-10.md`）。凭证从 [chat] 的 `/v1/tellomi/feed/auth` 取，
   * 列表、卡片、发帖都打这台主机。Desktop 不做动态，没有这一项。
   */
  val feed: String,
  /**
   * 静态 IP（契约第三节 staticIps，Android 独有）：主机名 → IP，系统 DNS 和 1.1.1.1 都失败时的最后一档
   * （`SignalServiceNetworkAccess.DNS` 的 StaticDns，表由 [TellomiRegions.staticIpTable] 合成）。
   * 只有 global 档有；CN 档暂无回落，CN 的主机查不到就是 UnknownHostException，落到系统 DNS 的结果。
   * 每个主机都得是这个区自己的端点（[TellomiRegions.problems] 核）。
   */
  val staticIps: Map<String, Set<String>> = emptyMap()
) {
  /** 这个区所有的端点（URL 或主机名），校验和测试用。 */
  fun endpoints(): List<String> {
    return listOfNotNull(
      chat,
      grpcChatHost,
      storage,
      cdn0,
      cdn2,
      cdn3,
      updatesHost,
      badgeStaticRoot,
      apkUpdateManifestUrl,
      contentProxyHost,
      captchaRegistration,
      captchaChallenge,
      sfu,
      uptimeHost,
      debugLog,
      feed
    )
  }
}

/**
 * 编进包里的区域表。
 *
 * 这一刀只把表立起来、把不变量钉成测试：global 档就是今天的常量（逐字节一致），CN 档按契约第四节生成、`enabled = false`。
 * 选区、切区（`AppDependencies.resetNetwork()`）和各调用点改从当前区取，在 #1055 的后面几刀。
 */
object TellomiRegions {
  private const val GLOBAL_DOMAIN = ".tellomi.app"
  private const val CN_DOMAIN = ".tellomi.cn"

  /** 原来写死在 `SubmitDebugLogRepository` 里，收进这里（契约第三节「写死」那几处之一）。 */
  private const val DEBUG_LOG_URL = "https://chat.tellomi.app/debuglogs"

  /** 内容引擎还没有构建常量，和 [DEBUG_LOG_URL] 一样写在表里。 */
  private const val FEED_URL = "https://feed.tellomi.app"

  /** global 档 = 今天的常量（契约第五节第 1 条）。 */
  @JvmField
  val GLOBAL = TellomiRegionProfile(
    id = TellomiRegionId.GLOBAL,
    enabled = true,
    chat = BuildConfig.SIGNAL_URL,
    grpcChatHost = BuildConfig.LIBSIGNAL_CUSTOM_SERVER_HOST,
    storage = BuildConfig.STORAGE_URL,
    cdn0 = BuildConfig.SIGNAL_CDN_URL,
    cdn2 = BuildConfig.SIGNAL_CDN2_URL,
    cdn3 = BuildConfig.SIGNAL_CDN3_URL,
    updatesHost = BuildConfig.UPDATES2_HOST,
    badgeStaticRoot = BuildConfig.BADGE_STATIC_ROOT,
    apkUpdateManifestUrl = BuildConfig.APK_UPDATE_MANIFEST_URL,
    contentProxyHost = BuildConfig.CONTENT_PROXY_HOST,
    contentProxyPort = BuildConfig.CONTENT_PROXY_PORT,
    captchaRegistration = BuildConfig.SIGNAL_CAPTCHA_URL,
    captchaChallenge = BuildConfig.RECAPTCHA_PROOF_URL,
    sfu = BuildConfig.SIGNAL_SFU_URL,
    uptimeHost = BuildConfig.SIGNAL_SERVICE_STATUS_URL,
    debugLog = DEBUG_LOG_URL,
    feed = FEED_URL,
    staticIps = mapOf(
      hostOf(BuildConfig.SIGNAL_URL) to BuildConfig.SIGNAL_SERVICE_IPS.toSet(),
      hostOf(BuildConfig.STORAGE_URL) to BuildConfig.SIGNAL_STORAGE_IPS.toSet(),
      hostOf(BuildConfig.SIGNAL_CDN_URL) to BuildConfig.SIGNAL_CDN_IPS.toSet(),
      hostOf(BuildConfig.SIGNAL_CDN2_URL) to BuildConfig.SIGNAL_CDN2_IPS.toSet(),
      // cdn3 的 IP 表**故意是空的**（#1077）：cdn3.tellomi.app 在 Cloudflare 后面，
      // 边缘 IP 会变，写死等于给自己做一张会过期的劫持表——DNS 正常时根本用不到，
      // DNS 失效时反而把流量送到一个可能早已不属于我们的地址。
      // 空集在 StaticDns 里和"没有这个 key"是同一个结果（UnknownHostException），
      // 留着这一行是为了让下一个人看见这是**决定**，不是漏填。
      hostOf(BuildConfig.SIGNAL_CDN3_URL) to BuildConfig.SIGNAL_CDN3_IPS.toSet(),
      // Tellomi（#1077）：上游这里还有 `sfu.voip.signal.org`。我们把 SFU 并进了 chat.tellomi.app，
      // 它要解析的主机就是第一行已经覆盖的 chat.tellomi.app，所以没有单独一行。
      BuildConfig.CONTENT_PROXY_HOST to BuildConfig.SIGNAL_CONTENT_PROXY_IPS.toSet()
    )
  )

  /**
   * CN 档：2026-10-19 起编进包里可用（tellomi.cn 已备案）。**默认仍是 global**：只有远程配置
   * `global.tellomi.region.target = cn` 且探测通过才切过去（[TellomiRegionAutoSwitch]，REGION_PROFILE 第六节之二）。
   */
  @JvmField
  val CN = cnOf(GLOBAL, enabled = true)

  @JvmField
  val ALL: List<TellomiRegionProfile> = listOf(GLOBAL, CN)

  init {
    // 契约第五节第 8 条：包内配置坏了就拒绝启动（和 Desktop 启动时抛同一语义）。
    // 单测只覆盖跑过的变体，而发版只打 website 档、不跑单测（它多一个 APK 清单端点），所以类加载时再核一次。
    val broken = problems(ALL)
    check(broken.isEmpty()) { "packaged region table is broken: $broken" }
  }

  /**
   * 当前区。各调用点在用的时候取，不在类加载时存下来，这样切区（`AppDependencies.resetNetwork()`）之后新建的连接就用新区。
   * 现在 CN 关着，所以恒为 global（debug 包开了测试区时除外）。
   */
  @JvmStatic
  fun current(): TellomiRegionProfile {
    val storedId: String? = try {
      SignalStore.tellomiRegion.currentId
    } catch (e: Exception) {
      // SignalStore 还没初始化（进程刚起的早期路径、单测）或者读库失败：回落 global，绝不抛（契约第五节第 8 条）
      null
    }
    return resolve(storedId, profiles())
  }

  /**
   * 本进程用的区域表：一般就是 [ALL]。debug 包在 Internal 设置里填了测试区域名时，CN 档换成开着的测试区（[testRegionProfiles]）。
   * release 包里 `BuildConfig.DEBUG` 是编译期常量 false，后面整段被 R8 删掉。
   */
  @JvmStatic
  @JvmOverloads
  fun profiles(isDebug: Boolean = BuildConfig.DEBUG, testRegionDomain: () -> String? = ::storedTestRegionDomain): List<TellomiRegionProfile> {
    return if (isDebug) testRegionProfiles(testRegionDomain()) else ALL
  }

  private fun storedTestRegionDomain(): String? {
    return try {
      SignalStore.tellomiRegion.testRegionDomain
    } catch (e: Exception) {
      null
    }
  }

  /**
   * 测试区：CN 档的形状（同名标签），主机挂到 [domain] 下（`chat.<域>`、`grpc.chat.<域>`、`cdn3.<域>`…，路径和端口不变），
   * `enabled = true`。用来在 CN 保持关闭、`tellomi.cn` 下没有任何 DNS 记录的前提下验切区（#1055 判据 2）。
   * 只换这一份表，不改 [ALL]，所以 `problems(ALL)` 和第二刀的门禁照旧。[domain] 不像域名、或者就是 `tellomi.cn`（及其子域）就当没设。
   */
  fun testRegionProfiles(domain: String?): List<TellomiRegionProfile> {
    if (domain == null || !isPlausibleTestDomain(domain)) {
      return ALL
    }
    val testRegion = cnOf(GLOBAL, domain = ".$domain", enabled = true)
    return ALL.map { if (it.id == TellomiRegionId.CN) testRegion else it }
  }

  private fun isPlausibleTestDomain(domain: String): Boolean {
    // 测试区是「开着的假 CN」：填 tellomi.cn 本身或它的子域就等于把真 CN 打开，不算
    if (".${domain.lowercase()}".endsWith(CN_DOMAIN)) {
      return false
    }
    val labels = domain.split(".")
    return labels.size >= 2 && labels.all { label -> label.isNotEmpty() && label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } }
  }

  /**
   * `SignalServiceNetworkAccess.DNS` 最后一档（StaticDns）的表：包里各区的 staticIps 合在一起，今天只有 global 档有。
   * 切区不用重建它：CN 的主机在表里查不到，落到系统 DNS。
   */
  @JvmStatic
  fun staticIpTable(): Map<String, Set<String>> {
    return ALL.flatMap { it.staticIps.entries }.associate { it.key to it.value }
  }

  /**
   * 记住的区 id → 区。没有记录、不认识、或者那个区被关了，一律回落 global
   * （契约第五节第 8 条：运行时来源坏了只回落，绝不抛）。
   */
  fun resolve(storedId: String?, profiles: List<TellomiRegionProfile> = ALL): TellomiRegionProfile {
    return profiles.firstOrNull { it.id.id == storedId && it.enabled } ?: GLOBAL
  }

  /**
   * 契约第四节：同名标签挂到 `tellomi.cn`。scheme、端口、路径都不变，只把主机名里的 `.tellomi.app` 换成 `.tellomi.cn`。
   * 这样 CN 档的每一个主机都在 `tellomi.cn` 下（App 备案要填运行时连接的全部域名，漏一条就是漏报）。
   */
  fun cnOf(global: TellomiRegionProfile, domain: String = CN_DOMAIN, enabled: Boolean = false): TellomiRegionProfile {
    // domain / enabled 只有测试区会改（testRegionProfiles）
    val rehost = { urlOrHost: String -> toCnHost(urlOrHost, domain) }
    return global.copy(
      id = TellomiRegionId.CN,
      enabled = enabled,
      chat = rehost(global.chat),
      grpcChatHost = rehost(global.grpcChatHost),
      storage = rehost(global.storage),
      cdn0 = rehost(global.cdn0),
      cdn2 = rehost(global.cdn2),
      cdn3 = rehost(global.cdn3),
      updatesHost = rehost(global.updatesHost),
      badgeStaticRoot = rehost(global.badgeStaticRoot),
      apkUpdateManifestUrl = global.apkUpdateManifestUrl?.let(rehost),
      contentProxyHost = rehost(global.contentProxyHost),
      captchaRegistration = rehost(global.captchaRegistration),
      captchaChallenge = rehost(global.captchaChallenge),
      sfu = rehost(global.sfu),
      uptimeHost = rehost(global.uptimeHost),
      debugLog = rehost(global.debugLog),
      feed = rehost(global.feed),
      // 契约第三节：staticIps 只属于 global 档，CN 暂无回落
      staticIps = emptyMap()
    )
  }

  /** URL 或主机名 → 主机名（去掉 scheme、端口、路径）。 */
  fun hostOf(urlOrHost: String): String {
    val start = urlOrHost.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val end = urlOrHost.indexOfAny(charArrayOf('/', ':', '?', '#'), startIndex = start).let { if (it < 0) urlOrHost.length else it }
    return urlOrHost.substring(start, end)
  }

  /**
   * 主机所在的 Tellomi 根域（`tellomi.app` / `tellomi.cn`），都不是就 null。
   */
  @JvmStatic
  fun tellomiRootOf(host: String): String? {
    val h = host.lowercase().removeSuffix(".")
    return listOf(GLOBAL_DOMAIN, CN_DOMAIN).map { it.removePrefix(".") }.firstOrNull { h == it || h.endsWith(".$it") }
  }

  /**
   * 服务端下发的地址（例如 `global.gif.proxyUrl`）能不能在 [region] 里用：同一个 Tellomi 根域才行
   * （下发值按账号、不按区，REGION_PROFILE 第八节）。区的 chat 不在 Tellomi 根域下（测试区）时不拦。
   */
  @JvmStatic
  fun isHostInRegion(host: String, region: TellomiRegionProfile): Boolean {
    val regionRoot = tellomiRootOf(hostOf(region.chat)) ?: return true
    return tellomiRootOf(host) == regionRoot
  }

  /** 只换主机部分；主机不在 `tellomi.app` 下的原样返回，由 [problems] 挑出来。 */
  fun toCnHost(urlOrHost: String, domain: String = CN_DOMAIN): String {
    val host = hostOf(urlOrHost)
    if (!host.endsWith(GLOBAL_DOMAIN)) {
      return urlOrHost
    }
    val start = urlOrHost.indexOf(host)
    return urlOrHost.substring(0, start) + host.removeSuffix(GLOBAL_DOMAIN) + domain + urlOrHost.substring(start + host.length)
  }

  /**
   * 包内区域表的不变量（契约第四节，第五节第 2、4 条）。空列表 = 合法。
   * 表是编进包里的，不合法是构建缺陷：单测把关，[TellomiRegions] 类加载时再核一次（契约第五节第 8 条：包内配置坏了拒绝启动）。
   */
  fun problems(profiles: List<TellomiRegionProfile>): List<String> {
    val problems = mutableListOf<String>()

    val ids = profiles.map { it.id }
    if (ids.size != ids.toSet().size) {
      problems += "duplicate region ids: $ids"
    }

    val global = profiles.firstOrNull { it.id == TellomiRegionId.GLOBAL }
    if (global == null) {
      problems += "missing the global region"
    } else if (!global.enabled) {
      problems += "the global region must be enabled"
    }

    for (profile in profiles) {
      val hosts = profile.endpoints().map { hostOf(it) }.toSet()
      profile.staticIps.keys.filterNot { it in hosts }.forEach {
        problems += "${profile.id.id} has static ips for a host that is not one of its endpoints: $it"
      }
    }

    profiles.firstOrNull { it.id == TellomiRegionId.CN }?.let { cn ->
      if (cn.staticIps.isNotEmpty()) {
        problems += "the cn region has no static ip fallback yet: ${cn.staticIps.keys}"
      }
      cn.endpoints().filterNot { hostOf(it).endsWith(CN_DOMAIN) }.forEach {
        problems += "cn endpoint outside tellomi.cn: $it"
      }
    }

    return problems
  }
}
