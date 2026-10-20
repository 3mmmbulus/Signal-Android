/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.friends

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.signal.core.util.readToSet
import org.signal.core.util.requireNonNullString
import org.signal.core.util.select
import org.signal.network.NetworkResult
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.feed.FeedCapabilityStore
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.TellomiFeed
import org.thoughtcrime.securesms.feed.net.FeedAuthApi
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.net.FeedServerErrors
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.region.TellomiRegions
import java.io.IOException

/** [FeedFriendSync] 在这台手机上的接线。 */
object FeedFriends {

  @JvmStatic
  fun forCurrentProcess(): FeedFriendSync {
    return FeedFriendSync(
      // 好友声明要引擎部署了 friend_assertions（契约 3.0）；Signal-Server 那一半仍按 404 标记判断
      enabled = { TellomiFeed.isTabEnabled() && !TellomiFeed.isSampleDataEnabled() && SignalStore.account.isRegistered && FeedCapabilityStore.entries().friendAssertions },
      engineKey = { TellomiRegions.current().let { "${it.id.id} ${it.feed}" } },
      friends = ::myFriendAcis,
      signer = ::sign,
      engine = { assertion -> FeedSources.engine().reportFriend(assertion) },
      state = StoredState
    )
  }

  /**
   * 我这一边算好友的人（第三节第 19 条的三种途径里，本机看得到的两种）：
   * 在我通讯录里的 Signal 用户，或者我在聊天里接受过（[RecipientTable.PROFILE_SHARING]）的人。
   * 屏蔽了的、隐藏了的、没注册的不算。「互存」「对方也接受」由对方那一半保证：引擎要两边都报才建边。
   */
  private fun myFriendAcis(): Set<String> {
    val selfId = Recipient.self().id.toLong()
    return SignalDatabase.readableDatabase
      .select(RecipientTable.ACI_COLUMN)
      .from(RecipientTable.TABLE_NAME)
      .where(
        """
        ${RecipientTable.ID} != ? AND ${RecipientTable.ACI_COLUMN} NOT NULL AND ${RecipientTable.REGISTERED} = ? AND
        ${RecipientTable.BLOCKED} = 0 AND ${RecipientTable.HIDDEN} = ? AND
        (${RecipientTable.PROFILE_SHARING} = 1 OR ${RecipientTable.SYSTEM_CONTACT_URI} NOT NULL)
        """,
        selfId,
        RecipientTable.RegisteredState.REGISTERED.id,
        Recipient.HiddenState.NOT_HIDDEN.serialize()
      )
      .run()
      .readToSet { it.requireNonNullString(RecipientTable.ACI_COLUMN) }
  }

  private suspend fun sign(peerAci: String, add: Boolean): String? {
    return withContext(Dispatchers.IO) {
      when (val result = FeedAuthApi(AppDependencies.authWebSocket).friendAssertion(peerAci, add)) {
        is NetworkResult.Success -> result.result.assertion
        // 404 只有带 `{"error":"account_not_found"}`（JSON）才是「对方没有账号」（null：跳过这个人）；别的 404 是这台服务器没有这个接口，抛出来，
        // 不能当成已处理。429 / 503 带上 Retry-After。规则见 FRIEND-ASSERTION-404-MARKER.md，逻辑在 FeedServerErrors.friendAssertion
        is NetworkResult.StatusCodeError -> {
          val error = FeedServerErrors.friendAssertion(
            status = result.code,
            contentType = result.header("content-type"),
            body = result.stringBody ?: result.binaryBody?.decodeToString(),
            retryAfterHeader = result.header("retry-after"),
            add = add
          )
          if (error == null) null else throw error
        }
        is NetworkResult.NetworkError -> throw result.exception
        is NetworkResult.ApplicationError -> throw IOException("friend assertion failed", result.throwable)
      }
    }
  }

  @Serializable
  private data class Reported(val engine: String, val acis: List<String>)

  private object StoredState : FeedFriendSync.State {
    override var checkedAt: Long
      get() = SignalStore.tellomiFeed.friendsCheckedAt
      set(value) {
        SignalStore.tellomiFeed.friendsCheckedAt = value
      }

    override fun reported(engineKey: String): Set<String> {
      val json = SignalStore.tellomiFeed.friendsReported ?: return emptySet()
      val stored = try {
        FeedJson.decodeFromString(Reported.serializer(), json)
      } catch (e: Exception) {
        return emptySet()
      }
      return if (stored.engine == engineKey) stored.acis.toSet() else emptySet()
    }

    override fun save(engineKey: String, acis: Set<String>) {
      SignalStore.tellomiFeed.friendsReported = FeedJson.encodeToString(Reported.serializer(), Reported(engineKey, acis.sorted()))
    }
  }
}
