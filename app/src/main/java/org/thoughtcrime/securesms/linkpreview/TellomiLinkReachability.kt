/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.linkpreview

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 本机可达性记录（ADR-0063 §4.3，tellomi/tellomi#1422）。
 *
 * 一次抓取在**网络层明确失败**（DNS 失败、TCP 连接被拒 / 被重置、TLS 握手失败；HTTP 状态码不算），
 * 就记下「这个 host 在当前网络不可达」，之后同一 host 的请求直接跳过、不再发出去。
 * **超时不算**（连接超时也不算；产品经理 2026-10-19 定，ADR-0063 §4.4）。
 * 系统网络变化时（[networkId] 变了）或 [ttlMs]（2 分钟，原来 30 分钟）以后清空。
 *
 * **只在内存、不落盘、不上报**：不会变成一份「用户所在网络能访问什么」的记录。
 */
class TellomiLinkReachability(
  private val clock: () -> Long,
  private val networkId: () -> Any?,
  private val ttlMs: Long = TTL_MS
) {

  companion object {
    /** ADR-0063 §4.3 / §4.4：2 分钟后清空（产品经理 2026-10-19 从 30 分钟缩短）。 */
    val TTL_MS: Long = TimeUnit.MINUTES.toMillis(2)

    /**
     * 区域先验（ADR-0063 §4.3 第一层）。P1 固定是 `global`：RegionProfile 两端还没接进链接管线（#1055 / #1056），
     * 而且 CN 档在备案前关着。`rust/links` 接进来以后作为 `Ctx.region` 传进去；这里不读 `TellomiRegions`。
     */
    const val P1_REGION_PRIOR = "global"
  }

  private val unreachableSince = HashMap<String, Long>()
  private var recordedOnNetwork: Any? = null

  /** [host] 在当前网络上是不是已知不可达。 */
  @Synchronized
  fun isUnreachable(host: String): Boolean {
    dropIfNetworkChanged()
    val key = host.lowercase(Locale.ROOT)
    val since = unreachableSince[key] ?: return false
    if (clock() - since >= ttlMs) {
      unreachableSince.remove(key)
      return false
    }
    return true
  }

  /** 当前网络上已知不可达、还没过期的 host（给 `rust/links` 的发送上下文，§4.3）。 */
  @Synchronized
  fun unreachableHosts(): List<String> {
    dropIfNetworkChanged()
    val now = clock()
    unreachableSince.entries.removeAll { now - it.value >= ttlMs }
    return unreachableSince.keys.sorted()
  }

  /** 记下 [host] 在当前网络上不可达。 */
  @Synchronized
  fun markUnreachable(host: String) {
    dropIfNetworkChanged()
    unreachableSince[host.lowercase(Locale.ROOT)] = clock()
  }

  @Synchronized
  fun clear() {
    unreachableSince.clear()
  }

  private fun dropIfNetworkChanged() {
    val current = networkId()
    if (current != recordedOnNetwork) {
      unreachableSince.clear()
      recordedOnNetwork = current
    }
  }
}
