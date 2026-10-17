/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory

/**
 * 预测性返回（tellomi/tellomi 交互审计 A-22）：应用级打开，任何页面都不许悄悄退出。
 *
 * 上游在 `<application>` 上写了 `enableOnBackInvokedCallback="false"` 整体退出，只给 MainActivity、
 * MediaSendV3Activity 单独打开；其余页面的返回都看不到「先露出上一页」。合上游新版本时这一行很容易被
 * 原样带回来，所以这里盯住它。确实要让某个页面退出时，写进 [INTENTIONAL_OPT_OUTS] 并写清理由。
 */
class PredictiveBackOptInTest {

  @Test
  fun application_opts_in_to_predictive_back() {
    val application = parse(APP_MANIFEST).getElementsByTagName("application").item(0) as Element

    assertThat(application.getAttributeNS(ANDROID_NS, ATTRIBUTE)).isEqualTo("true")
  }

  /** 进 App 合并清单的模块（app、core、feature、lib）里，没有哪个 Activity 自己写 "false" 退出。 */
  @Test
  fun no_activity_in_merged_modules_opts_out() {
    val manifests = mergedModuleManifests()
    val optedOut = manifests.flatMap { manifest ->
      val activities = parse(manifest).getElementsByTagName("activity")
      (0 until activities.length)
        .map { activities.item(it) as Element }
        .filter { it.getAttributeNS(ANDROID_NS, ATTRIBUTE) == "false" }
        .map { "${it.getAttributeNS(ANDROID_NS, "name")}（$manifest）" }
        .filterNot { entry -> INTENTIONAL_OPT_OUTS.any { entry.startsWith("$it（") } }
    }

    assertThat(manifests.size).isGreaterThan(1)
    assertThat(optedOut).isEmpty()
  }

  /** 有意退出的每一项在主清单里确实写着 "false"——改名或删掉以后别留一条空理由。 */
  @Test
  fun intentional_opt_outs_are_still_in_the_manifest() {
    val activities = parse(APP_MANIFEST).getElementsByTagName("activity")
    val optedOut = (0 until activities.length)
      .map { activities.item(it) as Element }
      .filter { it.getAttributeNS(ANDROID_NS, ATTRIBUTE) == "false" }
      .map { it.getAttributeNS(ANDROID_NS, "name") }
      .toSet()

    assertThat(INTENTIONAL_OPT_OUTS - optedOut).isEmpty()
  }

  /**
   * 各构建变体自己的清单（app/src/<变体>/AndroidManifest.xml，好几个已经在 `<application>` 上用 tools:replace）
   * 也不许把应用或某个 Activity 改回 "false"：只合进某一个变体，上面两条看不见。
   */
  @Test
  fun no_variant_manifest_opts_out() {
    val manifests = variantManifests()
    val optedOut = manifests.flatMap { manifest ->
      val document = parse(manifest)
      listOf("application", "activity").flatMap { tag ->
        val elements = document.getElementsByTagName(tag)
        (0 until elements.length)
          .map { elements.item(it) as Element }
          .filter { it.getAttributeNS(ANDROID_NS, ATTRIBUTE) == "false" }
          .map { "<$tag> ${it.getAttributeNS(ANDROID_NS, "name")}（$manifest）" }
      }
    }

    assertThat(manifests.size).isGreaterThan(1)
    assertThat(optedOut).isEmpty()
  }

  private fun parse(path: Path): Document {
    return DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(path.inputStream())
  }

  /** 用例的工作目录是 app 模块；core / feature / lib 下各模块的 `src/main/AndroidManifest.xml`。 */
  private fun mergedModuleManifests(): List<Path> {
    val modules = listOf(Path(".")) + MODULE_GROUPS.flatMap { group ->
      val dir = Path("..", group)
      if (dir.isDirectory()) Files.list(dir).use { stream -> stream.filter { it.isDirectory() }.toList() } else emptyList()
    }
    return modules.map { it.resolve("src/main/AndroidManifest.xml") }.filter { it.exists() }
  }

  /** app 模块 `src/` 下除 main 以外各源码集的清单（构建变体、构建类型）。 */
  private fun variantManifests(): List<Path> {
    return Files.list(Path("src")).use { stream -> stream.filter { it.isDirectory() && it.fileName.toString() != "main" }.toList() }
      .map { it.resolve("AndroidManifest.xml") }
      .filter { it.exists() }
      .sorted()
  }

  companion object {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTRIBUTE = "enableOnBackInvokedCallback"
    private val APP_MANIFEST = Path("src/main/AndroidManifest.xml")
    private val MODULE_GROUPS = listOf("core", "feature", "lib")

    /**
     * 有意退出预测性返回的 Activity，按清单里 `android:name` 的写法，每一项都要带理由。
     *
     * 下面两个都是从缩略图 / 头像用共享元素展开、返回时 `finishAfterTransition()` 缩回去的页面：打开预测性返回后，
     * Android 15 起系统先放跨页预览，松手后应用自己的返回动画又放一遍。等返回动画能跟手再打开。
     * 媒体查看器（`.mediapreview.MediaPreviewActivity`）原来也在这里，返回手势跟手缩小做好后已经打开（A-20，Signal-Android#281）。
     */
    private val INTENTIONAL_OPT_OUTS: Set<String> = setOf(
      // 头像大图：从会话设置、编辑资料展开
      ".AvatarPreviewActivity",
      // 聊天里的联系人卡片：从卡片头像展开
      ".contactshare.SharedContactDetailsActivityV2"
    )
  }
}
