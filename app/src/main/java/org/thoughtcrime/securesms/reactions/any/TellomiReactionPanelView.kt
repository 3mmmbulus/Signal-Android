/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.reactions.any

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.signal.emoji.EmojiEventListener
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.emoji.EmojiPageView
import org.thoughtcrime.securesms.components.emoji.EmojiPageViewGridAdapter
import org.thoughtcrime.securesms.keyboard.KeyboardPageCategoryIconMappingModel
import org.thoughtcrime.securesms.keyboard.emoji.EmojiKeyboardPageCategoriesAdapter
import org.thoughtcrime.securesms.keyboard.emoji.KeyboardPageSearchView
import org.thoughtcrime.securesms.reactions.ReactionsRepository
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel

/**
 * Tellomi（交互审计 A-06，owner 2026-10-17「和 Telegram 还不一样」）：长按菜单里点 ⌄（或按住 ⌄ 往开头拉）时，回应条就地长成的表情面板。
 *
 * Telegram 两端都是在原处长出面板（安卓 `CustomEmojiReactionsWindow`），不另弹 Sheet。里面的东西和上游的
 * 「用任意表情回应」Sheet（[ReactWithAnyEmojiBottomSheetDialogFragment]）一样：搜索、按分类的 8 列表情、分类条和「自定义回应」，
 * 数据也用同一个 [ReactWithAnyEmojiViewModel]；只是不在这里发回应——选中的表情交回长按浮层，走和条上表情同一条路（飞入、读屏播报、发送）。
 *
 * @param onSelected 选中的表情，和它在窗口里的位置（给回应飞入；拿不到是 null）。
 * @param onConfigure 点了「自定义回应」。
 */
@SuppressLint("ViewConstructor")
class TellomiReactionPanelView(
  context: Context,
  messageId: Long,
  isMms: Boolean,
  private val onSelected: (String, RectF?) -> Unit,
  private val onConfigure: () -> Unit
) : LinearLayout(context), EmojiEventListener, EmojiPageViewGridAdapter.VariationSelectorListener {

  private val viewModel: ReactWithAnyEmojiViewModel = ReactWithAnyEmojiViewModel.Factory(
    ReactionsRepository(),
    ReactWithAnyEmojiRepository(context, ReactWithAnyEmojiBottomSheetDialogFragment.REACTION_STORAGE_KEY),
    messageId,
    isMms
  ).create(ReactWithAnyEmojiViewModel::class.java)

  private val disposables = CompositeDisposable()

  private val search: KeyboardPageSearchView
  private val grid: TellomiReactionPanelGrid
  private val tabs: View
  private val categoriesRecycler: RecyclerView
  private val categoriesAdapter: EmojiKeyboardPageCategoriesAdapter

  /** Where the last press in the grid landed, to find the cell an emoji was picked from. */
  private val lastTouch = PointF()

  /** Off while a tap on a category scrolls the grid, so the scroll does not move the selection back on the way. */
  private var followScroll = true

  init {
    orientation = VERTICAL
    inflate(context, R.layout.tellomi_reaction_panel, this)

    search = findViewById(R.id.tellomi_reaction_panel_search)
    grid = findViewById(R.id.tellomi_reaction_panel_grid)
    tabs = findViewById(R.id.tellomi_reaction_panel_tabs)
    categoriesRecycler = findViewById(R.id.tellomi_reaction_panel_categories)

    grid.initialize(this, this, true)
    grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
      override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) {
          lastTouch.set(e.x, e.y)
        }
        return false
      }
    })
    grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
      override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        if (newState == RecyclerView.SCROLL_STATE_IDLE && !followScroll) {
          followScroll = true
          onScrolled(recyclerView, 0, 0)
        }
      }

      override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val adapter = grid.adapter ?: return
        if (!followScroll) {
          return
        }
        val model: MappingModel<*>? = adapter.getModel(layoutManager.findFirstCompletelyVisibleItemPosition()).orElse(null)
        if (model is EmojiPageViewGridAdapter.HasKey) {
          viewModel.selectPage(model.key)
        }
      }
    })

    categoriesAdapter = EmojiKeyboardPageCategoriesAdapter { key ->
      scrollTo(key)
      viewModel.selectPage(key)
    }
    categoriesRecycler.adapter = categoriesAdapter

    findViewById<View>(R.id.tellomi_reaction_panel_customize).setOnClickListener { onConfigure() }

    search.callbacks = object : KeyboardPageSearchView.Callbacks {
      override fun onQueryChanged(query: String) {
        val hasQuery = query.isNotEmpty()
        search.enableBackNavigation(hasQuery)
        if (hasQuery) {
          ViewUtil.fadeOut(tabs, 250, View.INVISIBLE)
        } else {
          ViewUtil.fadeIn(tabs, 250)
        }
        viewModel.onQueryChanged(query)
      }

      override fun onNavigationClicked() {
        search.clearQuery()
        search.clearFocus()
        ViewUtil.hideKeyboard(context, this@TellomiReactionPanelView)
      }
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    disposables += viewModel.emojiList.subscribe { pages -> grid.setList(pages, null) }
    disposables += viewModel.categories.subscribe { categoriesAdapter.submitList(it) }
    disposables += viewModel.selectedKey.subscribe { key ->
      categoriesRecycler.post {
        val index = categoriesAdapter.indexOfFirst(KeyboardPageCategoryIconMappingModel::class.java) { it.key == key }
        if (index != -1) {
          categoriesRecycler.smoothScrollToPosition(index)
        }
      }
    }
  }

  override fun onDetachedFromWindow() {
    disposables.clear()
    super.onDetachedFromWindow()
  }

  private fun scrollTo(key: String) {
    val adapter = grid.adapter ?: return
    val index = adapter.indexOfFirst(EmojiPageViewGridAdapter.EmojiHeader::class.java) { it.key == key }
    if (index != -1) {
      followScroll = false
      grid.smoothScrollToPositionTop(index)
    }
  }

  override fun onEmojiSelected(emoji: String) {
    val cell = grid.findChildViewUnder(lastTouch.x, lastTouch.y)?.findViewById<View>(R.id.emoji_image)
    val source = cell?.takeIf { it.isAttachedToWindow && it.width > 0 }?.let {
      val location = IntArray(2).also { array -> it.getLocationInWindow(array) }
      RectF(location[0].toFloat(), location[1].toFloat(), (location[0] + it.width).toFloat(), (location[1] + it.height).toFloat())
    }
    onSelected(emoji, source)
  }

  override fun onKeyEvent(keyEvent: KeyEvent?) = Unit

  override fun onVariationSelectorStateChanged(open: Boolean) = Unit
}

/**
 * Tellomi (A-06): Telegram's panel lays the emoji out eight to a row (36 dp each, `CustomEmojiReactionsWindow`); Signal's
 * grid works its columns out from a 48 dp cell, which leaves six in a strip-wide panel.
 */
class TellomiReactionPanelGrid(context: Context, attrs: AttributeSet?) : EmojiPageView(context, attrs) {
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    (layoutManager as? GridLayoutManager)?.spanCount = COLUMNS
  }

  private companion object {
    const val COLUMNS = 8
  }
}
