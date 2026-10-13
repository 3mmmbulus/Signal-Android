/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.friends

import org.signal.core.util.logging.Log

/**
 * 把「我把谁当好友」报给内容引擎（数据模型 2.4、Q-16；需求第三节第 19 条）。
 *
 * 好友在 Signal 里是端到端的，服务端看不到，所以每个人只报自己这一半：本机算出好友名单（[friends]），
 * 和上次报过的比一比，新加的报 `add`，没了的报 `remove`。两边都报了引擎才建边，任何一方撤回整条边就删。
 * 每一步都先找 Signal-Server 签名（[signer]，签的是假名），再把签名原样交给引擎（[engine]），客户端不接触别人的假名。
 *
 * 回到前台时调 [syncIfStale]：离上次不到 [INTERVAL_MILLIS] 就不做；名单没变不走网络。
 * 一次最多处理 [MAX_CHANGES_PER_RUN] 个变化，先撤回再新加（撤回关系到别人还能不能看到我的帖子，更急）；
 * 没做完的下次回到前台接着做。出错就停在这里，做完的部分已经记下。
 */
class FeedFriendSync(
  private val enabled: () -> Boolean,
  private val engineKey: () -> String,
  private val friends: () -> Set<String>,
  private val signer: Signer,
  private val engine: suspend (assertion: String) -> Unit,
  private val state: State,
  private val clock: () -> Long = System::currentTimeMillis
) {

  /** Signal-Server 签一张好友声明。加好友时对方没有账号返回 null（不用报）；其他失败抛异常。 */
  fun interface Signer {
    suspend fun sign(peerAci: String, add: Boolean): String?
  }

  interface State {
    var checkedAt: Long

    /** 在这个引擎（[engineKey]）上报过的好友 ACI；换了引擎（切区）就是空的，重新报一遍。 */
    fun reported(engineKey: String): Set<String>
    fun save(engineKey: String, acis: Set<String>)
  }

  enum class Outcome { SKIPPED, UNCHANGED, DONE, MORE_LEFT, FAILED }

  companion object {
    private val TAG = Log.tag(FeedFriendSync::class)

    const val INTERVAL_MILLIS = 15 * 60 * 1000L
    const val MAX_CHANGES_PER_RUN = 50
  }

  /** 会走网络，别在主线程调。 */
  suspend fun syncIfStale(): Outcome {
    if (!enabled()) return Outcome.SKIPPED
    val now = clock()
    if (state.checkedAt != 0L && now - state.checkedAt in 0 until INTERVAL_MILLIS) return Outcome.SKIPPED
    state.checkedAt = now

    val key = engineKey()
    val reported = state.reported(key).toMutableSet()
    val desired = friends()
    val removals = (reported - desired).sorted()
    val additions = (desired - reported).sorted()
    if (removals.isEmpty() && additions.isEmpty()) return Outcome.UNCHANGED

    val changes = removals.map { it to false } + additions.map { it to true }
    try {
      for ((aci, add) in changes.take(MAX_CHANGES_PER_RUN)) {
        val assertion = signer.sign(aci, add)
        if (assertion != null) {
          engine(assertion)
        }
        if (add) reported += aci else reported -= aci
        state.save(key, reported)
      }
    } catch (e: Exception) {
      Log.w(TAG, "friend sync stopped", e)
      return Outcome.FAILED
    }

    return if (changes.size > MAX_CHANGES_PER_RUN) {
      // 下次回到前台就接着做，不等满 15 分钟
      state.checkedAt = 0L
      Outcome.MORE_LEFT
    } else {
      Outcome.DONE
    }
  }
}
