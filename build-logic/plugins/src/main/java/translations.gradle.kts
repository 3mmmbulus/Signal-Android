import groovy.util.Node
import groovy.xml.XmlParser
import org.signal.buildtools.SmartlingClient
import org.signal.buildtools.StaticIpResolver
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Tasks for managing translations and static files.
 *
 * Smartling tasks require the following properties in local.properties:
 *   smartling.userIdentifier - Smartling API user identifier
 *   smartling.userSecret - Smartling API user secret
 *   smartling.projectId - Smartling project ID
 */

// =====================
// Data Classes
// =====================

/**
 * Represents a module containing translatable string resources.
 *
 * @property name Human-readable module name (e.g., "app", "lib-device-transfer")
 * @property fileUri Smartling file identifier. Uses "strings.xml" for app (backward compat), otherwise "{name}-strings.xml"
 * @property stringsFile Path to the source English strings.xml
 * @property resDir Path to the module's res directory for writing translated files
 */
data class TranslatableModule(
  val name: String,
  val fileUri: String,
  val stringsFile: File,
  val resDir: File
)

/**
 * Information about translatable strings in a strings.xml file.
 */
data class StringsInfo(
  val totalCount: Int,
  val translatableCount: Int,
  val hasTranslatable: Boolean
)

// =====================
// Standalone helpers (configuration-cache safe — not methods on the script object)
// =====================

private object TranslationUtils {

  /**
   * A mapping of smartling-locale => Android locale.
   * Only needed when they differ.
   */
  val localeMap = mapOf(
    "af-ZA" to "af",
    "az-AZ" to "az",
    "be-BY" to "be",
    "bg-BG" to "bg",
    "bn-BD" to "bn",
    "bs-BA" to "bs",
    "et-EE" to "et",
    "fa-IR" to "fa",
    "ga-IE" to "ga",
    "gl-ES" to "gl",
    "gu-IN" to "gu",
    "he" to "iw",
    "hi-IN" to "hi",
    "hr-HR" to "hr",
    "id" to "in",
    "ka-GE" to "ka",
    "kk-KZ" to "kk",
    "km-KH" to "km",
    "kn-IN" to "kn",
    "ky-KG" to "ky",
    "lt-LT" to "lt",
    "lv-LV" to "lv",
    "mk-MK" to "mk",
    "ml-IN" to "ml",
    "mr-IN" to "mr",
    "pa-IN" to "pa",
    "pt-BR" to "pt-rBR",
    "pt-PT" to "pt",
    "ro-RO" to "ro",
    "sk-SK" to "sk",
    "sl-SI" to "sl",
    "sq-AL" to "sq",
    "sr-RS" to "sr-rRS",
    "sr-YR" to "sr",
    "ta-IN" to "ta",
    "te-IN" to "te",
    "tl-PH" to "tl",
    "uk-UA" to "uk",
    "zh-CN" to "zh-rCN",
    "zh-HK" to "zh-rHK",
    "zh-TW" to "zh-rTW",
    "zh-YU" to "yue"
  )

  /**
   * Locales that should not be saved, even if present remotely.
   * Typically for unfinished translations not ready to be public.
   */
  val localeBlocklist = emptySet<String>()

  /**
   * Discovers all modules with translatable strings by scanning for `strings.xml` files
   * in `src/main/res/values/` directories. Excludes demo apps.
   */
  fun discoverTranslatableModules(rootDir: File): List<TranslatableModule> {
    return rootDir.walkTopDown()
      .filter { it.name == "strings.xml" && it.parentFile.name == "values" }
      .filter { it.path.contains("src${File.separator}main${File.separator}res") }
      .filter { !it.path.contains("${File.separator}demo${File.separator}") }
      .map { stringsFile ->
        val resDir = stringsFile.parentFile.parentFile
        val modulePath = resDir.parentFile.parentFile.parentFile
        val moduleName = modulePath.relativeTo(rootDir).path
          .replace(File.separator, "-")
          .ifEmpty { "app" }
        val fileUri = if (moduleName == "app") "strings.xml" else "$moduleName-strings.xml"
        TranslatableModule(moduleName, fileUri, stringsFile, resDir)
      }
      .sortedBy { it.name }
      .toList()
  }

  /**
   * Analyzes a strings.xml file to count total and translatable strings.
   * Parses the file once and returns counts for both all strings and translatable strings.
   * Only counts actual string resources: <string>, <plurals>, and <string-array> elements.
   * Excludes placeholder <item type="string" /> declarations.
   */
  fun analyzeStrings(stringsFile: File): StringsInfo {
    return try {
      val xml = XmlParser().parse(stringsFile)
      val stringNodes = xml.children()
        .filterIsInstance<Node>()
        .filter { node ->
          val nodeName = node.name().toString()
          nodeName == "string" || nodeName == "plurals" || nodeName == "string-array"
        }

      val totalCount = stringNodes.size
      val translatableCount = stringNodes.count { node ->
        node.attribute("translatable") != "false"
      }

      StringsInfo(
        totalCount = totalCount,
        translatableCount = translatableCount,
        hasTranslatable = translatableCount > 0
      )
    } catch (e: Exception) {
      // If we can't parse the file, return -1 to indicate error
      StringsInfo(totalCount = -1, translatableCount = -1, hasTranslatable = true)
    }
  }

  /**
   * Iterates over all strings.xml files in all translatable modules.
   * This includes the source English file and all translated locale files.
   */
  fun allStringsResourceFiles(rootDir: File, action: (File) -> Unit) {
    val modules = discoverTranslatableModules(rootDir)
    for (module in modules) {
      module.resDir.walkTopDown()
        .filter { it.isFile && it.name == "strings.xml" }
        .forEach(action)
    }
  }

  fun createSmartlingClient(rootDir: File): SmartlingClient {
    val localPropertiesFile = File(rootDir, "local.properties")
    if (!localPropertiesFile.exists()) {
      throw GradleException("local.properties not found at ${localPropertiesFile.absolutePath}")
    }

    val localProperties = Properties().apply {
      localPropertiesFile.inputStream().use { load(it) }
    }

    val userIdentifier = localProperties.requireProperty("smartling.userIdentifier")
    val userSecret = localProperties.requireProperty("smartling.userSecret")
    val projectId = localProperties.requireProperty("smartling.projectId")

    return SmartlingClient(userIdentifier, userSecret, projectId)
  }

  private fun Properties.requireProperty(name: String): String {
    return getProperty(name) ?: throw GradleException("$name not found in local.properties")
  }
}

// =====================
// Smartling Tasks
// =====================

tasks.register("translationsDryRun") {
  group = "Translations"
  description = "Preview discovered modules and translation files without making API calls"

  val rootDirFile = rootDir

  doLast {
    val modules = TranslationUtils.discoverTranslatableModules(rootDirFile)

    logger.lifecycle("")
    logger.lifecycle("=".repeat(60))
    logger.lifecycle("Translations Dry Run - Module Discovery")
    logger.lifecycle("=".repeat(60))
    logger.lifecycle("")
    logger.lifecycle("Discovered ${modules.size} translatable module(s):")
    logger.lifecycle("")

    modules.forEach { module ->
      val info = TranslationUtils.analyzeStrings(module.stringsFile)
      logger.lifecycle("  Module: ${module.name}")
      logger.lifecycle("    File URI:     ${module.fileUri}")
      logger.lifecycle("    Source file:  ${module.stringsFile.relativeTo(rootDirFile)}")
      logger.lifecycle("    Resource dir: ${module.resDir.relativeTo(rootDirFile)}")
      logger.lifecycle("    String count: ${info.translatableCount} translatable, ${info.totalCount} total")
      logger.lifecycle("    Will upload:  ${if (info.hasTranslatable) "Yes" else "No (no translatable strings)"}")
      logger.lifecycle("")
    }

    logger.lifecycle("=".repeat(60))
    logger.lifecycle("Push would upload ${modules.size} file(s) to Smartling")
    logger.lifecycle("Pull would download translations to ${modules.size} module(s)")
    logger.lifecycle("=".repeat(60))
  }
}

tasks.register("pushTranslations") {
  group = "Translations"
  description = "Pushes strings.xml files from all modules to Smartling for translation. Use -PdryRun to preview."

  val rootDirFile = rootDir
  val isDryRun = project.hasProperty("dryRun")

  doLast {
    val modules = TranslationUtils.discoverTranslatableModules(rootDirFile)

    if (modules.isEmpty()) {
      throw GradleException("No translatable modules found")
    }

    logger.lifecycle("Using Signal-Android root directory of $rootDirFile")
    logger.lifecycle("Found ${modules.size} module(s) to push")
    if (isDryRun) {
      logger.lifecycle("")
      logger.lifecycle("[DRY-RUN MODE - No files will be uploaded]")
    }
    logger.lifecycle("")

    val client = if (isDryRun) null else TranslationUtils.createSmartlingClient(rootDirFile)
    val authToken = if (isDryRun) {
      null
    } else {
      logger.lifecycle("Fetching auth...")
      val token = client!!.authenticate()
      logger.lifecycle("> Done")
      logger.lifecycle("")
      token
    }

    var skippedCount = 0
    for (module in modules) {
      if (!module.stringsFile.exists()) {
        logger.warn("strings.xml not found for module ${module.name} at ${module.stringsFile.absolutePath}")
        continue
      }

      val info = TranslationUtils.analyzeStrings(module.stringsFile)

      // Skip files with no translatable strings
      if (!info.hasTranslatable) {
        logger.lifecycle("Skipping ${module.name}: No translatable strings found (${info.totalCount} non-translatable)")
        skippedCount++
        continue
      }

      if (isDryRun) {
        logger.lifecycle("[DRY-RUN] Would upload: ${module.stringsFile.relativeTo(rootDirFile)}")
        logger.lifecycle("          File URI: ${module.fileUri}")
        logger.lifecycle("          Strings:  ${info.translatableCount} translatable")
        logger.lifecycle("")
      } else {
        logger.lifecycle("Uploading ${module.fileUri} (${info.translatableCount} translatable strings)...")
        val response = client!!.uploadFile(authToken!!, module.stringsFile, module.fileUri)
        logger.lifecycle(response)
        logger.lifecycle("> Done")
        logger.lifecycle("")
      }
    }

    if (isDryRun) {
      logger.lifecycle("=".repeat(60))
      val uploadCount = modules.size - skippedCount
      logger.lifecycle("[DRY-RUN] Would have uploaded $uploadCount file(s)")
      if (skippedCount > 0) {
        logger.lifecycle("          Skipped $skippedCount file(s) with no translatable strings")
      }
      logger.lifecycle("Run without -PdryRun to actually upload")
      logger.lifecycle("=".repeat(60))
    } else {
      if (skippedCount > 0) {
        logger.lifecycle("")
        logger.lifecycle("Skipped $skippedCount file(s) with no translatable strings")
      }
    }
  }
}

tasks.register("pullTranslations") {
  group = "Translations"
  description = "Pulls translated strings.xml files from Smartling for all modules and locales. Use -PdryRun to preview."
  mustRunAfter("pushTranslations")

  val rootDirFile = rootDir
  val isDryRun = project.hasProperty("dryRun")

  doLast {
    val modules = TranslationUtils.discoverTranslatableModules(rootDirFile)

    if (modules.isEmpty()) {
      throw GradleException("No translatable modules found")
    }

    logger.lifecycle("Using Signal-Android root directory of $rootDirFile")
    logger.lifecycle("Found ${modules.size} module(s) to pull translations for")
    if (isDryRun) {
      logger.lifecycle("")
      logger.lifecycle("[DRY-RUN MODE - No files will be downloaded or written]")
    }
    logger.lifecycle("")

    val client = TranslationUtils.createSmartlingClient(rootDirFile)

    logger.lifecycle("Fetching auth...")
    val authToken = client.authenticate()
    logger.lifecycle("> Done")
    logger.lifecycle("")

    for (module in modules) {
      logger.lifecycle("Processing module: ${module.name}")
      logger.lifecycle("  File URI: ${module.fileUri}")

      logger.lifecycle("  Fetching locales...")
      val locales = try {
        client.getLocales(authToken, module.fileUri)
      } catch (e: Exception) {
        logger.warn("  Could not get locales for ${module.fileUri}: ${e.message}")
        logger.lifecycle("  (This may be normal for new modules that haven't been pushed yet)")
        logger.lifecycle("")
        continue
      }

      val filteredLocales = locales.filter { it !in TranslationUtils.localeBlocklist }
      logger.lifecycle("  Found ${locales.size} locales (${filteredLocales.size} after filtering)")
      logger.lifecycle("")

      if (isDryRun) {
        logger.lifecycle("  [DRY-RUN] Would download ${filteredLocales.size} translations to:")
        logger.lifecycle("            ${module.resDir.relativeTo(rootDirFile)}/values-{locale}/strings.xml")
        logger.lifecycle("")
        continue
      }

      logger.lifecycle("  Fetching files...")
      val executor = Executors.newFixedThreadPool(35)
      val futures = mutableListOf<Future<Pair<String, String>>>()

      for (locale in filteredLocales) {
        futures += executor.submit<Pair<String, String>> {
          val content = client.downloadFile(authToken, module.fileUri, locale)
          logger.lifecycle("  Successfully pulled ${module.name} for locale $locale")
          locale to content
        }
      }

      val results = futures.map { it.get() }
      executor.shutdown()
      logger.lifecycle("  > Done fetching")

      logger.lifecycle("  Writing files...")
      for ((locale, content) in results) {
        val androidLocale = TranslationUtils.localeMap[locale] ?: locale
        val localeDir = File(module.resDir, "values-$androidLocale")
        localeDir.mkdirs()
        File(localeDir, "strings.xml").writeText(content)
      }
      logger.lifecycle("  > Done writing ${results.size} files")
      logger.lifecycle("")
    }

    if (isDryRun) {
      logger.lifecycle("=".repeat(60))
      logger.lifecycle("[DRY-RUN] Would have downloaded translations for ${modules.size} module(s)")
      logger.lifecycle("Run without -PdryRun to actually download")
      logger.lifecycle("=".repeat(60))
    }
  }
}

tasks.register("replaceEllipsis") {
  group = "Static Files"
  description = "Process strings for ellipsis characters."
  mustRunAfter("pullTranslations")

  val rootDirFile = rootDir

  doLast {
    TranslationUtils.allStringsResourceFiles(rootDirFile) { f ->
      val before = f.readText()
      val after = before.replace("...", "…")
      if (before != after) {
        f.writeText(after)
        logger.info("${f.parentFile.name}/${f.name}...updated")
      }
    }
  }
}

tasks.register("cleanApostropheErrors") {
  group = "Static Files"
  description = "Fix smartling apostrophe string errors."
  mustRunAfter("pullTranslations")

  val rootDirFile = rootDir

  doLast {
    val pattern = Regex("""([^\\=08])(')""")
    TranslationUtils.allStringsResourceFiles(rootDirFile) { f ->
      val before = f.readText()
      val after = pattern.replace(before) { match ->
        "${match.groupValues[1]}\\'"
      }
      if (before != after) {
        f.writeText(after)
        logger.info("${f.parentFile.name}/${f.name}...updated")
      }
    }
  }
}

tasks.register("excludeNonTranslatables") {
  group = "Static Files"
  description = "Remove strings that are marked \"translatable\"=\"false\" or are ExtraTranslations."
  mustRunAfter("pullTranslations")

  val rootDirFile = rootDir

  doLast {
    val modules = TranslationUtils.discoverTranslatableModules(rootDirFile)

    for (module in modules) {
      val englishFile = module.stringsFile

      if (!englishFile.exists()) {
        logger.warn("English file not found for module ${module.name}, skipping excludeNonTranslatables")
        continue
      }

      val english = XmlParser().parse(englishFile)
      val nonTranslatable = english.children()
        .filterIsInstance<Node>()
        .filter { it.attribute("translatable") == "false" }
        .mapNotNull { it.attribute("name") as? String }
        .toSet()
      val all = english.children()
        .filterIsInstance<Node>()
        .mapNotNull { it.attribute("name") as? String }
        .toSet()
      val translatable = all - nonTranslatable

      module.resDir.walkTopDown()
        .filter { it.isFile && it.name == "strings.xml" && it != englishFile }
        .forEach { f ->
          var inMultiline = false
          var endBlockName = ""

          val newLines = f.readLines().map { line ->
            if (!inMultiline) {
              val singleLineMatcher = Regex("""name="([^"]*)".*(<\/|\/>)""").find(line)
              if (singleLineMatcher != null) {
                val name = singleLineMatcher.groupValues[1]
                if (!line.contains("excludeNonTranslatables") && name !in translatable) {
                  return@map "  <!-- Removed by excludeNonTranslatables ${line.trim()} -->"
                }
              } else {
                val multilineStartMatcher = Regex("""<(.*) .?name="([^"]*)".*""").find(line)
                if (multilineStartMatcher != null) {
                  endBlockName = multilineStartMatcher.groupValues[1]
                  val name = multilineStartMatcher.groupValues[2]
                  if (!line.contains("excludeNonTranslatables") && name !in translatable) {
                    inMultiline = true
                    return@map "  <!-- Removed by excludeNonTranslatables ${line.trim()}"
                  }
                }
              }
            } else {
              val multilineEndMatcher = Regex("""</$endBlockName""").find(line)
              if (multilineEndMatcher != null) {
                inMultiline = false
                return@map "$line -->"
              }
            }

            line
          }

          f.writeText(newLines.joinToString("\n") + "\n")
        }
    }
  }
}

// Tellomi（#1077）：这张表是 SignalServiceNetworkAccess.DNS 里 StaticDns 的值，
// key 是同名的 *_URL 常量去掉 scheme。**URL 那一侧早就换成我们的域名了，IP 这一侧一直是上游的**，
// 于是这张表把「我们的主机名」映射到「Signal 的 IP」——DNS 被污染 / 被墙时（正是最需要这一档的时候）
// 100% 连不上。下面六条已改成解析我们自己的域名。
//
// ⚠️ **别在被污染的 DNS 后面跑这个 task。** 2026-09-23 在 Pro 的 Mac 上实测，
// 对 *.signal.org 的应答是伪造的，**而且每次都不一样**（同一台机器、同一条出口，三次采样）：
//   svr2.signal.org          → 65.49.68.152 / 74.86.151.167 / 199.59.150.12
//   svr2.staging.signal.org  → 31.13.96.194（Facebook 的地址）/ 185.45.7.185
//   chat.signal.org          → 199.59.149.244 / 173.255.209.47（真值是 13.248.212.111 / 76.223.92.165）
//   而 StaticIpResolver 有时直接报 "Failed to resolve host! Lookup did not return any records"。
// **应答不稳定这一点比"被污染"更要紧**：它意味着两个人跑同一个 task 会得到两份不同的文件，
// 而且写进包里之后从产物上完全看不出对错。下面 svr2 / cdsi 两条仍指向上游主机，
// 只能在干净出口上重新生成；我们自己的 *.tellomi.app 在这台机器上解析是干净的（都指向香港 EIP）。
//
// 还有一处**对不上**（本次没动，因为两条路都已关闭、这一档走不到）：
//   SIGNAL_CDSI_URL = https://cdsi.staging.signal.org  ← StaticDns 的 key
//   cdsi_ips        = resolveToBuildConfig("cdsi.signal.org")  ← 值来自**非** staging 的主机
// svr2 同理。要修得连 URL 一起对齐，或者干脆把这两条从 StaticDns 里删掉（#999 / #1030 已把两条路关死）。
tasks.register("resolveStaticIps") {
  group = "Static Files"
  description = "Fetches static IPs for core hosts and writes them to static-ips.properties"

  val projectDirFile = projectDir

  doLast {
    // ⚠️ 这四行**注释必须留在模板外面**。原来它们写在 """...""" 里，会被原样写进
    // static-ips.properties——那是一份 Java Properties 文件，注释符是 `#` 不是 `//`，
    // 于是 `Properties.load()` 会把它们当成键，app/build.gradle.kts 里
    // `staticIps.stringPropertyNames().forEach { rootProject.extra[it] = ... }`
    // 会往 extra 里塞几个垃圾键。（我自己在 tellomi/Signal-Android#6 里留下的，
    // 因为这台机器根本跑不了这个 task，一直没暴露。）
    //
    // cdn3 / updates 在 Cloudflare（橙云），不是香港源站。**边缘 IP 绝不能写死进包里**：
    // 它会变，写死等于给自己做一张会过期的劫持表——DNS 正常时用不到，DNS 失效时反而把流量
    // 送到一个可能早已不属于我们的地址。所以 cdn3 固定给空表，让这一档对它直接"不知道"。
    val hosts = listOf(
      "chat.tellomi.app", "storage.tellomi.app", "cdn.tellomi.app", "cdn2.tellomi.app",
      "contentproxy.tellomi.app", "svr2.signal.org", "cdsi.signal.org"
    )
    // 在被污染的 DNS 后面跑 [resolveStaticIps] 会把**伪造的 IP** 写进 static-ips.properties，
    // 而且从产物上完全看不出来。这个函数是那条路的守卫：跑不成就**中止**，不写文件。
    // 
    // 探针：对每个待解析主机所在的域，查一个**随机的、不存在的**子域。
    // 正常 DNS 必须返回 NXDOMAIN；只要有应答，就说明这条出口在替不存在的名字编造地址，
    // 那么它对真名字给的答案同样不可信。
    // 
    // 2026-09-23 在 Pro 的 Mac 上实测：
    // 
    // nx-probe-8786-29788.signal.org   → 157.240.10.32   （Facebook 的地址）
    // nx-probe-19518-11185.signal.org  → 104.244.46.21   （Twitter 的地址）
    // nx-probe-3916-18826.signal.org   → 199.96.62.41
    // nx-probe-11244.tellomi.app       → 无应答（正常）
    // 
    // 也就是说**同一台机器上，signal.org 被污染而 tellomi.app 没有**——按域分别判，
    // 不是一刀切。同一批采样里真名字的应答**每次都不一样**
    // （svr2.signal.org 三次得到 65.49.68.152 / 74.86.151.167 / 199.59.150.12），
    // 所以两个人跑同一个 task 会得到两份不同的文件。
    // 
    // 判据一律 fail-closed：探针自己出错（超时、解析器抽风）也当成"判不了"而中止，
    // 不往下写——一份写错了看不出来的表，比一次失败的构建糟得多。
    fun assertDnsNotLying(hosts: List<String>) {
      // 取可注册域（最后两级）：chat.tellomi.app → tellomi.app，svr2.signal.org → signal.org。
      // 多级公共后缀（.com.cn 之类）这里用不到，真用到再说，别提前做复杂。
      val domains = hosts.map { it.split(".").takeLast(2).joinToString(".") }.distinct().sorted()
      val liars = mutableListOf<String>()
      for (domain in domains) {
        val probe = "nx-probe-" + java.util.UUID.randomUUID().toString().take(12) + "." + domain
        val answers = try {
          java.net.InetAddress.getAllByName(probe).map { it.hostAddress }
        } catch (e: java.net.UnknownHostException) {
          emptyList()                                  // 正常：这个名字不存在
        } catch (e: Exception) {
          throw GradleException(
            "DNS 探针本身失败了（$probe：$e）——判不了这条出口可不可信，所以不往下写。" +
            "在一条干净的出口上重跑。"
          )
        }
        if (answers.isNotEmpty()) {
          liars += "$domain（$probe → ${answers.joinToString(", ")}）"
        }
      }
      if (liars.isNotEmpty()) {
        throw GradleException(
          "这条出口的 DNS 在说谎，**不会**把解析结果写进 static-ips.properties。\n" +
          liars.joinToString("\n") { "  $it" } + "\n" +
          "随机的、不存在的子域拿到了应答 = 解析器在编造地址，它对真名字给的答案同样不可信。\n" +
          "这张表是 SignalServiceNetworkAccess 里 StaticDns 的值，写错了从产物上看不出来，\n" +
          "而它恰好在 DNS 被污染时才会被用到——写进去等于给自己做一张劫持表。\n" +
          "在一条干净的出口上重跑这个 task（判据：上面那几个 nx-probe-* 必须全部无应答）。"
        )
      }
    }

    assertDnsNotLying(hosts)

    val staticIpResolver = StaticIpResolver()
    // Tellomi (tellomi/tellomi#1687): svr2 / cdsi used to be resolved from Signal's hosts (svr2.signal.org,
    // cdsi.signal.org) and compiled into BuildConfig, but nothing reads SIGNAL_SVR2_IPS / SIGNAL_CDSI_IPS and
    // Tellomi has no SVR2 / CDSI (they point at .invalid). Keep the fields, ship no Signal IPs.
    val content = """
      service_ips=${staticIpResolver.resolveToBuildConfig("chat.tellomi.app")}
      storage_ips=${staticIpResolver.resolveToBuildConfig("storage.tellomi.app")}
      cdn_ips=${staticIpResolver.resolveToBuildConfig("cdn.tellomi.app")}
      cdn2_ips=${staticIpResolver.resolveToBuildConfig("cdn2.tellomi.app")}
      cdn3_ips=new String[]{}
      sfu_ips=${staticIpResolver.resolveToBuildConfig("chat.tellomi.app")}
      content_proxy_ips=${staticIpResolver.resolveToBuildConfig("contentproxy.tellomi.app")}
      svr2_ips=new String[]{}
      cdsi_ips=new String[]{}
    """.trimIndent() + "\n"
    File(projectDirFile, "static-ips.properties").writeText(content)
  }
}

tasks.register("updateStaticFilesAndQa") {
  group = "Static Files"
  description = "Runs tasks to update static files. This includes translations, static IPs, and licenses. Runs QA afterwards to verify all went well. Intended to be run before cutting a release."
  dependsOn("pushTranslations", "pullTranslations", "replaceEllipsis", "cleanApostropheErrors", "excludeNonTranslatables", "resolveStaticIps", "postTranslateQa")
}

// This is a wrapper task just so that we can add a mustRunAfter in the context of the translation tasks.
tasks.register("postTranslateQa") {
  group = "Static Files"
  description = "Runs QA to check validity of updated strings, and ensure presence of any new languages in internal lists."
  mustRunAfter("replaceEllipsis", "cleanApostropheErrors", "excludeNonTranslatables", "resolveStaticIps")
  dependsOn(":qa")
}
