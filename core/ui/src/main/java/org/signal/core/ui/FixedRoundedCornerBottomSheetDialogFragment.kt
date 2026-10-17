/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.core.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import androidx.annotation.ColorInt
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.signal.core.ui.util.ThemeUtil
import org.signal.core.util.DimensionUnit
import com.google.android.material.R as MaterialR

/**
 * Forces rounded corners on BottomSheet.
 *
 * Expects [R.attr.fixedRoundedCornerBottomSheetStyle] to be defined in your app theme, pointing to a
 * style that extends [Widget.CoreUi.FixedRoundedCorners]. Subclasses can override [themeResId] to
 * use an alternate style.
 */
abstract class FixedRoundedCornerBottomSheetDialogFragment : BottomSheetDialogFragment() {

  /**
   * Sheet corner radius in DP
   */
  protected open val cornerRadius: Int
    get() = if (resources.isSplitPane()) {
      32
    } else {
      18
    }

  protected open val peekHeightPercentage: Float = 0.5f

  protected open val themeResId: Int
    get() = ThemeUtil.getThemedResourceId(requireContext(), R.attr.fixedRoundedCornerBottomSheetStyle)

  @ColorInt
  protected var backgroundColor: Int = Color.TRANSPARENT

  private lateinit var dialogBackground: MaterialShapeDrawable

  /** Tellomi（交互审计 A-29）：主题给的遮罩深度，拖到收起以下时按它变淡。 */
  private var baseDimAmount: Float? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setStyle(STYLE_NORMAL, themeResId)
  }

  override fun onResume() {
    super.onResume()
    dialog?.window?.initializeScreenshotSecurity()
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

    dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * peekHeightPercentage).toInt()

    val cornerRadiusPx = DimensionUnit.DP.toPixels(cornerRadius.toFloat())
    val shapeAppearanceModel = ShapeAppearanceModel.builder()
      .setTopLeftCorner(CornerFamily.ROUNDED, cornerRadiusPx)
      .setTopRightCorner(CornerFamily.ROUNDED, cornerRadiusPx)
      .build()

    dialogBackground = MaterialShapeDrawable(shapeAppearanceModel)

    val bottomSheetStyle = ThemeUtil.getThemedResourceId(ContextThemeWrapper(requireContext(), themeResId), MaterialR.attr.bottomSheetStyle)
    backgroundColor = ThemeUtil.getThemedColor(ContextThemeWrapper(requireContext(), bottomSheetStyle), MaterialR.attr.backgroundTint)
    dialogBackground.fillColor = ColorStateList.valueOf(backgroundColor)

    dialog.behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
      // Tellomi（交互审计 A-29 / Q-07，照 Telegram 两端）：拖着的时候遮罩不变；松手往下关掉时从松手的位置起跟着淡。
      private var lastOffset = 0f
      private var settleStartOffset: Float? = null

      override fun onStateChanged(bottomSheet: View, newState: Int) {
        if (bottomSheet.background !== dialogBackground) {
          bottomSheet.background = dialogBackground
        }
        settleStartOffset = if (newState == BottomSheetBehavior.STATE_SETTLING) lastOffset else null
        if (newState != BottomSheetBehavior.STATE_SETTLING && newState != BottomSheetBehavior.STATE_HIDDEN) {
          applyDim(1f)
        }
      }

      override fun onSlide(bottomSheet: View, slideOffset: Float) {
        lastOffset = slideOffset
        applyDim(TellomiSheetScrim.dimFraction(slideOffset, settleStartOffset))
      }

      private fun applyDim(fraction: Float) {
        val window = dialog.window ?: return
        val base = baseDimAmount ?: window.attributes.dimAmount.also { baseDimAmount = it }
        val dim = base * fraction
        if (window.attributes.dimAmount != dim) {
          window.setDimAmount(dim)
        }
      }
    })

    return dialog
  }
}
