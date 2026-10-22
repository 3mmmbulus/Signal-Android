package org.thoughtcrime.securesms.net;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.conscrypt.ConscryptSignal;
import org.signal.core.util.logging.Log;
import org.signal.network.util.HttpsProxySocketFactory;
import org.thoughtcrime.securesms.region.TellomiRegionProfile;
import org.thoughtcrime.securesms.region.TellomiRegions;
import org.thoughtcrime.securesms.util.RemoteConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

public class ContentProxySelector extends ProxySelector {

  private static final String TAG = Log.tag(ContentProxySelector.class);

  public static final Set<String> WHITELISTED_DOMAINS = new HashSet<>();
  static {
    WHITELISTED_DOMAINS.add("giphy.com");
  }

  private static volatile InnerTls innerTls;

  private final List<Proxy> content;

  public ContentProxySelector() {
    this(currentEndpoint());
  }

  private ContentProxySelector(@NonNull Endpoint endpoint) {
    this.content = Collections.singletonList(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)));
  }

  /**
   * Tellomi（#1078）：把一个 OkHttpClient.Builder 配成「经内容代理，而且和代理之间**先做 TLS，再在里面发 CONNECT**」。
   * 所有走内容代理的 client 都从这里配，别再各自 {@code .proxySelector(new ContentProxySelector())}——
   * 下面三件事少一件都不行。
   *
   * 1. {@code proxySelector}：白名单内一律走代理，白名单外直接抛异常，**从不返回 DIRECT**。
   * 2. {@code socketFactory}：{@link HttpsProxySocketFactory}，在 connect 那一刻和代理做 TLS（SNI 与证书都按
   *    代理主机）。上游只设 {@code Proxy.Type.HTTP}，对 443 端口发的是**明文** CONNECT，
   *    目标主机名（api.giphy.com）和隧道里 TLS 的 SNI 都在明文里——从大陆出发会被墙 reset，换成我们自己的代理也一样。
   *    香港那台的 nginx 按 SNI 分流：SNI = contentproxy.tellomi.app 的进 4444 终结 TLS 再交给 tinyproxy。
   *    2026-09-23 大陆直连实测（OkHttp 5.3.2）：明文 → api.giphy.com {@code Connection reset}；外层 TLS →
   *    CONNECT 200、Giphy 401（没带 key，符合预期）；白名单外的 example.com 两种都是 CONNECT 403。
   *    这个工厂永远连代理主机，只因为第 1 条保证了每个请求都走代理，这样用才是对的。
   * 3. {@code sslSocketFactory}：OkHttp 在隧道里对目标做的那层 TLS，是叠在第 2 条给的 socket（一个 Socket 子类）上的。
   *    这里给它一个开了 engine 模式的 Conscrypt 工厂——上游对自己的 TLS 代理 socket 也是这么做的，只是开在**全局**
   *    （ApplicationContext 的 proxy-init、SignalProxyUtil.enableProxy）；这里只对内容代理这几个 client 开，不影响别的连接。
   *    **2026-09-23 在 Android 16 模拟器上实测：不开也能通**（红控制没能复现失败：两种都是 HTTP 401、里层 TLS 1.3）。
   *    保留它是保险：Conscrypt 选 FileDescriptor 还是 engine 实现，取决于能不能反射到 socket 的文件描述符，
   *    这在旧版本 Android（minSdk 23）上没有实测过。
   *
   * 代理地址优先用服务端下发的 {@code global.gif.proxyUrl}（{@link #parseProxyUrl}），没下发或不合法才用编译期常量。
   */
  public static @NonNull OkHttpClient.Builder configure(@NonNull OkHttpClient.Builder builder) {
    // 代理选择器和 TLS 工厂必须用同一个主机：一个指 A、一个按 B 做 TLS，证书校验会失败。
    Endpoint endpoint = currentEndpoint();

    builder.proxySelector(new ContentProxySelector(endpoint))
           .socketFactory(new HttpsProxySocketFactory(endpoint.host));

    InnerTls inner = innerTls();
    if (inner != null) {
      builder.sslSocketFactory(inner.socketFactory, inner.trustManager);
    }

    return builder;
  }

  /** 服务端下发的地址能用就用，否则回落到编译期常量。 */
  private static @NonNull Endpoint currentEndpoint() {
    String               remote = RemoteConfig.gifProxyUrl();
    TellomiRegionProfile region = TellomiRegions.current();
    Endpoint             parsed = parseProxyUrl(remote);

    // Tellomi（REGION_PROFILE 第六节之二 / 第八节）：下发值按账号、不按区；不在当前区的 Tellomi 根域下就不用
    if (parsed != null && TellomiRegions.isHostInRegion(parsed.host, region)) {
      return parsed;
    }

    if (parsed != null) {
      Log.w(TAG, "Ignoring global.gif.proxyUrl, its host is not in the current region. Falling back to the region's proxy.");
    } else if (remote != null && !remote.isEmpty()) {
      Log.w(TAG, "Ignoring global.gif.proxyUrl, not an https:// URL with a host. Falling back to the build-time proxy.");
    }
    return new Endpoint(region.getContentProxyHost(), region.getContentProxyPort());
  }

  /**
   * 解析服务端下发的 {@code global.gif.proxyUrl}（形如 {@code https://contentproxy.tellomi.app:443}）。
   *
   * **只认 https**：和代理之间必须先做 TLS（见 {@link #configure}），下发一个 {@code http://} 进来等于让服务端
   * 一行配置就把所有客户端降回明文 CONNECT——那在大陆直接不通，所以宁可回落到编译期常量。
   * 不认路径以外的东西也不报错：host + port 就是这里要的全部。
   */
  @VisibleForTesting
  static @Nullable Endpoint parseProxyUrl(@Nullable String url) {
    if (url == null) {
      return null;
    }

    HttpUrl parsed = HttpUrl.parse(url.trim());
    if (parsed == null || !"https".equals(parsed.scheme()) || parsed.host().isEmpty()) {
      return null;
    }

    return new Endpoint(parsed.host(), parsed.port());
  }

  /** 和 OkHttp 默认的构造方式一致（平台默认 TrustManager + {@code SSLContext.getInstance("TLS")}），只多开 engine 模式。 */
  private static @Nullable InnerTls innerTls() {
    InnerTls result = innerTls;
    if (result != null) {
      return result;
    }

    synchronized (ContentProxySelector.class) {
      if (innerTls == null) {
        try {
          TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          trustManagerFactory.init((KeyStore) null);
          X509TrustManager trustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

          SSLContext context = SSLContext.getInstance("TLS");
          context.init(null, new TrustManager[] { trustManager }, null);

          SSLSocketFactory socketFactory = context.getSocketFactory();
          if (ConscryptSignal.isConscrypt(socketFactory)) {
            ConscryptSignal.setUseEngineSocket(socketFactory, true);
          } else {
            Log.w(TAG, "TLS provider is not our Conscrypt (" + socketFactory.getClass().getName() + "); TLS inside the content proxy tunnel may fail.");
          }

          innerTls = new InnerTls(socketFactory, trustManager);
        } catch (GeneralSecurityException | ClassCastException e) {
          Log.w(TAG, "Could not build the TLS factory for the content proxy tunnel.", e);
          return null;
        }
      }
      return innerTls;
    }
  }

  @Override
  public List<Proxy> select(URI uri) {
    String host = uri.getHost();
    if (host != null) {
      for (String domain : WHITELISTED_DOMAINS) {
        if (host.equals(domain) || host.endsWith("." + domain)) {
          return content;
        }
      }
    }
    throw new IllegalArgumentException("Tried to proxy a non-whitelisted domain.");
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException failure) {
    if (failure instanceof SocketException) {
      Log.d(TAG, "Socket exception. Likely a cancellation.");
    } else {
      Log.w(TAG, "Connection failed.", failure);
    }
  }

  @VisibleForTesting
  static final class Endpoint {
    final String host;
    final int    port;

    Endpoint(@NonNull String host, int port) {
      this.host = host;
      this.port = port;
    }
  }

  private static final class InnerTls {
    final SSLSocketFactory socketFactory;
    final X509TrustManager trustManager;

    InnerTls(@NonNull SSLSocketFactory socketFactory, @NonNull X509TrustManager trustManager) {
      this.socketFactory = socketFactory;
      this.trustManager  = trustManager;
    }
  }
}
