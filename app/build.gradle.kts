@file:Suppress("UnstableApiUsage")

import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.HasAndroidTest
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(testLibs.plugins.compose.screenshot)
  alias(benchmarkLibs.plugins.baselineprofile)
  id("androidx.navigation.safeargs")
  id("kotlin-parcelize")
  id("com.squareup.wire")
  id("translations")
  id("licenses")
}

val staticIps = Properties().apply { file("static-ips.properties").reader().use { load(it) } }
staticIps.stringPropertyNames().forEach { rootProject.extra[it] = staticIps.getProperty(it) }

// Tellomi 自己的版本线（ADR-0024「客户端版本治理」/ TR-PLAT-13）。
// **不跟上游 Signal 的版本号**：用户看到的是「版本 0.1.0」，不是 8.26.4。
// 基于哪个上游 release 记在超级仓库 docs/signal/VERSIONS.md，不占用面向用户的版本号。
// Desktop 走的是同一条线（package.json 0.1.x）。
//
// canonicalVersionCode：ADR 要求的「单调递增整数」，最低版本判定用它。
// **不从 1 重新开始**：现有测试安装的 versionCode 已经是 174801（上游 1748 派生的），
// 降号会让 Android 直接拒绝覆盖升级，测试机得卸载重装、本地聊天记录一起丢。
// 所以从当前值之上继续。以后要重排号，只能挑一个「所有人反正都要重装」的节点。
val canonicalVersionCode = 1754
val canonicalVersionName = "0.1.5"
val currentHotfixVersion = 0
val maxHotfixVersions = 100

// We don't want versions to ever end in 0 so that they don't conflict with nightly versions
val possibleHotfixVersions = (0 until maxHotfixVersions).toList().filter { it % 10 != 0 }

val debugKeystorePropertiesProvider: Provider<Properties> = providers.of(PropertiesFileValueSource::class.java) {
  parameters.file.set(rootProject.layout.projectDirectory.file("keystore.debug.properties"))
}

// Tellomi：release 签名（#921）。上游的 release 档没有 signingConfig（他们在 CI 外部签），
// 我们要出侧载包，所以按 debug 的同一套机制加一个。
//
// **密钥与口令都不在仓库里**：keystore.release.properties 已进 .gitignore，
// 它指向的 .jks 放在 ~/.config/nexi/signal/（0600）。
// 这个文件**不在就跳过**（release 档退回不签名），这样别的机器 / CI 不配置也能编译。
//
// 丢了这把 key = 这个 applicationId 再也发不出能被现有安装接受的更新（Android 按签名认身份）。
// 所以 owner 手上必须有一份离线备份 —— 见 docs/signal/BUILD_ANDROID.md「release 签名」。
val releaseKeystorePropertiesProvider: Provider<Properties> = providers.of(PropertiesFileValueSource::class.java) {
  parameters.file.set(rootProject.layout.projectDirectory.file("keystore.release.properties"))
}

val languagesProvider: Provider<List<String>> = providers.of(LanguageListValueSource::class.java) {
  parameters.resDir.set(layout.projectDirectory.dir("src/main/res"))
}

val languagesForBuildConfigProvider = languagesProvider.map { languages ->
  languages.joinToString(separator = ", ") { language -> "\"$language\"" }
}

val localPropertiesFile = File(rootProject.projectDir, "local.properties")
val localProperties: Properties? = if (localPropertiesFile.exists()) {
  Properties().apply { localPropertiesFile.inputStream().use { load(it) } }
} else {
  null
}
val quickstartCredentialsDir: String? = localProperties?.getProperty("quickstart.credentials.dir")
val benchmarkBackupFile: String? = localProperties?.getProperty("benchmark.backup.file")

val isInstrumentationTestRun = gradle.startParameter.taskNames.any { taskName ->
  val lower = taskName.lowercase()
  lower.contains("androidtest") || lower.contains("connectedcheck")
}

val selectableVariants = listOf(
  "nightlyProdSpinner",
  "nightlyProdPerf",
  "nightlyProdRelease",
  "nightlyStagingRelease",
  "playProdDebug",
  "playProdSpinner",
  "playProdCanary",
  "playProdPerf",
  "playProdMocked",
  "playProdNonMinifiedMocked",
  "playProdBenchmark",
  "playProdRelease",
  "playStagingDebug",
  "playStagingCanary",
  "playStagingSpinner",
  "playStagingPerf",
  "playStagingRelease",
  "playProdQuickstart",
  "playStagingQuickstart",
  "websiteProdSpinner",
  "websiteProdRelease",
  "githubProdSpinner",
  "githubProdRelease"
)

// Wire 5.x iterates Android source sets and expects matching Kotlin source sets.
// AGP 9.0's built-in Kotlin doesn't create all source sets automatically.
val kotlinExt = extensions.getByName("kotlin") as KotlinAndroidProjectExtension
android.sourceSets.all {
  kotlinExt.sourceSets.findByName(name) ?: kotlinExt.sourceSets.create(name)
}
// AGP 9.0's built-in Kotlin doesn't pick up extra java.srcDir entries from Android
// source sets, so add shared dirs directly to the relevant Kotlin compile tasks.
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
  val isTestTask = name.contains("UnitTest") || name.contains("AndroidTest")
  if (isTestTask) {
    source("$projectDir/src/testShared")
  }
  if (!isTestTask && (name.contains("Mocked") || name.contains("Benchmark"))) {
    source("$projectDir/src/benchmarkShared/java")
  }
  if (isTestTask && name.contains("AndroidTest")) {
    source("$projectDir/src/benchmarkShared/java")
  }
}

wire {
  kotlin {
    javaInterop = true
  }

  sourcePath {
    srcDir("src/main/protowire")
  }

  protoPath {
    srcDir("${project.rootDir}/lib/libsignal-service/src/main/protowire")
    srcDir("${project.rootDir}/lib/archive/src/main/protowire")
  }
}

ktlint {
  version.set("1.5.0")
}

// ktlint only scans convention source dirs, so the shared dirs added to the compile tasks are
// otherwise skipped. Add them to the base test/androidTest ktlint tasks so ktlintCheck/format cover them.
tasks.withType(org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask::class.java).configureEach {
  if (name.endsWith("OverTestSourceSet") || name.endsWith("OverAndroidTestSourceSet")) {
    source("$projectDir/src/testShared")
  }
  if (name.endsWith("OverAndroidTestSourceSet")) {
    source("$projectDir/src/benchmarkShared/java")
  }
}

screenshotTests {
  // Fraction of differing pixels tolerated before a screenshot test fails (0.0001 = 0.01%).
  imageDifferenceThreshold = 0.0001f
}

// 产物名：Tellomi-play-staging-arm64-v8a-debug-0.1.0.apk
base {
  archivesName.set("Tellomi")
}

android {
  namespace = "org.thoughtcrime.securesms"

  experimentalProperties["android.experimental.enableScreenshotTest"] = true

  buildToolsVersion = libs.versions.buildTools.get()

  compileSdk {
    version = release(libs.versions.compileSdk.get().toInt())
  }

  ndkVersion = libs.versions.ndk.get()

  flavorDimensions += listOf("distribution", "environment")

  android.bundle.language.enableSplit = false

  debugKeystorePropertiesProvider.get().takeIf { it.isNotEmpty() }?.let { properties ->
    signingConfigs.getByName("debug").apply {
      storeFile = file("${project.rootDir}/${properties.getProperty("storeFile")}")
      storePassword = properties.getProperty("storePassword")
      keyAlias = properties.getProperty("keyAlias")
      keyPassword = properties.getProperty("keyPassword")
    }
  }

  releaseKeystorePropertiesProvider.get().takeIf { it.isNotEmpty() }?.let { properties ->
    signingConfigs.create("release").apply {
      // storeFile 写绝对路径（密钥不在仓库里，没法相对 rootDir 定位）
      storeFile = file(properties.getProperty("storeFile"))
      storePassword = properties.getProperty("storePassword")
      keyAlias = properties.getProperty("keyAlias")
      keyPassword = properties.getProperty("keyPassword")
    }
  }

  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"

    unitTests {
      isIncludeAndroidResources = true
    }

    managedDevices {
      localDevices {
        create("pixel3api30") {
          device = "Pixel 3"
          apiLevel = 30
          systemImageSource = "google-atd"
          require64Bit = false
        }
      }
    }
  }

  sourceSets {
    getByName("test") {
      java.directories += "$projectDir/src/testShared"
    }

    getByName("androidTest") {
      java.directories += "$projectDir/src/testShared"
      java.directories += "$projectDir/src/benchmarkShared/java"
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  }

  packaging {
    jniLibs {
      excludes += setOf(
        "**/*.dylib",
        "**/*.dll"
      )
    }
    resources {
      excludes += setOf(
        "LICENSE.txt",
        "LICENSE",
        "NOTICE",
        "asm-license.txt",
        "META-INF/LICENSE",
        "META-INF/LICENSE.md",
        "META-INF/NOTICE",
        "META-INF/LICENSE-notice.md",
        "META-INF/proguard/androidx-annotations.pro",
        "**/*.dylib",
        "**/*.dll",
        "**/*.proto"
      )
    }
  }

  buildFeatures {
    buildConfig = true
    viewBinding = true
    compose = true
    // Tellomi：为了按档注入 @string/contact_account_type（见 defaultConfig 的注释）。
    // AGP 默认关掉 resValues，不开这个开关 defaultConfig 里写 resValue 会直接配置失败。
    resValues = true
  }

  defaultConfig {
    if (currentHotfixVersion >= maxHotfixVersions) {
      throw AssertionError("Hotfix version offset is too large!")
    }
    versionCode = (canonicalVersionCode * maxHotfixVersions) + possibleHotfixVersions[currentHotfixVersion]
    versionName = canonicalVersionName

    // Tellomi：上游没写 applicationId，于是它默认等于 namespace（org.thoughtcrime.securesms）。
    // 商店身份按 ADR-0019 定为 app.tellomi；**2026-09-23 owner 改成 app.tellomi.chat**，
    // 与 iOS 对齐（iOS 那边 app.tellomi 系列被两个免费个人团队占着，见 docs/signal/APPLE_TEAMS.md）。
    // ⚠️ 改包名 = 换身份：Android 按「包名 + 签名」认安装，**现有安装收不到这个包的升级**，
    //    必须卸载重装（本地聊天记录会丢）。现在没有正式用户，这个代价 owner 已确认可以付。
    // ⚠️ Firebase 也要跟着：google-services.json 里必须有 package_name=app.tellomi.chat 的 client，
    //    否则 scripts/release/publish-android.sh 的注入这一步会直接 fail（fail-closed，不会静默）。
    // namespace 不动——那是 Java 包名与 R 类的位置，
    // 改它等于把整棵源码树搬家，跟品牌无关，而且会让以后合并上游变得极痛苦。
    // provider 的 authority、自定义权限、FileProvider 全都是从 applicationId 派生的
    // （manifest 里是 ${'$'}{applicationId}.xxx，代码里是 BuildConfig.APPLICATION_ID），所以改这一处就够。
    applicationId = "app.tellomi.chat"

    // 系统联系人账号的 accountType 必须与代码里用的那一个一字不差（代码用 BuildConfig.APPLICATION_ID），
    // 否则 AccountManager 不认。上游把它硬写在 res/xml/{authenticator,syncadapter}.xml 里，
    // 再用 src/staging/ 的一整份副本改成带 .staging 的那个——每加一个后缀就要再抄一份。
    // 改成按档注入一个字符串资源，xml 只引用它，staging 那两份副本就可以删掉了。
    // （试过 onVariants 里的 variant.applicationId：实测拿到的是 namespace 派生值而不是这里的
    //   applicationId，产物里核出来还是 org.thoughtcrime.securesms.staging，所以不用它。）
    // test_run / benchmark 这两个后缀不覆盖：那是测试变体，不跑联系人同步。
    resValue("string", "contact_account_type", "app.tellomi.chat")

    // Tellomi：这套部署有没有 SVR（SGX enclave）。见 docs/signal/ENCLAVES.md 与
    // NetworkController.svrEnclaveAvailable。prod 档保持上游行为（连 Signal 自己的 svr2）；
    // staging 档指的是我们自建的香港服务端，那里没有 enclave。
    buildConfigField("boolean", "SVR_ENCLAVE_AVAILABLE", "false")
    // 同上，CDSI（按手机号找人）也是 SGX enclave。见 docs/signal/ENCLAVES.md。
    buildConfigField("boolean", "CDSI_AVAILABLE", "false")
    // 同上，key transparency 也是独立服务。见 docs/signal/ENCLAVES.md。
    buildConfigField("boolean", "KEY_TRANSPARENCY_AVAILABLE", "false")

    if (isInstrumentationTestRun) {
      applicationIdSuffix = ".test_run"
    }

    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()

    vectorDrawables.useSupportLibrary = true
    // 上游这里写的是 project.ext.set("archivesBaseName", "Signal")，**早就不起作用了**：
    // Gradle 8 删掉了 archivesBaseName 这个约定属性，往 ext 里塞同名值没人读。
    // 产物名实际来自 Gradle 模块名（settings.gradle.kts 把 :app 改名成 Signal-Android），
    // 所以包一直叫 Signal-Android-play-staging-….apk（owner 2026-09-22 指出）。
    // 模块名有 build.gradle.kts / CI 多处引用，不动；改现行的 base.archivesName。

    // Tellomi（#1235）：上游这里写死的是 **Signal 自己的** Google Maps key——用它等于让用户选点、查地址的请求
    // 走 Signal 的 Google 账号和配额，法律文件里也没有这个接收方。现在只认 getMapsKey()（gradle 属性 mapsKey /
    // 环境变量 MAPS_KEY），没有就为空：MAPS_AVAILABLE = false，附件面板的「位置」置灰、点了提示即将支持。
    // owner 2026-09-24：等高德（#1124），暂时不开谷歌账单。
    val tellomiMapsKey = getMapsKey()
    manifestPlaceholders["mapsKey"] = tellomiMapsKey
    buildConfigField("boolean", "MAPS_AVAILABLE", "${tellomiMapsKey.isNotEmpty()}")

    buildConfigField("long", "BUILD_TIMESTAMP", getLastCommitTimestamp() + "L")
    buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
    // ── Tellomi：自建服务端的端点 / 参数（#1023）────────────────────────────────
    // 这些值**必须放在 defaultConfig**，不能只放在某个 flavor 上。
    // 2026-09-22 的教训：它们原来只覆盖在 `staging` flavor 上，于是
    // `assembleWebsiteProdRelease` 打出来的包（= 发到 updates.tellomi.app 给用户下载的那个 0.1.0）
    // 连的是 **Signal 的生产服务端**（storage / cdn / cdsi / svr2 全是 signal.org）。
    // 而我们所有「跑通」的证据用的都是 staging 档（包名 app.tellomi.staging），
    // **测的从来不是发出去的那个包**，所以一直没暴露。
    // 门禁在 scripts/release/publish-android.sh：打完包对 DEX 做端点 grep，
    // 出现 signal.org 的服务端点就直接失败（带反向判据）。
    // staging flavor 里那份同名覆盖保留着（值一样），改的时候两边一起改。
    // 动态资源（emoji 数据与搜索索引 · 故事字体 · 通话 DRED 权重）的主机。
    // 上游把它写死在 s3/S3.kt 与 service/webrtc/CallingAssets.kt 两处（后者是整条 URL），
    // 收成一个常量是为了：① 两处不会再各走各的；② check-client-constants.py 能查它（#1017）。
    buildConfigField("String", "UPDATES2_HOST", "\"updates.tellomi.app\"")
    buildConfigField("String", "SIGNAL_URL", "\"https://chat.tellomi.app\"")
    buildConfigField("String", "STORAGE_URL", "\"https://storage.tellomi.app\"")
    buildConfigField("String", "SIGNAL_CDN_URL", "\"https://cdn.tellomi.app\"")
    buildConfigField("String", "SIGNAL_CDN2_URL", "\"https://cdn2.tellomi.app\"")
    buildConfigField("String", "SIGNAL_CDN3_URL", "\"https://cdn3.tellomi.app\"")
    buildConfigField("String", "SIGNAL_CDSI_URL", "\"https://cdsi.tellomi.invalid\"")
    // 服务故障探测（ServiceOutageDetectionJob）：对这个名字做 DNS 解析，127.0.0.1 = 正常、
    // 127.0.0.2 = 挂「服务故障」横幅。上游值是 Signal 自己的 uptime 主机，那是 **Signal 的**运维信号——
    // 境外会跟着 Signal 的故障挂横幅；大陆那个名字被 DNS 污染（随机公网 IP），探测永远判不出结果，
    // 还每轮连查 5 次一个被封域名（#1101）。记录还没建时是 UnknownHost → 重试 → 按「正常」收场，
    // 和今天大陆的实际行为一样，只是不再去问 Signal。
    // （故意不写上游的字面主机名：#1101 的判据之一是源码 grep 它 = 0。）
    buildConfigField("String", "SIGNAL_SERVICE_STATUS_URL", "\"uptime.tellomi.app\"")
    buildConfigField("String", "SIGNAL_SVR2_URL", "\"https://svr2.tellomi.invalid\"")
    // 群通话的 SFU 走我们自己的（docs/signal/BUILD_CALLING.md）：香港那台上 calling_frontend
    // 听 127.0.0.1:9010，nginx 以 /callingService/ 暴露；Desktop 的 config/production.json
    // 早就写的是这个地址，Android 这边一直还是上游的 —— 而 InternalValues.groupCallingServer
    // 默认就取 SIGNAL_SFU_URL，所以群通话的媒体一直在经 Signal 的服务器中转。
    //
    // 它和 CDSI / SVR2 那两条不是一回事：那两条有 *_AVAILABLE 开关整条关掉，是惰性常量；
    // 这条没有任何开关挡着。
    buildConfigField("String", "SIGNAL_SFU_URL", "\"https://chat.tellomi.app/callingService\"")
    buildConfigField("String", "SIGNAL_STAGING_SFU_URL", "\"https://chat.tellomi.app/callingService\"")
    // 内部调试菜单里的备选 SFU：我们只有一套，列三个上游地址只会把人导到连不上的服务器
    // （而且它们会作为字符串留在包里）。留空——菜单里选主 SFU 的那一项还在。
    buildConfigField("String[]", "SIGNAL_SFU_INTERNAL_NAMES", "new String[]{}")
    buildConfigField("String[]", "SIGNAL_SFU_INTERNAL_URLS", "new String[]{}")
    // Tellomi（#1072）：GIF 搜索 / 播放和**所有走 Glide 的远程图片**（链接预览缩略图等）都经这台代理中转
    // （ContentProxySelector → GiphyMp4Repository / GiphyMp4PagedDataSource / OkHttpUrlLoader / ChunkedImageUrlLoader）。
    // 留着上游的话，我们用户的 GIF 关键词和 IP 就打到 Signal 的代理上，用的还是 Signal 写在客户端里的那把 Giphy key。
    // 我们自己的那台在香港（deploy/hk/install-contentproxy.sh：nginx stream 按有没有 SNI 分流到 tinyproxy，
    // 只放行 giphy 域名）。Desktop 在 #1065 已经换过去了（contentProxyUrl = https://contentproxy.tellomi.app:443）。
    //
    // **这是编译期兜底值**，不是最终来源：ADR-0064 §4.4 定的是服务端 remoteConfig 下发 `gif.proxyUrl` /
    // `gif.apiKey`，客户端有值用值、无值回退到这里。Android 侧接 remoteConfig 是另一件（见 #1072 的跟进）。
    //
    // 这一行**只在 defaultConfig 里出现一次，没有任何 flavor 覆盖**（全文件 grep CONTENT_PROXY_HOST 就这一处），
    // 所以改这里就够——和 #1023 的形状一样。
    buildConfigField("String", "CONTENT_PROXY_HOST", "\"contentproxy.tellomi.app\"")
    buildConfigField("int", "CONTENT_PROXY_PORT", "443")
    buildConfigField("String[]", "SIGNAL_SERVICE_IPS", rootProject.extra["service_ips"] as String)
    buildConfigField("String[]", "SIGNAL_STORAGE_IPS", rootProject.extra["storage_ips"] as String)
    buildConfigField("String[]", "SIGNAL_CDN_IPS", rootProject.extra["cdn_ips"] as String)
    buildConfigField("String[]", "SIGNAL_CDN2_IPS", rootProject.extra["cdn2_ips"] as String)
    buildConfigField("String[]", "SIGNAL_CDN3_IPS", rootProject.extra["cdn3_ips"] as String)
    buildConfigField("String[]", "SIGNAL_SFU_IPS", rootProject.extra["sfu_ips"] as String)
    buildConfigField("String[]", "SIGNAL_CONTENT_PROXY_IPS", rootProject.extra["content_proxy_ips"] as String)
    buildConfigField("String[]", "SIGNAL_CDSI_IPS", rootProject.extra["cdsi_ips"] as String)
    buildConfigField("String[]", "SIGNAL_SVR2_IPS", rootProject.extra["svr2_ips"] as String)
    buildConfigField("String", "SIGNAL_AGENT", "\"OWA\"")
    buildConfigField("String", "SVR2_MRENCLAVE_LEGACY", "\"97f151f6ed078edbbfd72fa9cae694dcc08353f1f5e8d9ccd79a971b10ffc535\"")
    buildConfigField("String", "SVR2_MRENCLAVE", "\"3c699f4975aaa3d172c0aad042f94f031b2b03e10b9c19a45116a01693d83302\"")
    buildConfigField("String[]", "UNIDENTIFIED_SENDER_TRUST_ROOTS", "new String[]{\"BcLYlMOrgCUTLuLXSvW5I1FiBAub5uoawfHDNzrzyNg3\"}")
    buildConfigField("String", "ZKGROUP_SERVER_PUBLIC_PARAMS", "\"ADKO0hJxgxBky6XbESgxS+kxUo0+0fZinOfVIZfT+Chs1lBx0vRYvMzp+gUsbOpVRfsRMfdSwHq4GdExEyIMDhwamT0uT7OyL25KHXgAUXu56vWApb8c1mgdsd6GbfyfAOqGNAR7e8emQLSivHo+oYJVciuQVAznjbWdtjpvXMs3QMxitzmOcxhskuV+E6Md8BNIIqK6kviBf6GTVVJRIGr655FBcPf89L6Iva9ZEirT0pzQPBcGZ7mkzq56khyHLVxx3VzxCDjEUhPrco1Y4yfNqE7WA8JKi8dXA3pEslk8ztjXZ2C3nOIl1DnsaOJytFo14Gjri895dlDHCvY0s25sJlep3NrCO6U4imVVFhmc77y0dbn2FTwnOwefltRFQxz5yVvD4y50n8f6zZblT6u6w7YSdkf9glpzJOSYMdxPnHtWzzgQxPfqms2JYimZRakIqPcfnb3JIFTJqFITI3/E966PuFsB2kgZUNa+L9WUnhEw9utSJeFGltuX4IYIPPIWe1htCnrAyQgmpSoEwHseozG+FPol+YF4Hqd/WyA0DrPNr1749cXRkfwM+dNwrE59LkBf8Fp5UhtlyUW21E6wA4MLRAc2uoPXRUFzTcVykb42EYI/sAOWIfT103RvUhQfSBDYCj8uMYOonZ9fpkIL0u6zLt8zUE4CwjfbLAZ0bkKdS/baN5UMlq8cd3HZa09mvvLuL0Grl5mqRIRhUD4EhX8sVZHOzbof0Rc5JL4GvI4QOCObatrKg73D5prfLqyaJPb0TxuACl2S5fnNHs5FmqkDEw62yiUsrw5f8XYUTq5Y85s9MXBSjMUCGg+davkwDlmm1A4gqHuTzbcgGBRIh1iqWJ93cBq+uWsnMhSSdPbv4l8FUJwpruaZEj6FSw\"")
    buildConfigField("String", "GENERIC_SERVER_PUBLIC_PARAMS", "\"AP56nq1D39Uj1w+IDJCLdI4Eu9oqaEVKGELsl9HUlLxsflrRKB+gotelWnixnUDG+8yux4794uDsyWQlGKp1xHq2UH8NB+3VDJWJ1TC0Dp9TaBv3Mm8u09WiycRRslZjQ8qr62rztIru/9qbgsm9nbsgnlu4eKIBSG0BTeNN+MEC0jzZyDBNeG+liynQyFBCAhgzT1Q9a4iBr451Su97P1hO4IQmKDPNwQtulriKtlLBXkqFmzL8GlZpyX1x0NRZDsimO8t+kEmovoAf+Ybbhe12PMQfVWS9IB+kJrOs+00r\"")
    buildConfigField("String", "BACKUP_SERVER_PUBLIC_PARAMS", "\"AHYrGb9IfugAAJiPKp+mdXUx+OL9zBolPYHYQz6GI1gWjpEu5me3zVNSvmYY4zWboZHif+HG1sDHSuvwFd0QszSwuSF4X4kRP3fJREdTZ5MCR0n55zUppTwfHRW2S4sdQ0JGz7YDQIJCufYSKh0pGNEHL6hv79Agrdnr4momr3oXdnkpVBIp3HWAQ6IbXQVSG18X36GaicI1vdT0UFmTwU2KTneluC2eyL9c5ff8PcmiS+YcLzh0OKYQXB5ZfQ06d6DiINvDQLy75zcfUOniLAj0lGJiHxGczin/RXisKSR8\"")
    buildConfigField("String[]", "LANGUAGES", "new String[]{ ${languagesForBuildConfigProvider.get()} }")
    buildConfigField("int", "CANONICAL_VERSION_CODE", "$canonicalVersionCode")
    buildConfigField("String", "DEFAULT_CURRENCIES", "\"EUR,AUD,GBP,CAD,CNY\"")
    buildConfigField("String", "SIGNAL_CAPTCHA_URL", "\"https://chat.tellomi.app/captcha-tellomi/registration/generate.html\"")
    buildConfigField("String", "RECAPTCHA_PROOF_URL", "\"https://chat.tellomi.app/captcha-tellomi/challenge/generate.html\"")
    buildConfigField("org.signal.libsignal.net.Network.Environment", "LIBSIGNAL_NET_ENV", "org.signal.libsignal.net.Network.Environment.STAGING")
    // 自建服务端的 libsignal 主机；默认空 = 用上面的 LIBSIGNAL_NET_ENV（Signal 官方环境）。staging flavor 会覆盖它。
    buildConfigField("String", "LIBSIGNAL_CUSTOM_SERVER_HOST", "\"grpc.chat.tellomi.app\"")
    buildConfigField("int", "LIBSIGNAL_CUSTOM_SERVER_PORT", "443")
    buildConfigField("int", "LIBSIGNAL_LOG_LEVEL", "org.signal.libsignal.protocol.logging.SignalProtocolLogger.INFO")

    buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"unset\"")
    buildConfigField("String", "BUILD_ENVIRONMENT_TYPE", "\"unset\"")
    buildConfigField("String", "BUILD_VARIANT_TYPE", "\"unset\"")
    // Tellomi（#1017）：徽章图片也走我们自己的更新源。
    //
    // 这条和 UPDATES2_HOST 那几条不一样，值得写清楚：**它不受 DONATIONS_ENABLED 控制**。
    // 拼 URL 的 Badges.fromServiceBadge() 是被 RetrieveProfileJob / RefreshOwnProfileJob 调的——
    // 也就是**每次拉任何人的 profile 都会走**，只要服务端在 profile 里回了 badges 就会去取图。
    // 捐赠入口关掉只是让用户点不到捐赠页，挡不住这条路。
    //
    // 我们的服务端目前应该不会签发徽章（没有订阅后端），但那是**服务端的状态**、不是客户端保证，
    // 而且我在这台机器上核不了（服务端配置在香港主机上）。所以不赌它：指向我们自己的主机，
    // 哪怕镜像还没铺好（updates.tellomi.app/static/badges/ 现在是 404）。
    // 404 的后果是徽章图片加载不出来；指着上游的后果是用户 IP 直接落到 Signal 的 CDN 上。
    // 前者可接受，后者正是 #1023 / #1030 那一类问题。
    buildConfigField("String", "BADGE_STATIC_ROOT", "\"https://updates.tellomi.app/static/badges/\"")
    buildConfigField("String", "STRIPE_BASE_URL", "\"https://api.stripe.com/v1\"")
    buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_sngOd8FnXNkpce9nPXawKrJD00kIDngZkD\"")
    buildConfigField("boolean", "TRACING_ENABLED", "false")
    buildConfigField("boolean", "LINK_DEVICE_UX_ENABLED", "false")

    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    }
    resourceConfigurations += listOf()

    splits {
      abi {
        isEnable = !project.hasProperty("generateBaselineProfile")
        reset()
        include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        isUniversalApk = true
      }
    }

    testInstrumentationRunner = if (project.hasProperty("imoTests")) {
      "org.thoughtcrime.securesms.testing.incomingmessageobserver.IncomingMessageObserverTestRunner"
    } else {
      "org.thoughtcrime.securesms.testing.SignalTestRunner"
    }
    testInstrumentationRunnerArguments["clearPackageData"] = "true"
  }

  buildTypes {
    getByName("debug") {
      if (debugKeystorePropertiesProvider.orNull != null) {
        signingConfig = signingConfigs["debug"]
      }
      isDefault = true
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard/proguard-firebase-messaging.pro",
        "proguard/proguard-google-play-services.pro",
        "proguard/proguard-jackson.pro",
        "proguard/proguard-sqlite.pro",
        "proguard/proguard-square-okhttp.pro",
        "proguard/proguard-square-okio.pro",
        "proguard/proguard-rounded-image-view.pro",
        "proguard/proguard-shortcutbadger.pro",
        "proguard/proguard-retrofit.pro",
        "proguard/proguard-klinker.pro",
        "proguard/proguard-mobilecoin.pro",
        "proguard/proguard-retrolambda.pro",
        "proguard/proguard-okhttp.pro",
        "proguard/proguard-ez-vcard.pro",
        "proguard/proguard-dnsjava.pro",
        "proguard/proguard.cfg"
      )
      testProguardFiles(
        "proguard/proguard-automation.pro",
        "proguard/proguard.cfg"
      )

      manifestPlaceholders["mapsKey"] = getMapsKey()

      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Debug\"")
      buildConfigField("boolean", "LINK_DEVICE_UX_ENABLED", "true")
    }

    getByName("release") {
      isMinifyEnabled = true
      proguardFiles(*buildTypes["debug"].proguardFiles.toTypedArray())
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Release\"")
      // Tellomi：配了 keystore.release.properties 才签；没配就不签（别的机器仍能编译出未签名包）。
      signingConfigs.findByName("release")?.let { signingConfig = it }
    }

    create("spinner") {
      initWith(getByName("debug"))
      isDefault = false
      isMinifyEnabled = false
      matchingFallbacks += "debug"
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Spinner\"")
    }

    create("perf") {
      initWith(getByName("debug"))
      isDefault = false
      isDebuggable = false
      isMinifyEnabled = true
      matchingFallbacks += "debug"
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Perf\"")
      buildConfigField("boolean", "TRACING_ENABLED", "true")
    }

    create("benchmark") {
      initWith(getByName("debug"))
      isDefault = false
      isDebuggable = false
      isMinifyEnabled = true
      matchingFallbacks += "debug"
      applicationIdSuffix = ".benchmark"

      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Benchmark\"")
      buildConfigField("boolean", "TRACING_ENABLED", "true")
      buildConfigField("String[]", "UNIDENTIFIED_SENDER_TRUST_ROOTS", "new String[]{ \"BVT/2gHqbrG1xzuIypLIOjFgMtihrMld1/5TGADL6Dhv\"}")

      manifestPlaceholders["applicationClass"] = "org.thoughtcrime.securesms.BenchmarkApplicationContext"
    }

    create("mocked") {
      initWith(getByName("debug"))
      isDefault = false
      isDebuggable = false
      isMinifyEnabled = true
      isShrinkResources = true
      matchingFallbacks += "debug"
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Benchmark\"")
      buildConfigField("boolean", "TRACING_ENABLED", "true")

      manifestPlaceholders["applicationClass"] = "org.thoughtcrime.securesms.ApplicationContext"
    }

    create("canary") {
      initWith(getByName("debug"))
      isDefault = false
      isMinifyEnabled = false
      matchingFallbacks += "debug"
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Canary\"")
    }

    create("quickstart") {
      initWith(getByName("debug"))
      isDefault = false
      isMinifyEnabled = false
      matchingFallbacks += "debug"
      buildConfigField("String", "BUILD_VARIANT_TYPE", "\"Quickstart\"")
    }
  }

  productFlavors {
    create("play") {
      dimension = "distribution"
      isDefault = true
      buildConfigField("boolean", "MANAGES_APP_UPDATES", "false")
      buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "null")
      buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"play\"")
    }

    create("website") {
      dimension = "distribution"
      // Tellomi 侧载走的就是这个 flavor（#921）：iOS 没有付费开发者账号，Android 也不上架，
      // 所以对外发的包必须是 website 档——只有它 MANAGES_APP_UPDATES=true，装了旧版才会弹更新提示。
      // 清单格式由 ApkUpdateJob.UpdateDescriptor 定死：{versionCode, versionName, url, sha256sum, uploadTimestamp}，
      // **不是**三端共用的 versions.json（那份给官网下载页看）。两份都由 scripts/release/publish-android.sh 写。
      buildConfigField("boolean", "MANAGES_APP_UPDATES", "true")
      buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "\"https://updates.tellomi.app/android/latest.json\"")
      buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"website\"")
    }

    create("github") {
      dimension = "distribution"
      buildConfigField("boolean", "MANAGES_APP_UPDATES", "false")
      buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "null")
      buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"github\"")
    }

    create("nightly") {
      dimension = "distribution"
      versionNameSuffix = "-nightly-untagged-${getGitHash()}"
      buildConfigField("boolean", "MANAGES_APP_UPDATES", "false")
      buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "null")
      buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"nightly\"")
      buildConfigField("boolean", "LINK_DEVICE_UX_ENABLED", "true")
    }

    create("prod") {
      dimension = "environment"

      isDefault = true

      buildConfigField("String", "MOBILE_COIN_ENVIRONMENT", "\"mainnet\"")
      buildConfigField("String", "BUILD_ENVIRONMENT_TYPE", "\"Prod\"")
    }

    create("staging") {
      dimension = "environment"

      applicationIdSuffix = ".staging"

      buildConfigField("String", "SIGNAL_URL", "\"https://chat.tellomi.app\"")
      buildConfigField("String", "STORAGE_URL", "\"https://storage.tellomi.app\"")
      buildConfigField("String", "SIGNAL_CDN_URL", "\"https://cdn.tellomi.app\"")
      buildConfigField("String", "SIGNAL_CDN2_URL", "\"https://cdn2.tellomi.app\"")
      buildConfigField("String", "SIGNAL_CDN3_URL", "\"https://cdn3.tellomi.app\"")
      buildConfigField("String", "SIGNAL_CDSI_URL", "\"https://cdsi.tellomi.invalid\"")
      buildConfigField("String", "SIGNAL_SVR2_URL", "\"https://svr2.tellomi.invalid\"")
      buildConfigField("String", "SVR2_MRENCLAVE_LEGACY", "\"97f151f6ed078edbbfd72fa9cae694dcc08353f1f5e8d9ccd79a971b10ffc535\"")
      buildConfigField("String", "SVR2_MRENCLAVE", "\"3c699f4975aaa3d172c0aad042f94f031b2b03e10b9c19a45116a01693d83302\"")
      buildConfigField("String[]", "UNIDENTIFIED_SENDER_TRUST_ROOTS", "new String[]{\"BcLYlMOrgCUTLuLXSvW5I1FiBAub5uoawfHDNzrzyNg3\"}")
      buildConfigField("String", "ZKGROUP_SERVER_PUBLIC_PARAMS", "\"ADKO0hJxgxBky6XbESgxS+kxUo0+0fZinOfVIZfT+Chs1lBx0vRYvMzp+gUsbOpVRfsRMfdSwHq4GdExEyIMDhwamT0uT7OyL25KHXgAUXu56vWApb8c1mgdsd6GbfyfAOqGNAR7e8emQLSivHo+oYJVciuQVAznjbWdtjpvXMs3QMxitzmOcxhskuV+E6Md8BNIIqK6kviBf6GTVVJRIGr655FBcPf89L6Iva9ZEirT0pzQPBcGZ7mkzq56khyHLVxx3VzxCDjEUhPrco1Y4yfNqE7WA8JKi8dXA3pEslk8ztjXZ2C3nOIl1DnsaOJytFo14Gjri895dlDHCvY0s25sJlep3NrCO6U4imVVFhmc77y0dbn2FTwnOwefltRFQxz5yVvD4y50n8f6zZblT6u6w7YSdkf9glpzJOSYMdxPnHtWzzgQxPfqms2JYimZRakIqPcfnb3JIFTJqFITI3/E966PuFsB2kgZUNa+L9WUnhEw9utSJeFGltuX4IYIPPIWe1htCnrAyQgmpSoEwHseozG+FPol+YF4Hqd/WyA0DrPNr1749cXRkfwM+dNwrE59LkBf8Fp5UhtlyUW21E6wA4MLRAc2uoPXRUFzTcVykb42EYI/sAOWIfT103RvUhQfSBDYCj8uMYOonZ9fpkIL0u6zLt8zUE4CwjfbLAZ0bkKdS/baN5UMlq8cd3HZa09mvvLuL0Grl5mqRIRhUD4EhX8sVZHOzbof0Rc5JL4GvI4QOCObatrKg73D5prfLqyaJPb0TxuACl2S5fnNHs5FmqkDEw62yiUsrw5f8XYUTq5Y85s9MXBSjMUCGg+davkwDlmm1A4gqHuTzbcgGBRIh1iqWJ93cBq+uWsnMhSSdPbv4l8FUJwpruaZEj6FSw\"")
      buildConfigField("String", "GENERIC_SERVER_PUBLIC_PARAMS", "\"AP56nq1D39Uj1w+IDJCLdI4Eu9oqaEVKGELsl9HUlLxsflrRKB+gotelWnixnUDG+8yux4794uDsyWQlGKp1xHq2UH8NB+3VDJWJ1TC0Dp9TaBv3Mm8u09WiycRRslZjQ8qr62rztIru/9qbgsm9nbsgnlu4eKIBSG0BTeNN+MEC0jzZyDBNeG+liynQyFBCAhgzT1Q9a4iBr451Su97P1hO4IQmKDPNwQtulriKtlLBXkqFmzL8GlZpyX1x0NRZDsimO8t+kEmovoAf+Ybbhe12PMQfVWS9IB+kJrOs+00r\"")
      buildConfigField("String", "BACKUP_SERVER_PUBLIC_PARAMS", "\"AHYrGb9IfugAAJiPKp+mdXUx+OL9zBolPYHYQz6GI1gWjpEu5me3zVNSvmYY4zWboZHif+HG1sDHSuvwFd0QszSwuSF4X4kRP3fJREdTZ5MCR0n55zUppTwfHRW2S4sdQ0JGz7YDQIJCufYSKh0pGNEHL6hv79Agrdnr4momr3oXdnkpVBIp3HWAQ6IbXQVSG18X36GaicI1vdT0UFmTwU2KTneluC2eyL9c5ff8PcmiS+YcLzh0OKYQXB5ZfQ06d6DiINvDQLy75zcfUOniLAj0lGJiHxGczin/RXisKSR8\"")
      buildConfigField("String", "MOBILE_COIN_ENVIRONMENT", "\"testnet\"")
      // 自建服务端：香港 nginx 上的开发用 captcha 页（打开即跳 signalcaptcha://noop...，服务端 stub 接受）。
      // 上生产换成真 hCaptcha（服务端 captcha.allowHCaptcha + site keys）。
      // 2026-09-22 #882：切到回调 `tellomicaptcha://` 的那一份（客户端已能同时接住新旧两种回调，
      // 见 util/TellomiLinks.kt）。旧的 /captcha/… 回 `signalcaptcha://`，三端切完前服务端仍保留。
      buildConfigField("String", "SIGNAL_CAPTCHA_URL", "\"https://chat.tellomi.app/captcha-tellomi/registration/generate.html\"")
      buildConfigField("String", "RECAPTCHA_PROOF_URL", "\"https://chat.tellomi.app/captcha-tellomi/challenge/generate.html\"")
      buildConfigField("org.signal.libsignal.net.Network.Environment", "LIBSIGNAL_NET_ENV", "org.signal.libsignal.net.Network.Environment.STAGING")
      // 自建服务端：libsignal 的 chat 连接走 Omnibus 主机（与上面的 REST 主机不是同一台虚拟主机）。
      // 空字符串 = 沿用上面的 LIBSIGNAL_NET_ENV（Signal 自己的 staging）；非空 = Network.customServer(...)。
      buildConfigField("String", "LIBSIGNAL_CUSTOM_SERVER_HOST", "\"grpc.chat.tellomi.app\"")
      buildConfigField("int", "LIBSIGNAL_CUSTOM_SERVER_PORT", "443")
      buildConfigField("int", "LIBSIGNAL_LOG_LEVEL", "org.signal.libsignal.protocol.logging.SignalProtocolLogger.DEBUG")

      resValue("string", "contact_account_type", "app.tellomi.chat.staging")
      buildConfigField("boolean", "SVR_ENCLAVE_AVAILABLE", "false")
      buildConfigField("boolean", "CDSI_AVAILABLE", "false")
      buildConfigField("boolean", "KEY_TRANSPARENCY_AVAILABLE", "false")
      buildConfigField("String", "BUILD_ENVIRONMENT_TYPE", "\"Staging\"")
      buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"pk_test_sngOd8FnXNkpce9nPXawKrJD00kIDngZkD\"")
    }
  }

  lint {
    abortOnError = true
    baseline = file("lint-baseline.xml")
    checkReleaseBuilds = false
    ignoreWarnings = true
    quiet = true
    disable += "LintError"
    lintConfig = rootProject.file("lint.xml")
  }

  val releaseDir = "$projectDir/src/release/java"
  val debugDir = "$projectDir/src/debug/java"

  android.buildTypes.configureEach {
    val path = if (name == "release") releaseDir else debugDir
    sourceSets.named(name) {
      java.directories += path
    }
  }

  sourceSets {
    getByName("mocked") {
      java.directories += "$projectDir/src/benchmarkShared/java"
      manifest.srcFile("$projectDir/src/benchmarkShared/AndroidManifest.xml")
    }

    getByName("benchmark") {
      java.directories += "$projectDir/src/benchmarkShared/java"
      manifest.srcFile("$projectDir/src/benchmarkShared/AndroidManifest.xml")
    }
  }
}

androidComponents {
  beforeVariants { variant ->
    variant.enable = variant.name in selectableVariants
    if (variant.enable) {
      (variant as? com.android.build.api.variant.HasUnitTestBuilder)?.enableUnitTest = true
    }
  }
  onVariants(selector().all()) { variant: com.android.build.api.variant.ApplicationVariant ->
    // Rename APK to include version name
    val renameTask = tasks.register<RenameApkTask>("renameApk${variant.name.replaceFirstChar { it.uppercase() }}")
    val renameRequest = variant.artifacts.use(renameTask)
      .wiredWithDirectories(RenameApkTask::apkFolder, RenameApkTask::outFolder)
      .toTransformMany(SingleArtifact.APK)
    renameTask.configure {
      transformationRequest.set(renameRequest)
    }

    // Include the test-only library on non-release builds.
    if (variant.buildType == "release") {
      variant.packaging.jniLibs.excludes.add("**/libsignal_jni_testing.so")
      variant.androidResources.ignoreAssetsPatterns.add("libsignal-testing.md")
    }

    // Starting with minSdk 23, Android leaves native libraries uncompressed, which is fine for the Play Store, but not for our self-distributed APKs.
    // This reverts it to the legacy behavior, compressing the native libraries, and drastically reducing the APK file size.
    if (variant.name.contains("website", ignoreCase = true) || variant.name.contains("github", ignoreCase = true)) {
      variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }

    // Version overrides
    if (variant.name.contains("nightly", ignoreCase = true)) {
      var tag = getNightlyTagForCurrentCommit()
      if (!tag.isNullOrEmpty()) {
        if (tag.startsWith("v")) {
          tag = tag.substring(1)
        }

        // We add a multiple of maxHotfixVersions to nightlies to ensure we're always at least that many versions ahead
        val nightlyBuffer = (5 * maxHotfixVersions)
        val nightlyVersionCode = (canonicalVersionCode * maxHotfixVersions) + (getNightlyBuildNumber(tag) * 10) + nightlyBuffer

        variant.outputs.forEach { output ->
          output.versionName.set("$tag | ${getLastCommitDateTimeUtc()}")
          output.versionCode.set(nightlyVersionCode)
        }
      }
    }
  }

  onVariants(selector().withBuildType("quickstart")) { variant ->
    val environment = variant.flavorName?.let { name ->
      when {
        name.contains("staging", ignoreCase = true) -> "staging"
        name.contains("prod", ignoreCase = true) -> "prod"
        else -> "prod"
      }
    } ?: "prod"

    val taskProvider = tasks.register<CopyQuickstartCredentialsTask>("copyQuickstartCredentials${variant.name.capitalize()}") {
      if (quickstartCredentialsDir != null) {
        inputDir.set(File(quickstartCredentialsDir))
      }
      filePrefix.set("${environment}_")
    }
    variant.sources.assets?.addGeneratedSourceDirectory(taskProvider) { it.outputDir }
  }

  onVariants(selector().withBuildType("benchmark")) { variant ->
    val taskProvider = tasks.register<CopyBenchmarkBackupTask>("copyBenchmarkBackup${variant.name.capitalize()}") {
      if (benchmarkBackupFile != null) {
        inputFile.set(File(benchmarkBackupFile))
      }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(taskProvider) { it.outputDir }
  }

  onVariants(selector().withName("playProdDebug")) { variant ->
    val androidTest = (variant as? HasAndroidTest)?.androidTest ?: return@onVariants

    tasks.register<FirebaseTestLabTask>("firebaseTestLab") {
      group = "Verification"
      description = "Runs the ${variant.name} instrumentation tests on Firebase Test Lab via the gcloud CLI. Run a single class with -Pftl.class=<fqcn>[#method]; override other defaults with -Pftl.* properties."

      appApkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
      testApkDirectory.set(androidTest.artifacts.get(SingleArtifact.APK))
      builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())

      val deviceOverride = project.providers.gradleProperty("ftl.devices").orNull
      devices.set(
        deviceOverride?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
          ?: listOf("model=Pixel2.arm,version=31,locale=en,orientation=portrait")
      )

      useOrchestrator.set(true)
      environmentVariables.set(mapOf("clearPackageData" to "true"))
      testTimeout.set(project.providers.gradleProperty("ftl.timeout").getOrElse("30m"))
      numFlakyTestAttempts.set(project.providers.gradleProperty("ftl.numFlakyTestAttempts").map { it.toInt() }.getOrElse(1))
      gcloudProject.set(project.providers.gradleProperty("ftl.project"))
      resultsBucket.set(project.providers.gradleProperty("ftl.resultsBucket"))
      resultsDir.set(project.providers.gradleProperty("ftl.resultsDir"))

      val testClass = project.providers.gradleProperty("ftl.class").orNull?.takeIf { it.isNotBlank() }
      testTargets.set(
        if (testClass != null) "class $testClass" else project.providers.gradleProperty("ftl.testTargets").orNull
      )
      gcloudExecutable.set(project.providers.gradleProperty("ftl.gcloud").getOrElse("gcloud"))
      extraArgs.set(
        project.providers.gradleProperty("ftl.extraArgs").orNull
          ?.split(" ")?.map { it.trim() }?.filter { it.isNotEmpty() }
          ?: emptyList()
      )
    }
  }
}

baselineProfile {
  warnings {
    disabledVariants = false
  }

  mergeIntoMain = true

  variants.create("mocked") {
    from(project(":baseline-profile"))
  }

  dexLayoutOptimization = false
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get())
    freeCompilerArgs.addAll("-Xjvm-default=all")
    suppressWarnings = true
  }
}

dependencies {
  lintChecks(project(":lintchecks"))
  ktlintRuleset(libs.ktlint.twitter.compose)
  coreLibraryDesugaring(libs.android.tools.desugar)

  implementation(project(":core:models"))
  implementation(project(":core:models-jvm"))
  implementation(project(":core:serialization"))
  implementation(project(":core:ui"))
  implementation(project(":core:util"))

  implementation(project(":lib:apng"))
  implementation(project(":lib:archive"))
  implementation(project(":lib:contacts"))
  implementation(project(":lib:blurhash"))
  implementation(project(":lib:debuglogs-viewer"))
  implementation(project(":lib:device-transfer"))
  implementation(project(":lib:donations"))
  implementation(project(":lib:emoji"))
  implementation(project(":lib:glide"))
  implementation(project(":lib:image-editor"))
  implementation(project(":lib:libsignal-service"))
  implementation(project(":lib:network"))
  implementation(project(":lib:paging"))
  implementation(project(":lib:password-manager"))
  implementation(project(":lib:photoview"))
  implementation(project(":lib:qr"))
  implementation(project(":lib:signal-login"))
  implementation(project(":lib:sticky-header-grid"))
  implementation(project(":lib:ui-components"))
  implementation(project(":lib:video"))

  implementation(project(":feature:app-settings"))
  implementation(project(":feature:camera"))
  implementation(project(":feature:registration"))

  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.window.window)
  implementation(libs.androidx.window.java)
  implementation(libs.androidx.recyclerview)
  implementation(libs.material.material)
  implementation(libs.androidx.legacy.support)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.legacy.preference)
  implementation(libs.androidx.gridlayout)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.compose.rxjava3)
  implementation(libs.androidx.compose.runtime.livedata)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.navigation.fragment.ktx)
  implementation(libs.androidx.navigation.ui.ktx)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.viewmodel.savedstate)
  implementation(libs.androidx.lifecycle.common.java8)
  implementation(libs.androidx.lifecycle.reactivestreams.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.extensions)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.concurrent.futures)
  implementation(libs.androidx.autofill)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.core.telecom)
  implementation(libs.androidx.sharetarget)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.androidx.asynclayoutinflater)
  implementation(libs.androidx.asynclayoutinflater.appcompat)
  implementation(libs.androidx.emoji2)
  implementation(libs.androidx.paging.runtime)
  implementation(libs.androidx.paging.compose)
  implementation(libs.firebase.messaging) {
    exclude(group = "com.google.firebase", module = "firebase-core")
    exclude(group = "com.google.firebase", module = "firebase-analytics")
    exclude(group = "com.google.firebase", module = "firebase-measurement-connector")
  }
  implementation(libs.google.play.services.maps)
  implementation(libs.google.play.services.auth)
  implementation(libs.google.signin)
  implementation(libs.androidx.media)
  implementation(libs.bundles.media3)
  implementation(libs.conscrypt.android)
  implementation(libs.signal.aesgcmprovider)
  implementation(libs.libsignal.android)
  implementation(libs.mobilecoin)
  implementation(libs.signal.ringrtc)
  implementation(libs.leolin.shortcutbadger)
  implementation(libs.glide.glide)
  implementation(libs.roundedimageview)
  implementation(libs.materialish.progress)
  implementation(libs.greenrobot.eventbus)
  implementation(libs.google.zxing.android.integration)
  implementation(libs.google.zxing.core)
  implementation(libs.google.flexbox)
  implementation(libs.subsampling.scale.image.view) {
    exclude(group = "com.android.support", module = "support-annotations")
  }
  implementation(libs.lottie)
  implementation(libs.lottie.compose)

  // Compose screenshot testing
  screenshotTestImplementation(testLibs.compose.screenshot.validation.api)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling.core)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.signal.android.database.sqlcipher)
  implementation(libs.androidx.sqlite)
  testImplementation(libs.androidx.sqlite.framework)
  implementation(libs.google.ez.vcard) {
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "org.freemarker")
  }
  implementation(libs.dnsjava)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.arrow.core)
  implementation(libs.accompanist.permissions)
  implementation(libs.accompanist.drawablepainter)
  implementation(libs.kotlin.stdlib.jdk8)
  implementation(libs.kotlin.reflect)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.coroutines.rx3)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.rxjava3.rxandroid)
  implementation(libs.rxjava3.rxkotlin)
  implementation(libs.rxdogtag)
  implementation(libs.kotlinx.serialization.json)

  implementation(project(":lib:billing"))
  implementation(project(":feature:media-send"))

  "spinnerImplementation"(project(":lib:spinner"))

  "canaryImplementation"(libs.square.leakcanary)

  androidTestImplementation(libs.androidx.fragment.testing) {
    exclude(group = "androidx.test", module = "core")
  }

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
  testImplementation(testLibs.androidx.test.core)
  testImplementation(testLibs.robolectric.robolectric) {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
  }
  testImplementation(testLibs.bouncycastle.bcprov.jdk15on) {
    version {
      strictly("1.70")
    }
  }
  testImplementation(testLibs.bouncycastle.bcpkix.jdk15on) {
    version {
      strictly("1.70")
    }
  }
  testImplementation(testLibs.conscrypt.openjdk.uber)
  testImplementation(testLibs.mockk)
  // Tellomi（tellomi/tellomi#1422）：链接预览抓取器的本地 https 测试服务。
  // 它依赖的 okhttp 5.0.0-alpha.16 反正会被 App 的 5.3.2 顶掉；排除掉，免得为这份不用的元数据改依赖校验表。
  testImplementation(testLibs.square.mockwebserver) {
    exclude(group = "com.squareup.okhttp3", module = "okhttp")
  }
  testImplementation(testFixtures(project(":core:ui")))
  testImplementation(testFixtures(project(":lib:libsignal-service")))
  testImplementation(testLibs.espresso.core)
  testImplementation(testLibs.kotlinx.coroutines.test)
  testImplementation(testLibs.sqlite.jdbc)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  "perfImplementation"(libs.androidx.compose.ui.test.manifest)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(testLibs.androidx.test.ext.junit)
  androidTestImplementation(testLibs.espresso.core)
  androidTestImplementation(testLibs.espresso.contrib) {
    // espresso-contrib transitively pulls the full checkerframework jar (only its annotations are needed),
    // whose MANIFEST.MF collides with other test dependencies during androidTest resource merging.
    exclude(group = "org.checkerframework", module = "checker")
    // accessibility-test-framework drags in an ancient com.google.protobuf:protobuf-lite:3.0.1 whose
    // GeneratedMessageLite wins the merged dex and lacks registerDefaultInstance(Class, GeneratedMessageLite),
    // crashing tests at runtime. We only use RecyclerViewActions from contrib, not the accessibility checks.
    exclude(group = "com.google.android.apps.common.testing.accessibility.framework")
  }
  androidTestImplementation(testLibs.androidx.test.core)
  androidTestImplementation(testLibs.androidx.test.core.ktx)
  androidTestImplementation(testLibs.androidx.test.ext.junit.ktx)
  androidTestImplementation(testLibs.assertk)
  androidTestImplementation(testLibs.mockk.android)
  androidTestImplementation(testLibs.diff.utils)

  androidTestUtil(testLibs.androidx.test.orchestrator)
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("failed")
    exceptionFormat = TestExceptionFormat.FULL
    showCauses = true
    showExceptions = true
    showStackTraces = true
  }
}

fun getLastCommitTimestamp(): String {
  return providers.exec {
    commandLine("git", "log", "-1", "--pretty=format:%ct")
  }.standardOutput.asText.get() + "000"
}

fun getGitHash(): String {
  return providers.exec {
    commandLine("git", "rev-parse", "HEAD")
  }.standardOutput.asText.get().trim().substring(0, 12)
}

fun getNightlyTagForCurrentCommit(): String? {
  val output = providers.exec {
    commandLine("git", "tag", "--points-at", "HEAD")
  }.standardOutput.asText.get().trim()

  return if (output.isNotEmpty()) {
    val tags = output.split("\n").toList()
    tags.firstOrNull { it.contains("nightly") } ?: tags[0]
  } else {
    null
  }
}

fun getNightlyBuildNumber(tag: String?): Int {
  if (tag == null) {
    return 0
  }

  val match = Regex("-(\\d{3})$").find(tag)
  return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

fun getLastCommitDateTimeUtc(): String {
  val timestamp = providers.exec {
    commandLine("git", "log", "-1", "--pretty=format:%ct")
  }.standardOutput.asText.get().trim().toLong()
  val instant = Instant.ofEpochSecond(timestamp)
  val formatter = DateTimeFormatter.ofPattern("MMM d '@' HH:mm 'UTC'", Locale.US)
    .withZone(ZoneOffset.UTC)
  return formatter.format(instant)
}

fun getMapsKey(): String {
  return providers
    .gradleProperty("mapsKey")
    .orElse(providers.environmentVariable("MAPS_KEY"))
    .orElse("") // Tellomi（#1235）：不再回落到 Signal 的 key
    .get()
}

abstract class LanguageListValueSource : ValueSource<List<String>, LanguageListValueSource.Params> {
  interface Params : ValueSourceParameters {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val resDir: DirectoryProperty
  }

  override fun obtain(): List<String> {
    // In API 35, language codes for Hebrew and Indonesian now use the ISO 639-1 code ("he" and "id").
    // However, the value resources still only support the outdated code ("iw" and "in") so we have
    // to manually indicate that we support these languages.
    val updatedLanguageCodes = listOf("he", "id")

    val resRoot = parameters.resDir.asFile.get()

    val languages = resRoot
      .walkTopDown()
      .filter { it.isFile && it.name == "strings.xml" }
      .mapNotNull { stringFile -> stringFile.parentFile?.name }
      .map { valuesFolderName -> valuesFolderName.removePrefix("values-") }
      .filter { valuesFolderName -> valuesFolderName != "values" }
      .map { languageCode -> languageCode.replace("-r", "_") }
      .toList()
      .distinct()
      .sorted()

    return languages + updatedLanguageCodes + "en"
  }
}

abstract class PropertiesFileValueSource : ValueSource<Properties, PropertiesFileValueSource.Params> {
  interface Params : ValueSourceParameters {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val file: RegularFileProperty
  }

  override fun obtain(): Properties {
    val f: File = parameters.file.asFile.get()
    if (!f.exists()) return Properties()

    return Properties().apply {
      f.inputStream().use { load(it) }
    }
  }
}

fun String.capitalize(): String {
  return this.replaceFirstChar { it.uppercase() }
}

abstract class CopyQuickstartCredentialsTask : DefaultTask() {
  @get:InputDirectory
  @get:Optional
  abstract val inputDir: DirectoryProperty

  @get:Input
  abstract val filePrefix: Property<String>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun copy() {
    if (!inputDir.isPresent) {
      throw GradleException("quickstart.credentials.dir is not set in local.properties. This is required for quickstart builds.")
    }

    val prefix = filePrefix.get()
    val candidates = inputDir.get().asFile.listFiles()
      ?.filter { it.extension == "json" && it.name.startsWith(prefix) }
      ?: emptyList()

    if (candidates.isEmpty()) {
      throw GradleException("No credential files matching '$prefix*.json' found in ${inputDir.get().asFile}. Add files like '${prefix}account1.json' to your credentials directory.")
    }

    val chosen = candidates.random()
    logger.lifecycle("Selected quickstart credential: ${chosen.name}")

    val dest = outputDir.get().asFile.resolve("quickstart")
    dest.mkdirs()
    chosen.copyTo(dest.resolve(chosen.name), overwrite = true)
  }
}

abstract class CopyBenchmarkBackupTask : DefaultTask() {
  @get:InputFile
  @get:Optional
  abstract val inputFile: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun copy() {
    val dest = outputDir.get().asFile.resolve("backups")
    dest.mkdirs()

    if (!inputFile.isPresent) {
      logger.lifecycle("benchmark.backup.file is not set in local.properties. Benchmark tests using backup data will crash at runtime.")
      return
    }

    val backupFile = inputFile.get().asFile
    logger.lifecycle("Using benchmark backup: ${backupFile.absolutePath} (${backupFile.length() / 1024}KB)")
    backupFile.copyTo(dest.resolve("backup.binproto"), overwrite = true)
  }
}

/**
 * Runs an instrumentation test suite on Firebase Test Lab by shelling out to `gcloud firebase test android run`.
 *
 * The `gcloud` CLI must be installed and authenticated (`gcloud auth login` and a configured project, or an
 * activated service account) before invoking this task.
 */
@DisableCachingByDefault(because = "Executes tests on remote devices; results must never be served from the build cache")
abstract class FirebaseTestLabTask
@Inject
constructor(
  private val execOperations: ExecOperations
) : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val appApkDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val testApkDirectory: DirectoryProperty

  @get:Input
  abstract val devices: ListProperty<String>

  @get:Input
  abstract val useOrchestrator: Property<Boolean>

  @get:Input
  abstract val environmentVariables: MapProperty<String, String>

  @get:Input
  abstract val testTimeout: Property<String>

  @get:Input
  abstract val numFlakyTestAttempts: Property<Int>

  @get:Input
  @get:Optional
  abstract val gcloudProject: Property<String>

  @get:Input
  @get:Optional
  abstract val resultsBucket: Property<String>

  @get:Input
  @get:Optional
  abstract val resultsDir: Property<String>

  @get:Input
  @get:Optional
  abstract val testTargets: Property<String>

  @get:Input
  abstract val gcloudExecutable: Property<String>

  @get:Input
  abstract val extraArgs: ListProperty<String>

  @get:Internal
  abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

  @TaskAction
  fun run() {
    val appApk = findApk(appApkDirectory.get(), "app")
    val testApk = findApk(testApkDirectory.get(), "instrumentation test")

    val arguments = mutableListOf(
      gcloudExecutable.get(),
      "firebase", "test", "android", "run",
      "--type", "instrumentation",
      "--app", appApk.absolutePath,
      "--test", testApk.absolutePath,
      "--timeout", testTimeout.get(),
      "--num-flaky-test-attempts", numFlakyTestAttempts.get().toString()
    )

    devices.get().forEach { device ->
      arguments += listOf("--device", device)
    }

    if (useOrchestrator.get()) {
      arguments += "--use-orchestrator"
    }

    val environment = environmentVariables.get()
    if (environment.isNotEmpty()) {
      arguments += "--environment-variables"
      arguments += environment.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    gcloudProject.orNull?.takeIf { it.isNotBlank() }?.let { arguments += listOf("--project", it) }
    resultsBucket.orNull?.takeIf { it.isNotBlank() }?.let { arguments += listOf("--results-bucket", it) }
    resultsDir.orNull?.takeIf { it.isNotBlank() }?.let { arguments += listOf("--results-dir", it) }
    testTargets.orNull?.takeIf { it.isNotBlank() }?.let { arguments += listOf("--test-targets", it) }
    arguments += extraArgs.get()

    logger.lifecycle("Running Firebase Test Lab:\n  ${arguments.joinToString(" ")}")
    execOperations.exec {
      commandLine(arguments)
    }
  }

  /**
   * Resolves the APK this build produced from the variant's own output metadata. The directory listing can't be
   * trusted: APKs are named per version and ABI, so it also holds every earlier build's, plus this build's other
   * splits.
   */
  private fun findApk(directory: Directory, label: String): File {
    val elements = builtArtifactsLoader.get().load(directory)?.elements?.takeIf { it.isNotEmpty() }
      ?: throw GradleException("No $label APK found under ${directory.asFile.absolutePath}. Was the assemble task run?")

    val element = elements.firstOrNull { it.filters.isEmpty() }
      ?: throw GradleException("The $label APK is split by ${elements.flatMap { it.filters }.joinToString { it.filterType.name }} with no universal output to run on Test Lab.")

    return File(element.outputFile)
  }
}

abstract class RenameApkTask : DefaultTask() {
  @get:InputFiles
  abstract val apkFolder: DirectoryProperty

  @get:OutputDirectory
  abstract val outFolder: DirectoryProperty

  @get:Internal
  abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameApkTask>>

  @TaskAction
  fun rename() {
    transformationRequest.get().submit(this) { artifact ->
      val originalFile = File(artifact.outputFile)
      val versionName = artifact.versionName?.substringBefore(" |")
      val newName = if (!versionName.isNullOrEmpty()) {
        originalFile.name.replace(".apk", "-$versionName.apk")
      } else {
        originalFile.name
      }
      val newFile = File(outFolder.get().asFile, newName)
      originalFile.copyTo(newFile, overwrite = true)
      newFile
    }
  }
}
