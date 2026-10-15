/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import org.signal.core.ui.compose.Buttons
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.horizontalGutters
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.signal.core.ui.R as CoreUiR
import org.signal.mediasend.R as MediaSendR

/**
 * Tellomi（`docs/product/specs/whats-new-cards.md`，owner 2026-10-16 发来的文案）：新功能提示卡。同 iOS `TellomiFeatureIntro`：
 * - 第一次进入对应界面时弹一次底部卡片（图标、标题、每条「图标 + 一句话」、「知道了」），可以下滑划走；
 * - 一次只弹一张，按候选顺序；弹出来就记已读（划走、点按钮都算），记在本机 `SignalStore.uiHints`；
 * - 新装用户不弹：只给在这张卡出来之前注册的人（[introducedAtMillis]，用 `SignalStore.account.registeredAtTimestamp` 判断）。
 *
 * 安卓只有卡 2（选图）、卡 3（转发）。卡 1「新版附件面板」的第二条「位置、投票、联系人在面板里直接完成」在安卓还不成立：
 * 投票、联系人（2026-10-16）已在 Sheet 里，位置还等高德 #1124，位置页进了 Sheet 再加。
 */
enum class TellomiFeatureIntro(
  val id: String,
  @DrawableRes val icon: Int,
  @StringRes val title: Int,
  val items: List<Pair<Int, Int>>
) {
  PHOTO_PICKER(
    id = "photo_picker_v2",
    icon = CoreUiR.drawable.symbol_photo_24,
    title = R.string.TellomiFeatureIntro__photo_picker_title,
    items = listOf(
      R.drawable.symbol_number_24 to R.string.TellomiFeatureIntro__photo_picker_item_order,
      R.drawable.symbol_calendar_24 to R.string.TellomiFeatureIntro__photo_picker_item_schedule,
      MediaSendR.drawable.tellomi_adjust_24 to R.string.TellomiFeatureIntro__photo_picker_item_edit
    )
  ),
  FORWARD_GRID(
    id = "forward_grid_v1",
    icon = CoreUiR.drawable.symbol_forward_24,
    title = R.string.TellomiFeatureIntro__forward_grid_title,
    items = listOf(
      R.drawable.symbol_group_24 to R.string.TellomiFeatureIntro__forward_grid_item_many
    )
  );

  /** 这张卡管谁：在这之前注册的老用户。都是 2026-10-17 00:00（北京时间）——随这一版发出去；同 iOS。 */
  val introducedAtMillis: Long get() = 1_792_166_400_000L

  companion object {
    /** 该不该弹：没读过，并且是在这张卡出来之前注册的。还没注册（`registeredAtTimestamp` 为 -1）的不弹。 */
    @JvmStatic
    fun shouldShow(intro: TellomiFeatureIntro, registeredAtMillis: Long, hasSeen: Boolean): Boolean {
      return !hasSeen && registeredAtMillis > 0 && registeredAtMillis < intro.introducedAtMillis
    }

    /** [candidates] 里第一张该弹的。 */
    @JvmStatic
    fun firstDue(candidates: List<TellomiFeatureIntro>, registeredAtMillis: Long, hasSeen: (TellomiFeatureIntro) -> Boolean): TellomiFeatureIntro? {
      return candidates.firstOrNull { shouldShow(it, registeredAtMillis, hasSeen(it)) }
    }

    fun fromId(id: String?): TellomiFeatureIntro? = entries.firstOrNull { it.id == id }
  }
}

/** 卡片本身。 */
class TellomiFeatureIntroBottomSheet : ComposeBottomSheetDialogFragment() {

  companion object {
    private val TAG = Log.tag(TellomiFeatureIntroBottomSheet::class.java)
    private const val FRAGMENT_TAG = "tellomi_feature_intro"
    private const val ARG_ID = "intro_id"

    /**
     * 触发界面调：[candidates] 里第一张该弹的弹出来，弹出即记已读。已经有一张开着、或状态已保存（快退到后台）时不弹，下次再说。
     * 返回弹了哪张。
     */
    @JvmStatic
    fun showFirstDue(fragmentManager: FragmentManager, candidates: List<TellomiFeatureIntro>): TellomiFeatureIntro? {
      if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(FRAGMENT_TAG) != null) {
        return null
      }
      val due = TellomiFeatureIntro.firstDue(candidates, SignalStore.account.registeredAtTimestamp) {
        SignalStore.uiHints.hasSeenTellomiFeatureIntro(it.id)
      } ?: return null
      Log.i(TAG, "Showing feature intro ${due.id}")
      SignalStore.uiHints.markTellomiFeatureIntroSeen(due.id)
      TellomiFeatureIntroBottomSheet().apply { arguments = bundleOf(ARG_ID to due.id) }.show(fragmentManager, FRAGMENT_TAG)
      return due
    }
  }

  private val intro: TellomiFeatureIntro? by lazy { TellomiFeatureIntro.fromId(requireArguments().getString(ARG_ID)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (intro == null) {
      dismissAllowingStateLoss()
    }
  }

  @Composable
  override fun SheetContent() {
    intro?.let { FeatureIntroContent(it, onDone = this::dismissAllowingStateLoss) }
  }
}

@Composable
private fun FeatureIntroContent(intro: TellomiFeatureIntro, onDone: () -> Unit = {}) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .horizontalGutters()
      .padding(top = 24.dp, bottom = 40.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(64.dp)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
    ) {
      Icon(
        painter = painterResource(intro.icon),
        tint = MaterialTheme.colorScheme.primary,
        contentDescription = null,
        modifier = Modifier.size(32.dp)
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = stringResource(intro.title),
      style = MaterialTheme.typography.titleLarge,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
      intro.items.forEach { (icon, text) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            painter = painterResource(icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(16.dp))
          Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyLarge
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Buttons.LargeTonal(
      onClick = onDone,
      modifier = Modifier.widthIn(min = 220.dp)
    ) {
      Text(text = stringResource(R.string.TellomiFeatureIntro__got_it))
    }
  }
}

@DayNightPreviews
@Composable
private fun FeatureIntroPhotoPickerPreview() {
  Previews.Preview {
    FeatureIntroContent(TellomiFeatureIntro.PHOTO_PICKER)
  }
}

@DayNightPreviews
@Composable
private fun FeatureIntroForwardGridPreview() {
  Previews.Preview {
    FeatureIntroContent(TellomiFeatureIntro.FORWARD_GRID)
  }
}
