/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.search

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.net.FeedJson
import org.thoughtcrime.securesms.feed.net.FeedPersonSummary
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * 搜索历史和「最近看过的人」。只在手机上（第三节第 34 条），服务端不记；不进备份。
 */
interface FeedSearchHistory {
  fun queries(): List<String>
  fun addQuery(query: String)
  fun removeQuery(query: String)
  fun clearQueries()
  fun people(): List<FeedPersonSummary>
  fun addPerson(person: FeedPersonSummary)
  fun clearPeople()

  companion object {
    const val MAX_QUERIES = 20
    const val MAX_PEOPLE = 10
  }
}

/** 纯内存的一份，给测试和样例用。 */
open class InMemoryFeedSearchHistory : FeedSearchHistory {
  protected var queryList: List<String> = emptyList()
  protected var peopleList: List<FeedPersonSummary> = emptyList()

  override fun queries(): List<String> = queryList

  override fun addQuery(query: String) {
    val q = query.trim()
    if (q.isEmpty()) return
    queryList = (listOf(q) + queryList.filterNot { it.equals(q, ignoreCase = true) }).take(FeedSearchHistory.MAX_QUERIES)
    onChanged()
  }

  override fun removeQuery(query: String) {
    queryList = queryList - query
    onChanged()
  }

  override fun clearQueries() {
    queryList = emptyList()
    onChanged()
  }

  override fun people(): List<FeedPersonSummary> = peopleList

  override fun addPerson(person: FeedPersonSummary) {
    peopleList = (listOf(person) + peopleList.filterNot { it.id == person.id }).take(FeedSearchHistory.MAX_PEOPLE)
    onChanged()
  }

  override fun clearPeople() {
    peopleList = emptyList()
    onChanged()
  }

  protected open fun onChanged() = Unit
}

/** 存在 [SignalStore.tellomiFeed]（加密的本机键值库）。 */
class StoredFeedSearchHistory : InMemoryFeedSearchHistory() {

  companion object {
    private val TAG = Log.tag(StoredFeedSearchHistory::class)
    private val QUERIES = ListSerializer(String.serializer())
    private val PEOPLE = ListSerializer(FeedPersonSummary.serializer())
  }

  init {
    val values = SignalStore.tellomiFeed
    queryList = decode(values.searchQueries, QUERIES)
    peopleList = decode(values.searchPeople, PEOPLE)
  }

  override fun onChanged() {
    val values = SignalStore.tellomiFeed
    values.searchQueries = if (queryList.isEmpty()) null else FeedJson.encodeToString(QUERIES, queryList)
    values.searchPeople = if (peopleList.isEmpty()) null else FeedJson.encodeToString(PEOPLE, peopleList)
  }

  private fun <T> decode(text: String?, serializer: KSerializer<List<T>>): List<T> {
    if (text.isNullOrEmpty()) return emptyList()
    return try {
      FeedJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
      Log.w(TAG, "bad search history", e)
      emptyList()
    }
  }
}
