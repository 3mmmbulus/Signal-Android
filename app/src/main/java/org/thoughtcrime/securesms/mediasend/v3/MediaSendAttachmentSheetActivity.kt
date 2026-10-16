/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediasend.v3

import android.os.Build
import androidx.lifecycle.Lifecycle
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.TellomiFeatureIntro
import org.thoughtcrime.securesms.components.TellomiFeatureIntroBottomSheet
import org.thoughtcrime.securesms.util.DynamicTheme

/**
 * Tellomi（tellomi/tellomi#1115）：会话页「+」打开的附件 Sheet。和 [MediaSendV3Activity] 同一个流程，只是窗口透明
 * （聊天露在 Sheet 上面），所以单独一个 Activity——窗口透不透明只能在清单的主题里定。
 *
 * 进场由 Sheet 自己从底部滑上来（会话页启动时窗口不做动画）；退场整个窗口往下滑出、淡出，和会话页插入新消息同时发生。
 */
class MediaSendAttachmentSheetActivity : MediaSendV3Activity() {

  companion object {
    /** 等 Sheet 从底部滑上来再弹提示卡。 */
    private const val FEATURE_INTRO_DELAY_MS = 400L
  }

  private var featureIntroChecked = false

  override fun createDynamicTheme(): DynamicTheme = AttachmentSheetTheme()

  // 两端对齐清单 P-4：相册页底栏的说明框就地输入，同 Telegram 两端、iOS
  override val tellomiUsesInlineCaption: Boolean = true

  override fun onResume() {
    super.onResume()
    // 新功能提示卡（docs/product/specs/whats-new-cards.md）：第一次打开面板（默认是相册页）时弹「选图」。
    // 安卓没有卡 1「附件面板」：位置、联系人还没进面板。
    if (!featureIntroChecked) {
      featureIntroChecked = true
      window.decorView.postDelayed({
        if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
          TellomiFeatureIntroBottomSheet.showFirstDue(supportFragmentManager, listOf(TellomiFeatureIntro.PHOTO_PICKER))
        }
      }, FEATURE_INTRO_DELAY_MS)
    }
  }

  override fun finish() {
    super.finish()
    @Suppress("DEPRECATION")
    overridePendingTransition(0, R.anim.tellomi_attachment_sheet_exit)
  }

  override fun setRequestedOrientation(requestedOrientation: Int) {
    // Android 8.0 不许透明窗口固定方向（Only fullscreen opaque activities can request orientation）
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
      return
    }
    super.setRequestedOrientation(requestedOrientation)
  }

  private class AttachmentSheetTheme : DynamicTheme() {
    override fun getTheme(): Int = R.style.Signal_DayNight_NoActionBar_AttachmentSheet
  }
}
