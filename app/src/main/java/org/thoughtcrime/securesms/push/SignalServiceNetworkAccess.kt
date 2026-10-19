package org.thoughtcrime.securesms.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.Uri
import androidx.core.content.ContextCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.TlsVersion
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.network.config.HttpProxy
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.TrustStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.CustomDns
import org.thoughtcrime.securesms.net.DeprecatedClientPreventionInterceptor
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.net.RemoteDeprecationDetectorInterceptor
import org.thoughtcrime.securesms.net.SequentialDns
import org.thoughtcrime.securesms.net.StandardUserAgentInterceptor
import org.thoughtcrime.securesms.net.StaticDns
import org.thoughtcrime.securesms.net.StorageServiceSizeLoggingInterceptor
import org.thoughtcrime.securesms.region.TellomiRegions
import org.thoughtcrime.securesms.region.TellomiServiceConfigurations
import java.io.IOException
import java.util.Optional

/**
 * Provides a [SignalServiceConfiguration] to be used with our service layer.
 * If you're looking for a place to start, look at [getConfiguration].
 */
class SignalServiceNetworkAccess(context: Context) {
  companion object {
    private val TAG = Log.tag(SignalServiceNetworkAccess::class.java)

    @JvmField
    val DNS: Dns = SequentialDns(
      Dns.SYSTEM,
      CustomDns("1.1.1.1"),
      // Tellomi（#1055 第三刀）：静态 IP 表从区域表来（契约第三节 staticIps，global 档回落香港，CN 档回落成都），切区不用重建。
      // 上游这里还有 cdsi / svr2 两行，指向 Signal 自己的 IP。这两样都不走这张表：svr2 的 OkHttp client
      // （SignalUrlExtensions.buildOkHttpClient）不设 dns，CDSI 走 libsignal。是死配置，删掉。
      StaticDns(TellomiRegions.staticIpTable())
    )

    private fun String.stripProtocol(): String {
      return this.removePrefix("https://")
    }

    private const val COUNTRY_CODE_EGYPT = 20
    private const val COUNTRY_CODE_UAE = 971
    private const val COUNTRY_CODE_OMAN = 968
    private const val COUNTRY_CODE_QATAR = 974
    private const val COUNTRY_CODE_IRAN = 98
    private const val COUNTRY_CODE_CUBA = 53
    private const val COUNTRY_CODE_UZBEKISTAN = 998
    private const val COUNTRY_CODE_VENEZUELA = 58
    private const val COUNTRY_CODE_PAKISTAN = 92

    // Tellomi（#1025）：上游在这里放 reflector / Fastly fronted 主机名（全是 Signal 的基础设施），
    // 连同下面用它们组装的 buildGConfiguration / fConfig 一起删了。下面这几个 ConnectionSpec
    // 留着：它们只是 TLS 参数，没有主机名，将来我们自己做规避入口时还要用。
    private val GMAPS_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val GMAIL_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val PLAY_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val APP_CONNECTION_SPEC = ConnectionSpec.MODERN_TLS

    @Suppress("DEPRECATION")
    private fun getSystemHttpProxy(context: Context): HttpProxy? {
      val connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return null

      val proxyInfo = connectivityManager
        .activeNetwork
        ?.let { connectivityManager.getLinkProperties(it)?.httpProxy }

      return proxyInfo.toApplicableSystemHttpProxy()
    }

    fun ProxyInfo?.toApplicableSystemHttpProxy(): HttpProxy? {
      return this
        ?.takeIf { !it.exclusionList.contains(TellomiRegions.current().chat.stripProtocol()) }
        // NB: Edit carefully, dear reader, as the line below is written from hard won experience.
        // It turns out, that despite being documented *nowhere*, if a PAC file is set
        //   as the system proxy, proxyInfo.host will return "localhost" and proxyInfo.port
        //   will return -1.
        // I learnt this by reading the AOSP source code for ProxyInfo:
        //   https://android.googlesource.com/platform/frameworks/base/+/4696ee4/core/java/android/net/ProxyInfo.java#107
        // So, if we do not explicitly check that a PAC file is not set, the proxy
        //   we pass to libsignal may be syntactically invalid, and the user may be
        //   rendered unable to connect.
        ?.takeIf { it.pacFileUrl == Uri.EMPTY }
        ?.let { proxy -> HttpProxy(proxy.host, proxy.port) }
    }
  }

  private val serviceTrustStore: TrustStore = SignalServiceTrustStore(context)
  private val gTrustStore: TrustStore = DomainFrontingTrustStore(context)

  private val interceptors: List<Interceptor> = listOf(
    StandardUserAgentInterceptor(),
    StorageServiceSizeLoggingInterceptor(),
    RemoteDeprecationDetectorInterceptor(this::getConfiguration),
    DeprecatedClientPreventionInterceptor(),
    DeviceTransferBlockingInterceptor.getInstance()
  )

  private val zkGroupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val genericServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val backupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  // ── Tellomi（#1025）：删掉上游的「审查规避」配置 ─────────────────────────────
  // 上游这里有两套规避通道，全部指向 **Signal 自己的基础设施**：
  //   · 域名前置到 Google（reflector-nrgwuv7kwq-uc.a.run.app）
  //   · Fastly 的 fronted 主机（storage.signal.org.global.prod.fastly.net 等 7 个）
  // 我们没有任何 fronted 入口，留着它们只有坏处：用户在连不上的时候最可能去按
  // 设置里那个「审查规避」开关，按下去之后客户端会改去连 Signal 的主机——
  // 结果是更连不上，而且没有任何提示说明原因。
  //
  // 所以：规避模式下仍然用**我们自己的端点**，只保留 censored = true 这个标记，
  // 让 isCensored() / 前台服务 / FCM 取数策略等依赖它的地方行为不变。
  // 判据：发版包的 DEX 里 signal.org.global.prod.fastly.net 出现次数 = 0（原来 3）。
  //
  // 真要做规避，得先有我们自己的 fronted 入口（域名 + CDN，服务端那半），
  // 那时把这段按上游的形状重建、并把 86 加进 defaultCensoredCountryCodes 才有意义。
  // 上游原文见 v8.26.4 的同一文件（G_HOST / F_* 常量 + buildGConfiguration + fConfig）。
  // 重建时每个 CDN 只能放一个 URL，并且经 TellomiServiceConfigurations 组装：照抄上游的 fUrls / buildGConfiguration
  // （每个 CDN 3–6 个 URL）会在构造时被下面的 init 拒绝（RegionProfile 契约第五节第 3 条，tellomi/tellomi#1055）。
  private val censorshipConfiguration: Map<Int, SignalServiceConfiguration> = emptyMap()

  // 注意 **不能**写成 `private val ... = uncensoredConfiguration.copy(...)`：
  // uncensoredConfiguration 在本文件里声明得更靠后，属性初始化顺序会让它在这里是 null。
  // 用 get() 每次取，避开初始化顺序。
  private val defaultCensoredConfiguration: SignalServiceConfiguration
    get() = uncensoredConfiguration.copy(censored = true)

  private val defaultCensoredCountryCodes: Set<Int> = setOf(
    COUNTRY_CODE_EGYPT,
    COUNTRY_CODE_UAE,
    COUNTRY_CODE_OMAN,
    COUNTRY_CODE_QATAR,
    COUNTRY_CODE_IRAN,
    COUNTRY_CODE_CUBA,
    COUNTRY_CODE_UZBEKISTAN,
    COUNTRY_CODE_VENEZUELA,
    COUNTRY_CODE_PAKISTAN
  )

  // Tellomi（#1055）：按当前区组装（RegionProfile 契约；现在 CN 关着，恒为 global = 原来这里的常量），组装时断言 cdn3 恰好一个。
  // 切区走 AppDependencies.resetNetwork()：它会重建整个 NetworkDependenciesModule，连同这个对象。
  val uncensoredConfiguration: SignalServiceConfiguration = TellomiServiceConfigurations.build(
    profile = TellomiRegions.current(),
    trustStore = serviceTrustStore,
    cdsiUrl = BuildConfig.SIGNAL_CDSI_URL,
    svr2Url = BuildConfig.SIGNAL_SVR2_URL,
    networkInterceptors = interceptors,
    dns = Optional.of(DNS),
    signalProxy = if (SignalStore.proxy.isProxyEnabled) Optional.ofNullable(SignalStore.proxy.proxy) else Optional.empty(),
    systemHttpProxy = Optional.ofNullable(getSystemHttpProxy(context)),
    zkGroupServerPublicParams = zkGroupServerPublicParams,
    genericServerPublicParams = genericServerPublicParams,
    backupServerPublicParams = backupServerPublicParams,
    censored = false
  )

  init {
    // 契约第五节第 3 条：getConfiguration() 可能返回的每一份都要 cdn3 恰好一个，含将来按上游形状重建的规避配置。
    // 必须放在 uncensoredConfiguration 之后：defaultCensoredConfiguration 的 getter 要读它。
    (censorshipConfiguration.values + defaultCensoredConfiguration).forEach(TellomiServiceConfigurations::requireSingleCdn3)
  }

  fun getConfiguration(): SignalServiceConfiguration {
    return getConfiguration(SignalStore.account.e164)
  }

  fun getConfiguration(e164: String?): SignalServiceConfiguration {
    if (e164 == null || SignalStore.proxy.isProxyEnabled) {
      return uncensoredConfiguration
    }

    val countryCode: Int = PhoneNumberUtil.getInstance().parse(e164, null).countryCode

    return when (SignalStore.settings.censorshipCircumventionEnabled) {
      SettingsValues.CensorshipCircumventionEnabled.ENABLED -> {
        censorshipConfiguration[countryCode] ?: defaultCensoredConfiguration
      }

      SettingsValues.CensorshipCircumventionEnabled.DISABLED -> {
        uncensoredConfiguration
      }

      SettingsValues.CensorshipCircumventionEnabled.DEFAULT -> {
        if (defaultCensoredCountryCodes.contains(countryCode)) {
          censorshipConfiguration[countryCode] ?: defaultCensoredConfiguration
        } else {
          uncensoredConfiguration
        }
      }
    }
  }

  fun isCensored(): Boolean {
    return isCensored(SignalStore.account.e164)
  }

  fun isCensored(number: String?): Boolean {
    return getConfiguration(number) != uncensoredConfiguration
  }

  fun isCountryCodeCensoredByDefault(countryCode: Int): Boolean {
    return defaultCensoredCountryCodes.contains(countryCode)
  }
}
