package org.thoughtcrime.securesms

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.components.settings.conversation.MuteUntilTimePickerBottomSheet
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

object MuteDialog {

  private const val MUTE_UNTIL: Long = -1L

  private data class MuteOption(
    @DrawableRes val iconRes: Int,
    val title: String,
    val duration: Long
  )

  private fun options(context: Context): List<MuteOption> {
    return listOf(
      MuteOption(R.drawable.ic_daytime_24, context.getString(R.string.arrays__mute_for_one_hour), 1.hours.inWholeMilliseconds),
      MuteOption(R.drawable.ic_nighttime_26, context.getString(R.string.arrays__mute_for_eight_hours), 8.hours.inWholeMilliseconds),
      MuteOption(R.drawable.symbol_calendar_one, context.getString(R.string.arrays__mute_for_one_day), 1.days.inWholeMilliseconds),
      MuteOption(R.drawable.symbol_calendar_week, context.getString(R.string.arrays__mute_for_seven_days), 7.days.inWholeMilliseconds),
      MuteOption(R.drawable.symbol_calendar_24, context.getString(R.string.MuteDialog__mute_until), MUTE_UNTIL),
      MuteOption(R.drawable.symbol_bell_slash_24, context.getString(R.string.arrays__always), Long.MAX_VALUE)
    )
  }

  private fun listenForCustomTime(fragmentManager: FragmentManager, lifecycleOwner: LifecycleOwner, action: MuteSelectionListener) {
    fragmentManager.setFragmentResultListener(MuteUntilTimePickerBottomSheet.REQUEST_KEY, lifecycleOwner) { _, bundle ->
      action.onMuted(bundle.getLong(MuteUntilTimePickerBottomSheet.RESULT_TIMESTAMP))
    }
  }

  private fun onSelected(option: MuteOption, fragmentManager: FragmentManager, action: MuteSelectionListener) {
    when (option.duration) {
      MUTE_UNTIL -> MuteUntilTimePickerBottomSheet.show(fragmentManager)
      Long.MAX_VALUE -> action.onMuted(Long.MAX_VALUE)
      else -> action.onMuted(System.currentTimeMillis() + option.duration)
    }
  }

  @JvmStatic
  fun show(context: Context, fragmentManager: FragmentManager, lifecycleOwner: LifecycleOwner, action: MuteSelectionListener) {
    listenForCustomTime(fragmentManager, lifecycleOwner, action)

    val options = options(context)

    val adapter = object : BaseAdapter() {
      override fun getCount(): Int = options.size
      override fun getItem(position: Int): MuteOption = options[position]
      override fun getItemId(position: Int): Long = position.toLong()

      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.mute_dialog_item, parent, false)
        val option = options[position]
        view.findViewById<ImageView>(R.id.mute_dialog_icon).setImageResource(option.iconRes)
        view.findViewById<TextView>(R.id.mute_dialog_title).text = option.title
        return view
      }
    }

    MaterialAlertDialogBuilder(context)
      .setTitle(R.string.MuteDialog_mute_notifications)
      .setAdapter(adapter) { _, which ->
        onSelected(options[which], fragmentManager, action)
      }
      .show()
  }

  /**
   * Tellomi（交互审计 A-39「选项型菜单都从屏幕底部 / 中间弹出，和点的那个按钮没有关系」）：静音时长就在点的地方展开，
   * 用 [menu] 配好位置的锚定菜单（会话列表长按菜单里点「静音」→ 锚在那一行；聊天页「···」里点「静音通知」→ 锚在「···」）。
   * 选项、图标、「静音到…」和 [show] 一样。Telegram 两端也是在原处选时长：iOS 会话长按菜单里推出子菜单，
   * 安卓聊天页右上角菜单里翻到二级页 / 资料页锚在通知按钮上弹出。
   */
  @JvmStatic
  fun showAnchored(menu: SignalContextMenu.Builder, fragmentManager: FragmentManager, lifecycleOwner: LifecycleOwner, action: MuteSelectionListener): SignalContextMenu {
    listenForCustomTime(fragmentManager, lifecycleOwner, action)

    val items = options(menu.anchor.context).map { option ->
      ActionItem(option.iconRes, option.title) { onSelected(option, fragmentManager, action) }
    }
    return menu.show(items)
  }

  fun interface MuteSelectionListener {
    fun onMuted(until: Long)
  }
}
