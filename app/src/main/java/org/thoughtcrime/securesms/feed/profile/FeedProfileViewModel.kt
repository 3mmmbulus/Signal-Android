/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.feed.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.feed.FeedInputLimit
import org.thoughtcrime.securesms.feed.FeedSource
import org.thoughtcrime.securesms.feed.FeedSources
import org.thoughtcrime.securesms.feed.net.FeedApiException
import org.thoughtcrime.securesms.feed.net.FeedMyProfile
import org.thoughtcrime.securesms.feed.net.FeedProfileUpdate
import org.thoughtcrime.securesms.feed.takeCodePoints

/**
 * 建 / 改动态的公开主页（契约第八节；`dynamic-feed.md` 第三节第 8、9 条）。
 *
 * 发帖、评论、关注之前引擎要求先有公开主页（409 `profile_required`）；第一次进来名字先填聊天资料里的名字，用户可以改。
 * 这一刀只管名字和简介；头像、兴趣、私密账号随主页页面的 PR 加。ID（handle）就是聊天用户名（第 9 条），不在这里单独设。
 */
class FeedProfileViewModel(
  private val suggestedName: String,
  private val source: FeedSource = FeedSources.current(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(FeedProfileViewModel::class)
  }

  private val internalState = MutableStateFlow(FeedProfileState())
  val state: StateFlow<FeedProfileState> = internalState.asStateFlow()

  init {
    load()
  }

  fun load() {
    internalState.update { it.copy(loading = true, loadFailed = false) }
    viewModelScope.launch {
      try {
        val profile = withContext(ioDispatcher) { source.myProfile() }
        internalState.update {
          it.copy(
            loading = false,
            profile = profile,
            name = profile?.displayName ?: suggestedName.trim().takeCodePoints(FeedMyProfile.MAX_NAME_CHARS),
            bio = profile?.bio.orEmpty()
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "profile load failed", e)
        internalState.update { it.copy(loading = false, loadFailed = true) }
      }
    }
  }

  /**
   * 昵称（[FeedInputLimit.PROFILE_NAME] 个码点）：到上限就不让再输入（文本不变，[FeedProfileState.nameLimitHit] 亮一下），粘贴超长按码点截；
   * [composing]（输入法组字中）一律放行，可以暂时超限，保存不可用，组字结束后再调一次就截一次。
   */
  fun onNameChanged(name: String, composing: Boolean = false) {
    val edit = FeedInputLimit.change(internalState.value.name, name, FeedInputLimit.PROFILE_NAME, composing)
    internalState.update { it.copy(name = edit.text, nameLimitHit = edit.limitHit) }
  }

  /** 简介（[FeedInputLimit.PROFILE_BIO] 个码点），规则同 [onNameChanged]。 */
  fun onBioChanged(bio: String, composing: Boolean = false) {
    val edit = FeedInputLimit.change(internalState.value.bio, bio, FeedInputLimit.PROFILE_BIO, composing)
    internalState.update { it.copy(bio = edit.text, bioLimitHit = edit.limitHit) }
  }

  fun save() {
    val current = internalState.value
    if (!current.canSave) return

    internalState.update { it.copy(saving = true, error = null) }
    viewModelScope.launch {
      try {
        val saved = withContext(ioDispatcher) { source.saveProfile(FeedProfileUpdate(displayName = current.name.trim(), bio = current.bio.trim())) }
        internalState.update { it.copy(saving = false, profile = saved, saved = true) }
      } catch (e: FeedApiException) {
        Log.w(TAG, "profile save rejected: ${e.status} ${e.code}")
        val error = if (e.status == 400) FeedProfileState.Error.INVALID else FeedProfileState.Error.FAILED
        internalState.update { it.copy(saving = false, error = error) }
      } catch (e: Exception) {
        Log.w(TAG, "profile save failed", e)
        internalState.update { it.copy(saving = false, error = FeedProfileState.Error.FAILED) }
      }
    }
  }

  fun onErrorShown() {
    internalState.update { it.copy(error = null) }
  }
}

data class FeedProfileState(
  val loading: Boolean = true,
  val loadFailed: Boolean = false,
  /** null = 还没建过主页。 */
  val profile: FeedMyProfile? = null,
  val name: String = "",
  val bio: String = "",
  val saving: Boolean = false,
  /** 保存成功，可以关页面了。 */
  val saved: Boolean = false,
  val error: Error? = null,
  /** 昵称 / 简介刚才碰到了上限（拦下或截了），计数可以抖一下；下一次正常输入清掉。 */
  val nameLimitHit: Boolean = false,
  val bioLimitHit: Boolean = false
) {
  /** 还能再输入几个码点（计数显示用）；超了（组字中）是负数。 */
  val nameRemaining: Int
    get() = FeedInputLimit.remaining(name, FeedInputLimit.PROFILE_NAME)

  val bioRemaining: Int
    get() = FeedInputLimit.remaining(bio, FeedInputLimit.PROFILE_BIO)

  val isNew: Boolean
    get() = profile == null

  val isChanged: Boolean
    get() = profile == null || name.trim() != profile.displayName || bio.trim() != profile.bio.orEmpty()

  val canSave: Boolean
    get() = !loading && !loadFailed && !saving && name.isNotBlank() && nameRemaining >= 0 && bioRemaining >= 0 && isChanged

  enum class Error {
    /** 引擎说名字或简介不合规（空名字、超长）。 */
    INVALID,
    FAILED
  }
}
