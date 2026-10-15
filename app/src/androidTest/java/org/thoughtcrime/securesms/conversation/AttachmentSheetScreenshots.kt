/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.mediasend.v3.MediaSendAttachmentSheetActivity
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.SignalActivityRule
import org.thoughtcrime.securesms.util.MessageTableTestUtils
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Tellomi（tellomi/tellomi#1115，判据「两端点『+』→ Sheet 从底部进入、聊天留在背景；相册网格首格相机、多选；dock 切换；下滑关闭」）：
 * 在真实会话页里点「+」，端到端走附件 Sheet：
 *
 * - 收起时 Sheet 顶边在全屏高度的 27% 处（Sheet 占 73%），上面露出会话页；底部 dock 五格从左到右；测试构建没有地图，
 *   「位置」读屏报「即将支持」，点了只提示、Sheet 留着；
 * - 勾两张 → dock 让位给「说明 + 发送」；拖顶栏往上 → 全屏；发送 → Sheet 收起，会话里多一条带两张图的消息；
 * - dock 的「文件」在同一个 Sheet 里换到「文件」页（tellomi/tellomi#1121，那一页的端到端在 AttachmentFilesScreenshots）；
 *   「投票」：Sheet 收起，新建投票页出来；
 * - 下拉关闭；选了东西下拉先问「放弃所选媒体？」，取消后 Sheet 还在；点压暗的聊天关闭；重复点「相册」展开；
 * - 网格往上滑时 Sheet 跟着手指走（量手指挪了多少、Sheet 挪了多少）。
 *
 * 只在 instrumentation 参数 `tellomiShots=1` 时跑；截图写在 `<app 外部文件目录>/tellomi-shots/<屏宽>dp/sheet-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSheetScreenshots {

  @get:Rule
  val harness = SignalActivityRule(othersCount = 1)

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val report = StringBuilder()
  private lateinit var outDir: File

  @Before
  fun onlyWhenAsked() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("tellomiShots") == "1")

    val widthDp = harness.context.resources.configuration.screenWidthDp
    outDir = File(harness.context.getExternalFilesDir(null), "tellomi-shots/${widthDp}dp").apply { mkdirs() }
    report.appendLine("screenWidthDp=$widthDp")

    val packageName = harness.context.packageName
    instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_IMAGES)
    instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.READ_MEDIA_VIDEO)

    val info = instrumentation.uiAutomation.serviceInfo
    info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    instrumentation.uiAutomation.serviceInfo = info
  }

  @Test
  fun plusOpensTheSheetOverTheChatAndSendsFromIt() {
    val run = SystemClock.uptimeMillis()
    listOf(300 to 400, 400 to 300, 500 to 500, 360 to 360).forEachIndexed { index, (width, height) ->
      insertGalleryImage("sheet-$run-$index", width, height, index)
      SystemClock.sleep(1_100)
    }

    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val before = MessageTableTestUtils.getMessages(threadId).map { it.id }.toSet()

    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      val chatOnly = instrumentation.uiAutomation.takeScreenshot()
      val sheet = openSheet(conversation)
      val metrics = SheetMetrics.of(sheet)
      report.appendLine("screenHeight=${metrics.screenHeight} statusBar=${metrics.statusBarTop} density=${metrics.density} expectedCollapsedTop=${metrics.collapsedTop}")

      // 收起：顶栏（56dp，标题在正中）从全屏高度的 27% 处开始
      val title = recentsTitle()
      report.appendLine("collapsed title=${title.boundsInScreen()}")
      assertEquals("收起时顶栏在 27% 处", metrics.collapsedTop + metrics.dp(28f), title.boundsInScreen().exactCenterY(), metrics.dp(12f))

      // 会话页还在后面、压暗 20%：Sheet 上面那块和打开前的截图比，亮一点的地方每个颜色都是原来的 0.8 倍左右
      val withSheet = instrumentation.uiAutomation.takeScreenshot()
      val ratio = dimRatioAbove(chatOnly, withSheet, fromY = metrics.statusBarTop + metrics.dp(8f), toY = metrics.collapsedTop - metrics.dp(8f))
      report.appendLine("chat above the sheet: dim ratio=$ratio")
      assertEquals("会话页露在 Sheet 上面、压暗 20%", 0.8f, ratio, 0.08f)

      // dock：五格一排，从左到右，在屏幕底部
      val dock = DOCK_LABELS.map { label -> textNode(label) }
      val dockBounds = dock.map { it.boundsInScreen() }
      report.appendLine("dock=$dockBounds")
      assertTrue("dock 从左到右", dockBounds.zipWithNext().all { (a, b) -> a.centerX() < b.centerX() })
      assertTrue("dock 在一排", dockBounds.all { abs(it.centerY() - dockBounds[0].centerY()) < metrics.dp(4f) })
      assertTrue("dock 在屏幕底部", dockBounds[0].centerY() > metrics.screenHeight - metrics.dp(140f))

      // 测试构建没有地图：「位置」读屏报「即将支持」
      val comingSoon = harness.context.getString(R.string.TellomiLocation__coming_soon)
      val location = dockNode(harness.context.getString(R.string.AttachmentKeyboard_location))
      if (Build.VERSION.SDK_INT >= 30) {
        report.appendLine("location stateDescription=${location.stateDescription}")
        assertEquals("置灰的「位置」读屏报即将支持", comingSoon, location.stateDescription?.toString())
      }
      shot("sheet-1-collapsed")

      click(location)
      waitFor("「即将支持」的提示") { nodes { it.text?.toString() == comingSoon }.isNotEmpty() }
      assertTrue("点置灰的「位置」Sheet 留着", resumedActivity() is MediaSendAttachmentSheetActivity)
      shot("sheet-2-location-coming-soon")
      settle(3500)

      // 勾两张：dock 让位给「说明 + 发送」
      waitFor("网格里的勾") { checks().size >= 2 }
      click(checks()[0])
      settle(500)
      click(checks()[1])
      waitFor("「✓2」") { byDescription("2 selected").isNotEmpty() }
      waitFor("dock 让位") { nodes { it.text?.toString() == DOCK_LABELS[3] }.isEmpty() }
      shot("sheet-3-selected")

      // 拖顶栏往上：全屏，顶栏就在状态栏下面
      val titleCenter = recentsTitle().boundsInScreen()
      drag(x = titleCenter.exactCenterX() + metrics.dp(80f), fromY = titleCenter.exactCenterY(), toY = titleCenter.exactCenterY() - metrics.screenHeight * 0.4f, durationMs = 600)
      settle(800)
      val expandedTitle = recentsTitle().boundsInScreen()
      report.appendLine("expanded title=$expandedTitle")
      assertEquals("全屏时顶栏在状态栏下面", metrics.statusBarTop + metrics.dp(28f), expandedTitle.exactCenterY(), metrics.dp(12f))
      shot("sheet-4-expanded")

      // 发送：Sheet 收起，会话里多一条带两张图的消息
      click(sendButton())
      val sent = waitForNewOutgoing(threadId, before, 1)
      val attachments = waitForAttachments(sent, 2)
      report.appendLine("sent=${sent.map { it.id }} attachments=$attachments")
      assertEquals("一条消息带两张图", listOf(2), attachments)
      waitFor("回到会话页") { resumedActivity() !is MediaSendAttachmentSheetActivity }
      settle(1200)
      shot("sheet-5-sent")
    } finally {
      File(outDir, "metrics-sheet-send.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun dockFileAndPollStayInTheSheet() {
    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val conversation = openConversation(other, threadId)
    try {
      settle(1500)

      // tellomi/tellomi#1121：「文件」在同一个 Sheet 里换页，不收起
      openSheet(conversation)
      click(dockNode(harness.context.getString(R.string.AttachmentKeyboard_file)))
      textNode(harness.context.getString(org.signal.mediasend.R.string.AttachmentFilesScreen__select_from_files))
      assertTrue("点「文件」Sheet 留着", resumedActivity() is MediaSendAttachmentSheetActivity)
      settle(800)
      shot("sheet-6-files-page")
      closeSheetIfOpen()
      settle(800)

      openSheet(conversation)
      click(dockNode(harness.context.getString(R.string.AttachmentKeyboard_poll)))
      val newPoll = harness.context.getString(R.string.CreatePollFragment__new_poll)
      waitFor("新建投票页") { nodes { it.text?.toString() == newPoll }.isNotEmpty() }
      // 2026-10-16：投票也在同一个 Sheet 里换页（照 Telegram 两端），不再收起 Sheet 另开整屏对话框
      assertTrue("点「投票」Sheet 留着", resumedActivity() is MediaSendAttachmentSheetActivity)
      settle(800)
      shot("sheet-7-poll")
      instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      settle(800)
    } finally {
      File(outDir, "metrics-sheet-dock.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun dragDownClosesTheSheetAndAsksFirstWhenSomethingIsSelected() {
    val run = SystemClock.uptimeMillis()
    insertGalleryImage("close-$run", 400, 400, 7)
    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val conversation = openConversation(other, threadId)
    try {
      settle(1500)

      // 没选东西：下拉就关
      val metrics = SheetMetrics.of(openSheet(conversation))
      val title = recentsTitle().boundsInScreen()
      drag(x = title.exactCenterX() + metrics.dp(80f), fromY = title.exactCenterY(), toY = title.exactCenterY() + metrics.screenHeight * 0.3f, durationMs = 500)
      waitFor("下拉关闭") { resumedActivity() != null && resumedActivity() !is MediaSendAttachmentSheetActivity }
      settle(800)

      // 选了一张：下拉先问，取消后 Sheet 还在、回到收起位置
      openSheet(conversation)
      waitFor("网格里的勾") { checks().isNotEmpty() }
      click(checks()[0])
      waitFor("「✓1」") { byDescription("1 selected").isNotEmpty() }
      val selectedTitle = recentsTitle().boundsInScreen()
      drag(x = selectedTitle.exactCenterX() + metrics.dp(80f), fromY = selectedTitle.exactCenterY(), toY = selectedTitle.exactCenterY() + metrics.screenHeight * 0.3f, durationMs = 500)
      val discard = harness.context.getString(org.signal.mediasend.R.string.MediaSendDialogs__discard_media)
      waitFor("「放弃所选媒体？」") { nodes { it.text?.toString() == discard }.isNotEmpty() }
      shot("sheet-8-discard-asked")
      click(textNode(harness.context.getString(android.R.string.cancel)))
      settle(1000)
      assertTrue("取消后 Sheet 还在", resumedActivity() is MediaSendAttachmentSheetActivity)
      assertEquals("回到收起位置", metrics.collapsedTop + metrics.dp(28f), recentsTitle().boundsInScreen().exactCenterY(), metrics.dp(12f))
      // 取消选择再关，免得下一段又被问
      click(checks()[0])
      waitFor("没有选中") { byDescription("1 selected").isEmpty() }

      // 点压暗的聊天：关闭
      tap(x = metrics.screenWidth / 2f, y = metrics.collapsedTop / 2f)
      waitFor("点聊天关闭") { resumedActivity() != null && resumedActivity() !is MediaSendAttachmentSheetActivity }
      settle(800)

      // 重复点「相册」：展开到全屏。选中的 Tab 在读屏树里不报可点，按坐标点它
      openSheet(conversation)
      val gallery = textNode(harness.context.getString(R.string.AttachmentKeyboard_gallery)).boundsInScreen()
      tap(x = gallery.exactCenterX(), y = gallery.exactCenterY())
      settle(1000)
      assertEquals("重复点相册展开", metrics.statusBarTop + metrics.dp(28f), recentsTitle().boundsInScreen().exactCenterY(), metrics.dp(12f))
      shot("sheet-9-gallery-expanded")
    } finally {
      File(outDir, "metrics-sheet-close.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  @Test
  fun scrollingTheGridUpMovesTheSheetWithTheFinger() {
    val run = SystemClock.uptimeMillis()
    repeat(9) { index -> insertGalleryImage("finger-$run-$index", 320, 320, index) }
    val other = harness.others[0]
    val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(other))
    val conversation = openConversation(other, threadId)
    try {
      settle(1500)
      val metrics = SheetMetrics.of(openSheet(conversation))
      waitFor("网格里的勾") { checks().size >= 6 }
      val startTitle = recentsTitle().boundsInScreen().exactCenterY()

      // 从收起的 Sheet 正中（网格里，三种屏宽都在 dock 上面、离底部的系统手势区很远）按下，慢慢往上挪 150dp，
      // 不松手量一下 Sheet 跟了多少
      val x = metrics.screenWidth / 2f
      val fromY = metrics.collapsedTop + (metrics.screenHeight - metrics.collapsedTop) / 2f
      val distance = metrics.dp(150f)
      val downTime = SystemClock.uptimeMillis()
      inject(MotionEvent.ACTION_DOWN, x, fromY, downTime)
      val steps = 30
      for (i in 1..steps) {
        SystemClock.sleep(16)
        inject(MotionEvent.ACTION_MOVE, x, fromY - distance * i / steps, downTime)
      }
      SystemClock.sleep(300)
      val movedTitle = recentsTitle().boundsInScreen().exactCenterY()
      inject(MotionEvent.ACTION_UP, x, fromY - distance, downTime)
      settle(800)

      val followed = (startTitle - movedTitle) / distance
      report.appendLine("finger=$distance sheet=${startTitle - movedTitle} ratio=$followed")
      assertTrue("Sheet 跟着手指走（跟了 ${"%.2f".format(followed)}）", followed > 0.85f)
      assertTrue("松手后停在全屏或收起", abs(recentsTitle().boundsInScreen().exactCenterY() - (metrics.statusBarTop + metrics.dp(28f))) < metrics.dp(12f) || abs(recentsTitle().boundsInScreen().exactCenterY() - (metrics.collapsedTop + metrics.dp(28f))) < metrics.dp(12f))
    } finally {
      File(outDir, "metrics-sheet-finger.txt").writeText(report.toString())
      closeSheetIfOpen()
      conversation.close()
    }
  }

  // region helpers

  /** 屏幕与 Sheet 的几个量（px）：收起时 Sheet 顶边 = 状态栏 + (全屏 − 状态栏) × 27%。 */
  private class SheetMetrics(val screenWidth: Int, val screenHeight: Int, val statusBarTop: Float, val density: Float) {
    val collapsedTop: Float get() = statusBarTop + (screenHeight - statusBarTop) * (1f - 0.73f)
    fun dp(value: Float): Float = value * density

    companion object {
      fun of(activity: Activity): SheetMetrics {
        var metrics: SheetMetrics? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
          val decor = activity.window.decorView
          val statusBar = if (Build.VERSION.SDK_INT >= 30) decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0 else 0
          metrics = SheetMetrics(decor.width, decor.height, statusBar.toFloat(), activity.resources.displayMetrics.density)
        }
        return metrics!!
      }
    }
  }

  /** 「+」→ 附件 Sheet 到前台、从底下滑上来停稳。 */
  private fun openSheet(conversation: OpenedConversation): Activity {
    conversation.onActivity { it.findViewById<View>(R.id.attach_button).performClick() }
    waitFor("附件 Sheet") { resumedActivity() is MediaSendAttachmentSheetActivity }
    settle(1200)
    return resumedActivity()!!
  }

  private fun recentsTitle(): AccessibilityNodeInfo {
    waitFor("顶栏「最近」") { nodes { it.text?.toString() == "Recents" }.isNotEmpty() }
    return nodes { it.text?.toString() == "Recents" }.first()
  }

  /** dock 里的格子：按文字找，取它可点的那一层（带读屏状态）。 */
  private fun dockNode(label: String): AccessibilityNodeInfo {
    var node: AccessibilityNodeInfo? = textNode(label)
    while (node != null && !node.isClickable) {
      node = node.parent
    }
    assertTrue("dock 里「$label」没有可点的一层", node != null)
    return node!!
  }

  private fun textNode(text: String): AccessibilityNodeInfo {
    waitFor("「$text」") { nodes { it.text?.toString() == text }.isNotEmpty() }
    return nodes { it.text?.toString() == text }.first()
  }

  /** Sheet 里「说明 + 发送」的发送键（后面会话页的输入框也可能有个 Send，只在 Sheet 自己的窗口里找）。 */
  private fun sendButton(): AccessibilityNodeInfo {
    val send = harness.context.getString(org.signal.mediasend.R.string.AddAMessageRow__send)
    val root = instrumentation.uiAutomation.rootInActiveWindow
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo) {
      if (node.contentDescription?.toString() == send) {
        found += node
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(::walk)
      }
    }
    root?.let(::walk)
    assertTrue("Sheet 里找不到发送键", found.isNotEmpty())
    return found.first()
  }

  private fun checks(): List<AccessibilityNodeInfo> = byDescription("Select")

  private fun byDescription(description: String): List<AccessibilityNodeInfo> = nodes { it.contentDescription?.toString() == description }

  private fun nodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    val automation = instrumentation.uiAutomation
    if (Build.VERSION.SDK_INT >= 34) {
      automation.clearCache()
    }
    val roots = automation.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
    val found = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo) {
      if (predicate(node)) {
        found += node
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(::walk)
      }
    }
    roots.forEach(::walk)
    return found.sortedWith(compareBy({ it.boundsInScreen().top }, { it.boundsInScreen().left }))
  }

  /**
   * [fromY]…[toY] 之间，打开 Sheet 前后两张截图同一个点的颜色比，**只看原来是中间调的点**（头像、图标、字的边缘，60…220），取中位数：
   * 聊天露着、压暗 20% 就是 0.8 左右；窗口不透明的话，这些点会变成压暗了的窗口底色（白底压暗后比原来亮，比值 > 1），对不上。
   * 不看白的点：聊天底色本来就是白的，白底压暗和不透明的白窗口压暗是同一个颜色，分不出来（第一版这么量，窗口改成不透明照样 0.80）。
   */
  private fun dimRatioAbove(before: Bitmap?, after: Bitmap?, fromY: Float, toY: Float): Float {
    assertTrue("截图失败", before != null && after != null)
    val ratios = mutableListOf<Float>()
    val width = minOf(before!!.width, after!!.width)
    for (row in 0 until 40) {
      val y = (fromY + (toY - fromY) * (row + 0.5f) / 40).toInt()
      for (col in 0 until 40) {
        val x = (width * (col + 0.5f) / 40).toInt()
        val a = before.getPixel(x, y)
        val b = after.getPixel(x, y)
        for (shift in listOf(16, 8, 0)) {
          val original = (a shr shift) and 0xFF
          if (original in 60..220) {
            ratios += ((b shr shift) and 0xFF) / original.toFloat()
          }
        }
      }
    }
    report.appendLine("mid-tone samples above the sheet: ${ratios.size}")
    assertTrue("Sheet 上面没有中间调的点可比（只有 ${ratios.size} 个）", ratios.size >= 20)
    return ratios.sorted()[ratios.size / 2]
  }

  private fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

  private fun click(node: AccessibilityNodeInfo) {
    var target: AccessibilityNodeInfo? = node
    while (target != null && !target.isClickable) {
      target = target.parent
    }
    assertTrue("「${node.contentDescription ?: node.text}」没有可点的一层", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
  }

  private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
    assertTrue("注入触摸失败", instrumentation.uiAutomation.injectInputEvent(event, true))
    event.recycle()
  }

  /** 真实触摸：按下、分步挪到 [toY]、抬起（慢到不算甩）。 */
  private fun drag(x: Float, fromY: Float, toY: Float, durationMs: Long) {
    val downTime = SystemClock.uptimeMillis()
    inject(MotionEvent.ACTION_DOWN, x, fromY, downTime)
    val steps = (durationMs / 16).toInt().coerceAtLeast(2)
    for (i in 1..steps) {
      SystemClock.sleep(16)
      inject(MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / steps, downTime)
    }
    SystemClock.sleep(150)
    inject(MotionEvent.ACTION_UP, x, toY, downTime)
  }

  private fun tap(x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    inject(MotionEvent.ACTION_DOWN, x, y, downTime)
    SystemClock.sleep(50)
    inject(MotionEvent.ACTION_UP, x, y, downTime)
  }

  private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + 20_000
    while (SystemClock.uptimeMillis() < deadline) {
      if (condition()) {
        return
      }
      SystemClock.sleep(200)
    }
    assertTrue("等不到：$what", condition())
  }

  private fun waitForNewOutgoing(threadId: Long, before: Set<Long>, expected: Int): List<MessageRecord> {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var found: List<MessageRecord> = emptyList()
    while (SystemClock.uptimeMillis() < deadline) {
      found = MessageTableTestUtils.getMessages(threadId).filter { it.id !in before && it.isOutgoing }.sortedBy { it.dateSent }
      if (found.size >= expected) break
      SystemClock.sleep(200)
    }
    assertEquals("新发出的消息条数", expected, found.size)
    return found
  }

  private fun waitForAttachments(records: List<MessageRecord>, expectedEach: Int): List<Int> {
    val deadline = SystemClock.uptimeMillis() + 20_000
    var counts = records.map { SignalDatabase.attachments.getAttachmentsForMessage(it.id).size }
    while (SystemClock.uptimeMillis() < deadline && counts.any { it < expectedEach }) {
      SystemClock.sleep(200)
      counts = records.map { SignalDatabase.attachments.getAttachmentsForMessage(it.id).size }
    }
    return counts
  }

  private fun closeSheetIfOpen() {
    val activity = resumedActivity()
    if (activity is MediaSendAttachmentSheetActivity) {
      instrumentation.runOnMainSync { activity.finish() }
      settle(600)
    }
  }

  private fun insertGalleryImage(name: String, width: Int, height: Int, number: Int) {
    val resolver = harness.context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "tellomi-sheet-$name.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TellomiSheetTest")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    resolver.openOutputStream(uri)!!.use { it.write(jpeg(width, height, number)) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
  }

  private fun jpeg(width: Int, height: Int, number: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.HSVToColor(floatArrayOf((number * 67f) % 360f, 0.55f, 0.9f)))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textAlign = Paint.Align.CENTER
      textSize = minOf(width, height) * 0.4f
    }
    canvas.drawText("${number + 1}", width / 2f, height / 2f + paint.textSize * 0.35f, paint)
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      bitmap.recycle()
      out.toByteArray()
    }
  }

  private fun openConversation(recipientId: RecipientId, threadId: Long): OpenedConversation {
    val intent = ConversationIntents.createBuilderSync(harness.context, recipientId, threadId).build()
    return OpenedConversation(instrumentation.startActivitySync(intent))
  }

  private inner class OpenedConversation(private val activity: Activity) {
    fun onActivity(block: (Activity) -> Unit) {
      var error: Throwable? = null
      instrumentation.runOnMainSync {
        try {
          block(activity)
        } catch (t: Throwable) {
          error = t
        }
      }
      error?.let { throw it }
    }

    fun close() {
      instrumentation.runOnMainSync { activity.finish() }
      instrumentation.waitForIdleSync()
      SystemClock.sleep(500)
    }
  }

  private fun resumedActivity(): Activity? {
    var activity: Activity? = null
    instrumentation.runOnMainSync {
      activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return activity
  }

  private fun shot(name: String) {
    instrumentation.waitForIdleSync()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun settle(millis: Long = 600) {
    instrumentation.waitForIdleSync()
    SystemClock.sleep(millis)
    instrumentation.waitForIdleSync()
  }

  // endregion

  private companion object {
    private val DOCK_LABELS = listOf("Gallery", "File", "Location", "Poll", "Contact")
  }
}
