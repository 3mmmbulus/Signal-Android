/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.compose.ComposeBottomSheetDialogFragment
import org.signal.core.ui.compose.LocalFragmentManager
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.getParcelableCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.TellomiFeatureIntro
import org.thoughtcrime.securesms.components.TellomiFeatureIntroBottomSheet
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.safety.SafetyNumberBottomSheet
import org.thoughtcrime.securesms.util.CommunicationActions
import org.thoughtcrime.securesms.util.fragments.findListener
import org.thoughtcrime.securesms.util.viewModel
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog

/**
 * 转发面板：Telegram 式头像网格（tellomi/tellomi#1259，需求 `docs/product/specs/media-album-forward-picker.md` 第二节）。
 *
 * 长按「转发」、多选「转发」、查看器「转发」都打开它（F-1）。底部 sheet + 拖动条，一开始露出约 3/5 屏，上拉展开（F-2）；
 * 深浅色跟宿主：查看器 Activity 固定夜间模式（上游 MediaPreviewActivity），从查看器打开就是深色。选中后底部出现附言 + 带数量的「发送」（与上游一样挂在 sheet 外、屏幕底部，sheet 收着时也看得见）。
 * 发送与上游转发面板同一条路（安全码确认、[MultiselectForwardRepository.send]），宿主仍用 [MultiselectForwardBottomSheet.Callback]。
 */
class TellomiForwardGridBottomSheet :
  ComposeBottomSheetDialogFragment(),
  SafetyNumberBottomSheet.Callbacks,
  TellomiForwardGridCallbacks {

  companion object {
    private val TAG = Log.tag(TellomiForwardGridBottomSheet::class.java)

    @JvmStatic
    fun show(fragmentManager: FragmentManager, args: MultiselectForwardFragmentArgs) {
      TellomiForwardGridBottomSheet().apply {
        arguments = bundleOf(MultiselectForwardFragment.ARGS to args.copy(isWrappedInBottomSheet = true))
      }.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }
  }

  override val peekHeightPercentage: Float = TellomiForwardGridMetrics.PEEK_HEIGHT_FRACTION

  private val args: MultiselectForwardFragmentArgs by lazy {
    requireArguments().getParcelableCompat(MultiselectForwardFragment.ARGS, MultiselectForwardFragmentArgs::class.java)!!
  }

  private val viewModel: TellomiForwardGridViewModel by viewModel {
    TellomiForwardGridViewModel(args.multiShareArgs, TellomiForwardTargetsRepository(requireContext().applicationContext))
  }

  private var bottomBarHeightPx by mutableIntStateOf(0)
  private var progressDialog: SimpleProgressDialog.DismissibleDialog? = null
  private var handledStage: TellomiForwardGridViewModel.Stage? = null

  /**
   * 同基类，只是 Surface 不再按键盘高度留白：BottomSheetBehavior 的 paddingBottomSystemWindowInsets 用的是
   * getSystemWindowInsetBottom（含键盘），sheet 底部已经垫到键盘顶上；基类再扣一次键盘高度，
   * 这张铺满整屏的 sheet 在搜索态只剩 529 px（实测：键盘 1008 − 导航条 72 = 多扣的 936 px），结果格子被裁掉。
   */
  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val isDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        CompositionLocalProvider(LocalFragmentManager provides childFragmentManager) {
          SignalTheme(isDarkMode = isDark) {
            Surface(
              shape = RoundedCornerShape(cornerRadius.dp, cornerRadius.dp),
              color = SignalTheme.colors.colorSurface1,
              contentColor = MaterialTheme.colorScheme.onSurface
            ) {
              SheetContent()
            }
          }
        }
      }
    }
  }

  @Composable
  override fun SheetContent() {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bottomPadding = with(LocalDensity.current) { (if (state.selected.isEmpty()) 0 else bottomBarHeightPx).toDp() }
    TellomiForwardGridContent(
      state = state,
      canShare = TellomiForwardShare.canShare(args.multiShareArgs),
      callbacks = this,
      bottomContentPadding = bottomPadding
    )
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    // 内容高 = 整屏：一开始露出 3/5（peek），上拉展开到顶
    view.minimumHeight = resources.displayMetrics.heightPixels
    addBottomBar(view)

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          viewModel.events.collect { event ->
            when (event) {
              is TellomiForwardGridViewModel.Event.SelectionLimitReached -> {
                Toast.makeText(requireContext(), getString(R.string.TellomiForwardGrid__select_up_to_d_chats, event.limit), Toast.LENGTH_SHORT).show()
              }
            }
          }
        }
        viewModel.state.collect { state -> onStage(state.stage) }
      }
    }

    // 新功能提示卡「转发给多个聊天」（docs/product/specs/whats-new-cards.md），只在第一次打开时弹。
    if (savedInstanceState == null) {
      view.post {
        if (isAdded && !childFragmentManager.isStateSaved) {
          TellomiFeatureIntroBottomSheet.showFirstDue(childFragmentManager, listOf(TellomiFeatureIntro.FORWARD_GRID))
        }
      }
    }
  }

  /** 附言 + 发送挂在对话框的 CoordinatorLayout 底部（同上游转发面板的底栏），sheet 收着时也在屏幕底部 */
  private fun addBottomBar(view: View) {
    var container: View? = view.parent as? View
    while (container != null && container !is CoordinatorLayout) {
      container = container.parent as? View
    }
    val coordinator = container as? CoordinatorLayout ?: return
    val bar = ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      layoutParams = CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
      setContent {
        val state by viewModel.state.collectAsStateWithLifecycle()
        if (state.selected.isNotEmpty()) {
          SignalTheme(isDarkMode = resources.configuration.isNightMode()) {
            Surface(color = SignalTheme.colors.colorSurface1) {
              TellomiForwardGridBottomBar(
                message = state.message,
                selectedCount = state.selected.size,
                isSendEnabled = state.stage == TellomiForwardGridViewModel.Stage.Selection,
                onMessageChanged = viewModel::setMessage,
                onSend = viewModel::send,
                modifier = Modifier
                  .fillMaxWidth()
                  .imePadding()
                  .navigationBarsPadding()
                  .onSizeChanged { bottomBarHeightPx = it.height }
              )
            }
          }
        }
      }
    }
    coordinator.addView(bar)
  }

  private fun android.content.res.Configuration.isNightMode(): Boolean {
    return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
  }

  private fun onStage(stage: TellomiForwardGridViewModel.Stage) {
    if (stage == handledStage) {
      return
    }
    handledStage = stage
    Log.d(TAG, "Stage: ${stage.javaClass.simpleName}")
    when (stage) {
      TellomiForwardGridViewModel.Stage.Selection,
      TellomiForwardGridViewModel.Stage.LoadingIdentities -> {
        progressDialog?.dismiss()
      }

      is TellomiForwardGridViewModel.Stage.SafetyConfirmation -> {
        SafetyNumberBottomSheet
          .forIdentityRecordsAndDestinations(stage.identities, stage.destinations)
          .show(childFragmentManager)
      }

      TellomiForwardGridViewModel.Stage.SendPending -> {
        progressDialog?.dismiss()
        progressDialog = SimpleProgressDialog.showDelayed(requireContext())
      }

      is TellomiForwardGridViewModel.Stage.Sent -> onSent(stage)
    }
  }

  private fun onSent(stage: TellomiForwardGridViewModel.Stage.Sent) {
    progressDialog?.dismiss()
    val context = requireContext()
    val host = findListener<MultiselectForwardBottomSheet.Callback>()
    host?.onFinishForwardAction()

    if (stage.result == TellomiForwardGridViewModel.Result.ALL_FAILED) {
      val count = args.multiShareArgs.size
      Toast.makeText(context, resources.getQuantityString(R.plurals.MultiselectForwardFragment_messages_failed_to_send, count), Toast.LENGTH_SHORT).show()
      dismissAllowingStateLoss()
      return
    }

    setFragmentResult(MultiselectForwardFragment.RESULT_KEY, bundleOf(MultiselectForwardFragment.RESULT_SENT to true))

    if (stage.result == TellomiForwardGridViewModel.Result.SOME_FAILED) {
      // 有的聊天没发出去：不震、不点名（名单里有没发出去的），用上游分享用的「无法发送给某些用户」
      Toast.makeText(context, R.string.MultiShareDialogs__failed_to_send_to_some_users, Toast.LENGTH_SHORT).show()
      dismissAllowingStateLoss()
      return
    }

    // F-8：面板收起，成功轻震一下，提示约 3 秒写明转给了谁；只转到「我的收藏」时可以直接打开它
    val anchor: View? = activity?.findViewById(android.R.id.content)
    val toast = TellomiForwardedToast.build(context, stage.recipients)
    if (anchor != null && toast != null) {
      anchor.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
      val snackbar = Snackbar.make(anchor, toast.text, TellomiForwardedToast.DURATION_MS)
      if (toast.opensSavedMessages) {
        snackbar.setAction(R.string.ConversationFragment__tellomi_view_saved_messages) {
          CommunicationActions.startConversation(anchor.context, Recipient.self(), null)
        }
        snackbar.view.setOnClickListener {
          snackbar.dismiss()
          CommunicationActions.startConversation(anchor.context, Recipient.self(), null)
        }
      }
      snackbar.show()
    }
    dismissAllowingStateLoss()
  }

  override fun onDismiss(dialog: DialogInterface) {
    super.onDismiss(dialog)
    findListener<MultiselectForwardBottomSheet.Callback>()?.onDismissForwardSheet()
  }

  override fun onDestroyView() {
    progressDialog?.dismissNow()
    super.onDestroyView()
  }

  // region TellomiForwardGridCallbacks

  override fun onTargetClicked(target: TellomiForwardTarget, fromSearch: Boolean) {
    viewModel.onTargetClicked(target, fromSearch)
  }

  override fun onQueryChanged(query: String) {
    viewModel.setQuery(query)
  }

  override fun onSearchCancelled() {
    viewModel.setSearchActive(false)
  }

  override fun onSearchFocusChanged(focused: Boolean) {
    if (focused) {
      viewModel.setSearchActive(true)
      (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    } else if (viewModel.state.value.query.isBlank()) {
      viewModel.setSearchActive(false)
    }
  }

  override fun onShareClicked() {
    val intent = TellomiForwardShare.createChooser(requireContext(), args.multiShareArgs) ?: return
    try {
      startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "No activity existed to share the forwarded content.", e)
      Toast.makeText(requireContext(), R.string.MediaPreviewActivity_cant_find_an_app_able_to_share_this_media, Toast.LENGTH_LONG).show()
    }
  }

  // endregion

  // region SafetyNumberBottomSheet.Callbacks

  override fun sendAnywayAfterSafetyNumberChangedInBottomSheet(destinations: List<ContactSearchKey.RecipientSearchKey>) {
    viewModel.confirmSafetySend(destinations)
  }

  override fun onMessageResentAfterSafetyNumberChangeInBottomSheet() {
    throw UnsupportedOperationException()
  }

  override fun onCanceled() {
    viewModel.cancelSend()
  }

  // endregion
}
