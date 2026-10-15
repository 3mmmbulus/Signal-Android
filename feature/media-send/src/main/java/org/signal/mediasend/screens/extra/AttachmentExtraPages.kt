/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.mediasend.screens.extra

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.signal.core.ui.compose.SignalIcons
import org.signal.mediasend.MediaSendDependencies
import org.signal.mediasend.MediaSendFlowActivityContract.AttachmentPage
import org.signal.mediasend.MediaSendFlowActivityContract.DockEntry
import org.signal.mediasend.MediaSendFlowEvent
import org.signal.mediasend.R
import org.signal.mediasend.ToastEvent
import org.signal.mediasend.ToastMessage
import org.signal.mediasend.screens.select.AttachmentDock
import org.signal.mediasend.screens.select.LocalAttachmentSheetState
import org.signal.mediasend.screens.select.PickerMetrics
import org.signal.mediasend.screens.select.RoundIconButton

/**
 * Tellomi（2026-10-16）：附件 Sheet 里由 app 画的页（投票……）。feature 模块不能依赖 app，所以页面内容由 app 注入
 * （[MediaSendDependencies.attachmentExtraPages]），Sheet、换页、dock 仍由这里管。
 *
 * 照 Telegram 两端：附件菜单里每一格都是 Sheet 里的一页（安卓 `ChatAttachAlert.showLayout(pollLayout)`，
 * iOS `ChatControllerOpenAttachmentMenu` 的 `requestController` 把 `.poll` 页换进 Sheet）；Sheet 不收、dock 不动，
 * 进来展开到全屏；键盘出来时 dock 收起；✕ / 发完收起整个 Sheet。
 */
interface AttachmentExtraPages {

  /**
   * 画 [page] 这一页。
   *
   * @param recipientId 这个会话的对方（`RecipientId` 的数值），没有时是 null；联系人页用它画「发给谁」和会话颜色。
   * @param onClose 收起整个 Sheet（✕）。
   * @param onResult 做完了（例如投票发出），[Bundle] 原样交给会话页，Sheet 收起。
   */
  @Composable
  fun Content(page: AttachmentPage, recipientId: Long?, onClose: () -> Unit, onResult: (Bundle) -> Unit, modifier: Modifier)
}

/**
 * app 画的页里点 dock 的另一格：置灰的只提示；Sheet 里的页（相册、文件、投票）就地换过去；别的交给会话页。
 * 和相册页（`MediaSelectViewModel.onDockEntryClicked`）、文件页同一套规则。
 */
internal fun dockEventFor(entry: DockEntry): MediaSendFlowEvent {
  val comingSoon = entry.comingSoonMessage
  val page = entry.page
  return when {
    comingSoon != null -> MediaSendFlowEvent.ShowToast(ToastEvent(SignalIcons.Info, ToastMessage.Text(comingSoon)))
    page != null -> MediaSendFlowEvent.OpenAttachmentPage(page)
    else -> MediaSendFlowEvent.AttachmentDockEntrySelected(entry.id)
  }
}

/** Sheet 里 app 画的页 + 底部 dock（键盘出来时不显示）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentExtraPageHost(
  page: AttachmentPage,
  recipientId: Long?,
  dock: List<DockEntry>,
  onDockClick: (DockEntry) -> Unit,
  onClose: () -> Unit,
  onResult: (Bundle) -> Unit
) {
  val sheet = LocalAttachmentSheetState.current
  val scope = rememberCoroutineScope()

  LaunchedEffect(page) {
    sheet?.expand()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
  ) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      MediaSendDependencies.attachmentExtraPages.Content(page = page, recipientId = recipientId, onClose = onClose, onResult = onResult, modifier = Modifier.fillMaxSize())
    }

    if (dock.isNotEmpty() && !WindowInsets.isImeVisible) {
      AttachmentDock(
        entries = dock,
        currentPage = page,
        onClick = { entry ->
          if (entry.page == page) {
            // 重复点当前格：展开到全屏（同另外两页）
            scope.launch { sheet?.expand() }
          } else {
            onDockClick(entry)
          }
        },
        // 和另外两页同一个高度：它们的 dock 在 Scaffold 里，带着导航栏的留白
        modifier = Modifier.navigationBarsPadding()
      )
    }
  }
}

/**
 * app 画的页的顶栏：和「文件」页同一个样子（左边圆形 ✕、标题居中、同高），同一个 Sheet 里换页时顶栏不跳。
 */
@Composable
fun AttachmentSheetPageTopBar(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .height(PickerMetrics.topBarHeight)
      .padding(horizontal = 8.dp)
  ) {
    Box(modifier = Modifier.align(Alignment.CenterStart)) {
      RoundIconButton(
        icon = SignalIcons.X,
        contentDescription = stringResource(R.string.MediaSelectScreen__close),
        onClick = onClose
      )
    }

    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.align(Alignment.Center)
    )
  }
}
