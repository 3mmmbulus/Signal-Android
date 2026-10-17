/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.restriction

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.core.util.logging.Log
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.WebsocketResponse
import org.signal.network.websocket.get
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.api.fromWebSocketRequest
import java.util.concurrent.TimeUnit

/**
 * Tellomi（tellomi/tellomi ADR-0078 §3.4 第三刀）：服务端的人工处置说明。
 *
 * 服务端在三处给出同一个形状 `{"tellomiRestriction":{"level","until","appeal","case"}}`：发消息被拒的 403、取发送证书被拒的
 * 403、`GET /v1/tellomi/restriction` 的 200（没有处置是 204）。`until` 是 epoch 秒，`case` 是案件号（老条目可能没有）。
 */
data class TellomiRestriction(
  val level: Level,
  val untilMillis: Long,
  val appeal: String,
  val caseId: String?
) {
  enum class Level(val code: String) {
    STRANGER("stranger"),
    ALL("all"),
    SUSPENDED("suspended")
  }

  fun isActive(now: Long): Boolean = untilMillis > now

  @Serializable
  private data class Body(val tellomiRestriction: Raw? = null)

  @Serializable
  private data class Raw(
    val level: String? = null,
    val until: Long? = null,
    val appeal: String? = null,
    @SerialName("case") val caseId: String? = null
  )

  companion object {
    private val json = Json { ignoreUnknownKeys = true }
    private val APPEAL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val CASE_ID = Regex("^[0-9A-Za-z._-]{1,64}$")

    /** body 里没有可用的 `tellomiRestriction` 就是 null（老服务端、别的 403、空的 204、字段不对）。 */
    @JvmStatic
    fun parse(body: String?): TellomiRestriction? {
      if (body.isNullOrBlank()) return null
      val raw = try {
        json.decodeFromString(Body.serializer(), body).tellomiRestriction
      } catch (e: Exception) {
        null
      } ?: return null
      val level = Level.entries.firstOrNull { it.code == raw.level } ?: return null
      val until = raw.until?.takeIf { it > 0 } ?: return null
      val appeal = raw.appeal?.takeIf { APPEAL.matches(it) } ?: return null
      return TellomiRestriction(level, TimeUnit.SECONDS.toMillis(until), appeal, raw.caseId?.takeIf { CASE_ID.matches(it) })
    }

    /** 存回 [TellomiRestrictionValues] 用：和服务端同一个形状，读的时候走 [parse]。 */
    fun toJson(restriction: TellomiRestriction): String {
      return json.encodeToString(
        Body.serializer(),
        Body(Raw(restriction.level.code, TimeUnit.MILLISECONDS.toSeconds(restriction.untilMillis), restriction.appeal, restriction.caseId))
      )
    }
  }
}

/**
 * 什么时候问、存在哪、告诉谁（ADR-0078 §3.4「怎么告诉当事人」）：
 * - 发消息 / 取发送证书被拒（403）：body 里有说明就记下，没有（REST 兜底、证书接口都会把 body 丢掉）就问一次服务端；
 * - 回到前台：至多每 6 小时问一次；
 * - 结果存在 [SignalStore.tellomiRestriction]，聊天列表的 `TellomiRestrictionBanner` 显示它；截止时间到了、或者服务端说没有了就清掉。
 *
 * 被暂停的账号登不上，问不了；暂停只在重新注册被拒（422）时说明（注册流程自己解析）。
 */
object TellomiRestrictions {
  private val TAG = Log.tag(TellomiRestrictions::class.java)

  private const val PATH = "/v1/tellomi/restriction"
  private val REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(6)

  /** 有处置时问得勤一些：提前解除（申诉通过、处置有误）时，横幅不该还挂几个小时。 */
  private val REFRESH_INTERVAL_WHILE_RESTRICTED = TimeUnit.MINUTES.toMillis(15)

  /** 回前台时该不该问（时钟往回拨过也问一次）。 */
  @JvmStatic
  fun shouldRefresh(now: Long, lastRefreshAt: Long, restricted: Boolean): Boolean {
    val sinceLast = now - lastRefreshAt
    return sinceLast < 0 || sinceLast >= if (restricted) REFRESH_INTERVAL_WHILE_RESTRICTED else REFRESH_INTERVAL
  }

  private val changes = MutableStateFlow<TellomiRestriction?>(null)
  private var loaded = false

  /** 现在生效的处置（变了就发一次）。 */
  val current: StateFlow<TellomiRestriction?>
    get() {
      active()
      return changes
    }

  /** 现在生效的处置；过期的顺手清掉。 */
  @JvmStatic
  @JvmOverloads
  fun active(now: Long = System.currentTimeMillis()): TellomiRestriction? {
    val stored = TellomiRestriction.parse(SignalStore.tellomiRestriction.currentJson)
    val active = stored?.takeIf { it.isActive(now) }
    if (stored != null && active == null) {
      store(null)
    } else if (!loaded || changes.value != active) {
      loaded = true
      changes.value = active
    }
    return active
  }

  private fun store(restriction: TellomiRestriction?) {
    val active = restriction?.takeIf { it.isActive(System.currentTimeMillis()) }
    SignalStore.tellomiRestriction.currentJson = active?.let { TellomiRestriction.toJson(it) }
    if (changes.value != active) {
      Log.i(TAG, if (active != null) "Active: ${active.level.code}" else "Cleared")
    }
    loaded = true
    changes.value = active
  }

  /** 200 → body 原样（交给 [TellomiRestriction.parse]），204 → 空字符串（没有处置），别的状态码照常是错误。 */
  private object BodyOrEmpty : NetworkResult.WebSocketResponseConverter<String> {
    override fun convert(response: WebsocketResponse): NetworkResult<String> {
      return if (response.status in 200..299) NetworkResult.Success(response.body ?: "") else response.toStatusCodeError()
    }
  }

  /** 问服务端一次（阻塞，后台线程）。失败（断网、老服务端 404）不动现有的说明。 */
  @JvmStatic
  @WorkerThread
  fun refresh(reason: String): TellomiRestriction? {
    val result = NetworkResult.fromWebSocketRequest(
      signalWebSocket = AppDependencies.authWebSocket,
      request = WebSocketRequestMessage.get(PATH),
      webSocketResponseConverter = BodyOrEmpty
    )
    return when (result) {
      is NetworkResult.Success -> {
        // 问到了才记时间：回前台那一刻断网的话，下次回前台还要再问，不能白等 6 小时
        SignalStore.tellomiRestriction.lastRefreshAt = System.currentTimeMillis()
        store(TellomiRestriction.parse(result.result))
        active()
      }
      else -> {
        Log.w(TAG, "Refresh ($reason) failed: ${result.javaClass.simpleName}")
        active()
      }
    }
  }

  /** 回到前台时调（后台线程）：没有处置时距离上次问超过 6 小时才问，有处置时 15 分钟。 */
  @JvmStatic
  @WorkerThread
  fun onForeground() {
    if (!SignalStore.account.isRegistered) return
    if (shouldRefresh(System.currentTimeMillis(), SignalStore.tellomiRestriction.lastRefreshAt, restricted = active() != null)) {
      refresh("foreground")
    }
  }

  /**
   * 发消息或取发送证书收到 403 时调（后台线程）：返回 true = 是人工处置造成的，发送任务不要再重试。
   * [body] 能读到就直接用，读不到就问服务端。
   */
  @JvmStatic
  @WorkerThread
  fun onForbidden(body: String?): Boolean {
    val fromBody = TellomiRestriction.parse(body)
    if (fromBody != null) {
      store(fromBody)
      return fromBody.isActive(System.currentTimeMillis())
    }
    return refresh("forbidden") != null
  }
}
