/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediapreview

import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import org.thoughtcrime.securesms.components.ThumbnailView
import java.lang.ref.WeakReference

/**
 * Tellomi（交互审计 A-20 Android 余项「翻页后重映射到当前那张的缩略图」）：查看器翻到别的一张再关时，缩回聊天里**那一张**的缩略图，
 * 而不是点开的那张（Telegram 两端都是：安卓 `PhotoViewer.closePhoto` 问 `placeProvider.getPlaceForPhoto(当前这张)`，
 * iOS `GalleryController` 拿当前页的消息问 `transitionArguments`）。
 *
 * 聊天页打开查看器前 [register] 一个查找函数（媒体 uri → 屏幕上看得见的那个缩略图）；查看器关的时候用 [find] 问当前这张在不在屏幕上，
 * 在就 [returnTo] 记下、走共享元素返回，聊天页 Activity 的 [ExitCallback] 在返回时把共享元素换到那个缩略图上；不在就照旧飞出淡出。
 * 查看器打开时 [claim]：只认聊天页刚登记的那一次，别的入口（全部媒体页）打开的查看器不会误用聊天页的查找。
 */
object TellomiMediaReturnTarget {

  /** 登记之后多久内打开的查看器算「这一次」。 */
  private const val CLAIM_WINDOW_MS = 2000L

  private var finder: WeakReference<(Uri) -> ThumbnailView?>? = null
  private var pendingReturn: Uri? = null
  private var registeredAt = 0L
  private var claimed = true

  /** 聊天页打开查看器前调。[finder] 由调用方自己持有（这里只留弱引用，页面没了就找不到）。 */
  fun register(finder: (Uri) -> ThumbnailView?) {
    this.finder = WeakReference(finder)
    pendingReturn = null
    registeredAt = SystemClock.uptimeMillis()
    claimed = false
  }

  /** 查看器第一次创建时调：这次打开是不是聊天页刚登记的那一次（[CLAIM_WINDOW_MS] 内、还没被认领过）。 */
  fun claim(): Boolean {
    val fresh = !claimed && finder?.get() != null && SystemClock.uptimeMillis() - registeredAt < CLAIM_WINDOW_MS
    claimed = true
    return fresh
  }

  /** [uri] 的缩略图此刻在聊天里看得见、而且画面已经加载出来，就返回它。 */
  fun find(uri: Uri): ThumbnailView? {
    return finder?.get()?.invoke(uri)?.takeIf { it.isMostlyOnScreen() && it.imageDrawable != null }
  }

  /** 查看器决定缩回 [uri] 的缩略图：聊天页返回时按它重新对应共享元素。 */
  fun returnTo(uri: Uri) {
    pendingReturn = uri
  }

  /**
   * 至少一半露在窗口里才算看得见：缩回一个大半在屏幕外的缩略图，看起来像飞出了屏幕。
   */
  private fun View.isMostlyOnScreen(): Boolean {
    if (!isAttachedToWindow || !isShown || width == 0 || height == 0) {
      return false
    }
    val visible = Rect()
    if (!getGlobalVisibleRect(visible)) {
      return false
    }
    return visible.width() * visible.height() * 2 >= width * height
  }

  /**
   * 聊天页 Activity 的共享元素回调：返回时如果查看器记下了别的一张，就把 [MediaPreviewActivity.SHARED_ELEMENT_TRANSITION_NAME]
   * 对应到那张的缩略图上。要在 super 之前换——Material 的回调在 [onMapSharedElements] 里记下共享元素，后面算形状用它。
   */
  class ExitCallback(launchedFrom: View) : MaterialContainerTransformSharedElementCallback() {
    private val launchedFrom = WeakReference(launchedFrom)

    override fun onMapSharedElements(names: MutableList<String>, sharedElements: MutableMap<String, View>) {
      // 不在这里清掉：返回途中可能对应不止一次；下一次登记时才清。
      val target = pendingReturn?.let { find(it) }
      if (target != null) {
        launchedFrom.get()?.takeIf { it !== target }?.transitionName = null
        target.transitionName = MediaPreviewActivity.SHARED_ELEMENT_TRANSITION_NAME
        sharedElements[MediaPreviewActivity.SHARED_ELEMENT_TRANSITION_NAME] = target
      }
      super.onMapSharedElements(names, sharedElements)
    }
  }
}
