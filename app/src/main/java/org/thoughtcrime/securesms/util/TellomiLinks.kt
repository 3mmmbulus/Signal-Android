/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.util

import android.net.Uri
import org.signal.core.util.TellomiUsernames

/**
 * Tellomi 的链接 scheme / 域名，以及「同时接受新旧两种形状」的判定。
 *
 * 决定与完整映射见超级仓库 `docs/signal/LINKS_AND_SCHEMES.md`：
 *   `sgnl://` → `tellomi://`，`signalcaptcha://` → `tellomicaptcha://`，
 *   `signal.me|.group|.art|.link` → `tell.cc/u|g|s|call`。
 *
 * **分两阶段**（owner / taishi 2026-09-22 05:00 改的策略）：
 * 1. **接受**：三端各自独立上线「新旧都认」，顺序无所谓 —— 就是这个文件。
 * 2. **发出**：等三端都到 1 之后，再把「生成链接」翻到新形状。tellomi/tellomi#1113 翻了三种：
 *    用户名链接（`UsernameRepository.BASE_URL`）、群邀请（`GroupInviteLinkUrl`）、通话链接（`CallLinks.url()`）；
 *    贴纸（`StickerUrl`）仍发 `signal.art`，与 iOS 一起另做。本文件只管判定与解析。
 *
 * 为什么集中在一处：这些字面量原本散在十几个文件里（SignalMeUtil / GroupInviteLinkUrl /
 * StickerUrl / CallLinks / LinkDeviceRepository / CommunicationActions / …），
 * 每处各写一遍 `||` 很容易漏掉一处，而漏掉的那处的症状是「某一类链接打不开」，
 * 与根因毫无关联 —— 和我们在常量漂移上踩过的两次是同一形状。
 */
object TellomiLinks {
  const val SCHEME = "tellomi"
  const val LEGACY_SCHEME = "sgnl"

  const val CAPTCHA_SCHEME = "tellomicaptcha://"
  const val LEGACY_CAPTCHA_SCHEME = "signalcaptcha://"

  /**
   * 短域名。**一个域名承载全部链接类型，靠第一段路径区分**（owner 2026-09-22：
   * tell.cc 以后不只是用户名地址，群邀请 / 贴纸 / 通话链接 / 邀请链接都要走它）。
   *
   * 路径命名空间 —— **新增用途一律在这里登记**，不要随手占用：
   *
   * | 路径 | 用途 | 状态 |
   * | --- | --- | --- |
   * | `/u` | 找人：`#p/<E164>`、`#eu/<加密用户名链接>`、`#u/<明文用户名>` | 已用 |
   * | `/g` | 群邀请：`#<invite>` | 已用 |
   * | `/s` | 贴纸包：`#pack_id=…&pack_key=…` | 已用 |
   * | `/call` | 通话链接：`#key=…` | 已用（通话本身是阶段三） |
   * | `/i` | **邀请下载**（邀请朋友装 Tellomi） | **预留，未实现** |
   * | `/m` | 动态：`#<content id>`（小写 UUID，内容引擎 docs/api.md「动态的外链」） | 已用 |
   * | `/` | 不用 —— R2 自定义域对根路径只会 404（2026-09-22 上线时踩到） |
   *
   * 秘密一律放 `#` 后面，不进服务器日志 —— 沿用 Signal 的形状，别改。
   *
   * 每加一种，要同时动三处，缺一不可：
   * 1. 这张表 + 对应常量；
   * 2. `AndroidManifest.xml` 的 intent-filter（**每种单开一个 filter**，见文件末尾的说明）；
   * 3. 落地页 `deploy/tellcc/site/`（没装 App 的人要有地方落）。
   * 只加 1 不加 2，链接点了没反应；只加 2 不加 3，没装 App 的人看到 404。
   */
  const val HOST = "tell.cc"

  const val PATH_CONTACT = "/u"
  const val PATH_GROUP = "/g"
  const val PATH_STICKER = "/s"
  const val PATH_CALL = "/call"
  const val PATH_POST = "/m"

  /** 预留：邀请朋友下载。还没有处理逻辑，所以 manifest 里**故意没有**对应的 intent-filter
   *  —— 声明了却不处理的话，用户点链接会打开 App 然后什么也不发生，比直接落网页更糟。 */
  const val PATH_INVITE_RESERVED = "/i"

  const val LEGACY_HOST_CONTACT = "signal.me"
  const val LEGACY_HOST_GROUP = "signal.group"
  const val LEGACY_HOST_STICKER = "signal.art"
  const val LEGACY_HOST_CALL = "signal.link"

  /** 自定义 scheme：新旧都认。 */
  @JvmStatic
  fun isAppScheme(scheme: String?): Boolean = SCHEME.equals(scheme, ignoreCase = true) || LEGACY_SCHEME.equals(scheme, ignoreCase = true)

  /**
   * 是不是**设备配对二维码**（`tellomi://linkdevice…` / `sgnl://linkdevice…`）。
   *
   * 给「扫错入口」的提示用：用户名链接那个扫码器扫到它时，要能说清「这是配对码，
   * 请到 设置 → 已关联的设备 扫」，而不是只回一句「二维码无效」（#947，owner 撞过）。
   *
   * Telegram reference（只看机制，一行没搬——GPLv2 与 AGPLv3 不兼容）：
   * - Android `reference/telegram/android/.../ui/CameraScanActivity.java:156-159` 把扫码器分成
   *   `TYPE_QR` / `TYPE_QR_LOGIN` / `TYPE_QR_WEB_BOT`；`:1380` 里 `TYPE_QR_LOGIN` **显式拒绝**
   *   非 `tg://login?token=` 的内容，`SessionsActivity.java:1220` 设备页用的就是这个窄模式。
   * - 通用那个也并不万能：`NewContactBottomSheet.java:660-662` 用 `TYPE_QR`，但 `didFindQr` 里
   *   只做 `Browser.extractUsername`，不是用户名就弹一条错误。
   * 结论：**Telegram 同样不统一扫码器**，所以我们也不合并入口；我们自己的改进只是
   *   「认出对方的码并指路」，这条是 Tellomi 的决定，不是抄来的。
   */
  @JvmStatic
  fun isDeviceLinkQr(url: String?): Boolean {
    if (url == null) return false
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return isAppScheme(uri.scheme) && uri.host.equals("linkdevice", ignoreCase = true)
  }

  /** captcha 回跳：新旧都认。 */
  @JvmStatic
  fun isCaptchaUrl(url: String?): Boolean = url != null && (url.startsWith(CAPTCHA_SCHEME) || url.startsWith(LEGACY_CAPTCHA_SCHEME))

  /**
   * 去掉 captcha 回跳的 scheme 前缀，拿到 token。
   * 两种前缀长度不同（`tellomicaptcha://` 17 / `signalcaptcha://` 15），
   * 所以必须按实际匹配到的那个前缀截。上游用的是 `substring(常量.length)`，
   * 本来就没写死数字；但那只对**单一**前缀成立，接受两种之后就得在这里分支。
   */
  @JvmStatic
  fun stripCaptchaScheme(url: String): String = when {
    url.startsWith(CAPTCHA_SCHEME) -> url.substring(CAPTCHA_SCHEME.length)
    url.startsWith(LEGACY_CAPTCHA_SCHEME) -> url.substring(LEGACY_CAPTCHA_SCHEME.length)
    else -> url
  }

  /**
   * 明文用户名链接里的用户名，没有就返回 null。**两种写法都认**：
   *
   * 1. `https://tell.cc/ceshi.57` —— **用户看到、会去分享的就是这一种**（t.me 式，
   *    owner 2026-09-22 定）。用户名带判别数字，所以一定含 `.`。
   * 2. `tellomi://tell.cc/u#u/ceshi.57` —— 落地页把 1 改写成的内部形状。
   *
   * 两种都要认：装了 App 的人点 1 应当**直接**进 App（不闪一下浏览器），
   * 没装的人才走落地页；而落地页唤起 App 时用的是 2。只认 2 的话，
   * 第一种链接永远要绕一次浏览器。
   *
   * 上游没有这种明文形状：Signal 的 `#eu/` 是加密块。
   *
   * **不带「.数字」的也认**（tellomi/tellomi#1106，ADR-0066）：`tell.cc/kaixin` → `kaixin.01`。
   * 返回的**总是协议层的完整用户名**（裸 nickname 补 `.01`，已带后缀的原样），调用方可以直接拿去查、存进 Recipient。
   *
   * 原来靠「必须含 `.`」挡保留路径，放开裸 nickname 之后改成显式挡：
   * - 裸 nickname 至少 3 位、首字符是字母或下划线：1–2 位的保留路径 `/u` `/g` `/s` `/i` … 和
   *   `/.well-known/assetlinks.json`（以 `.` 开头）天然匹配不上；
   * - 3 位以上的保留路径（`call`、`app`，ADR-0066 §五「命名空间」）在 [RESERVED_FIRST_LEVEL_PATHS] 里显式排除。
   */
  @JvmStatic
  fun parsePlainUsernameFromLink(link: String?): String? {
    if (link == null) return null
    val raw = PLAIN_USERNAME_FRAGMENT_REGEX.find(link)?.groups?.get(2)?.value
      ?: PLAIN_USERNAME_PATH_REGEX.find(link)?.groups?.get(2)?.value
      ?: return null
    if (!raw.contains('.') && raw.lowercase() in RESERVED_FIRST_LEVEL_PATHS) return null
    return TellomiUsernames.toProtocolUsername(raw)
  }

  /**
   * tell.cc 的一级路径与预留路径（ADR-0066 §五：它们都进了用户名保留词，服务端也拒 `call.01` 之类）。
   * 1–2 位的其实已被长度规则挡住，列全是为了让这张表和 ADR 一一对得上。
   */
  private val RESERVED_FIRST_LEVEL_PATHS = setOf("u", "g", "s", "call", "i", "m", "e", "a", "app", "b")

  /** 用户名：带后缀的旧形状，或 3–32 位、首字符为字母 / 下划线的裸 nickname。 */
  private const val USERNAME_IN_LINK = """[A-Za-z0-9_]+\.[0-9]+|[A-Za-z_][A-Za-z0-9_]{2,31}"""

  /** 内部形状：`tellomi://tell.cc/u#u/<username>`（落地页唤起 App 用的） */
  private val PLAIN_USERNAME_FRAGMENT_REGEX =
    """^(https://|tellomi://)tell\.cc/u/?#u/($USERNAME_IN_LINK)$""".toRegex()

  /**
   * 用户看到的形状：`https://tell.cc/<username>`。
   *
   * **允许尾随 `?…` / `#…`** —— 与 iOS 对齐（taishi 在 #975 的正则比我第一版稳：
   * 真实分享出去的链接常带 utm / 来源参数，`tell.cc/ceshi.57?from=wechat` 我原来会漏掉）。
   */
  private val PLAIN_USERNAME_PATH_REGEX =
    """^(https://|tellomi://)tell\.cc/($USERNAME_IN_LINK)/?([?#].*)?$""".toRegex()

  // 这里**故意不提供** `isGroupHost(host)` 之类只看 host 的便利方法：
  // tell.cc 一个域名承载 /u /g /s /call 四种用途，只判 host 会把联系人链接
  // 也放进群邀请的解析里（然后 Base64 解不动，报「群链接无效」，与真实原因无关）。
  // 各调用点自己连路径一起判，见 GroupInviteLinkUrl.getGroupUrl。

  /** 一条动态的外链：`https://tell.cc/m#<content id>`。只给公开动态、作者不是私密账号时用（`dynamic-feed.md` 第三节第 29 条）。 */
  @JvmStatic
  fun postLink(contentId: String): String = "https://$HOST$PATH_POST#${contentId.lowercase()}"

  /**
   * 动态外链里的 content id（小写），不是就返回 null。两种都认：`https://tell.cc/m#<id>` 和 App 内的 `tellomi://tell.cc/m#<id>`；
   * `/m` 后面多一个 `/` 也认（落地页和手打都可能有）。id 必须是 UUID，不认的一律 null，不去问引擎。
   */
  @JvmStatic
  fun parsePostId(link: String?): String? {
    if (link == null) return null
    return POST_LINK_REGEX.find(link.trim())?.groups?.get(2)?.value?.lowercase()
  }

  private val POST_LINK_REGEX =
    """^(https://|tellomi://)tell\.cc/m/?#([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})$""".toRegex(RegexOption.IGNORE_CASE)
}
