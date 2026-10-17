package org.thoughtcrime.securesms.stickers;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.Hex;
import org.whispersystems.signalservice.api.util.OptionalUtil;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Pair;

/**
 * Manages creating and parsing the various sticker pack URLs.
 */
public class StickerUrl {

  // Tellomi：新旧两种形状都认（见 docs/signal/LINKS_AND_SCHEMES.md）
  //   旧：https://signal.art/addstickers/#pack_id=…&pack_key=…
  //   新：https://tell.cc/s#pack_id=…&pack_key=…
  // 「发出」这一侧：分享链接 2026-10-17 翻到新形状（tellomi/tellomi#1687；三端都已接受 tell.cc/s，Desktop 早已发新形状）。
  // createActionUri 的 sgnl:// 是 App 内部跳转用的，不对外，不动。
  private static final Pattern STICKER_URL_PATTERN = Pattern.compile("^https://signal\\.art/addstickers/#pack_id=(.*)&pack_key=(.*)$");
  private static final Pattern STICKER_URL_PATTERN_TELLOMI = Pattern.compile("^https://tell\\.cc/s/?#pack_id=(.*)&pack_key=(.*)$");

  public static Optional<Pair<String, String>> parseExternalUri(@Nullable Uri uri) {
    if (uri == null) return Optional.empty();

    return OptionalUtil.or(parseActionUri(uri), parseShareLink(uri.toString()));
  }

  public static Optional<Pair<String, String>> parseActionUri(@Nullable Uri uri) {
    if (uri == null) return Optional.empty();

    String packId  = uri.getQueryParameter("pack_id");
    String packKey = uri.getQueryParameter("pack_key");

    if (TextUtils.isEmpty(packId) || TextUtils.isEmpty(packKey) || !isValidHex(packId) || !isValidHex(packKey)) {
      return Optional.empty();
    }

    return Optional.of(new Pair<>(packId, packKey));
  }

  public static @NonNull Uri createActionUri(@NonNull String packId, @NonNull String packKey) {
    return Uri.parse(String.format("sgnl://addstickers?pack_id=%s&pack_key=%s", packId, packKey));
  }

  public static boolean isValidShareLink(@Nullable String url) {
    return parseShareLink(url).isPresent();
  }

  public static @NonNull Optional<Pair<String, String>> parseShareLink(@Nullable String url) {
    if (url == null) return Optional.empty();

    Matcher matcher = STICKER_URL_PATTERN.matcher(url);

    if (!matcher.matches()) {
      matcher = STICKER_URL_PATTERN_TELLOMI.matcher(url);
    }

    if (matcher.matches() && matcher.groupCount() == 2) {
      String packId  = matcher.group(1);
      String packKey = matcher.group(2);

      if (isValidHex(packId) && isValidHex(packKey)) {
        return Optional.of(new Pair<>(packId, packKey));
      }
    }

    return Optional.empty();
  }

  public static String createShareLink(@NonNull String packId, @NonNull String packKey) {
    return "https://tell.cc/s#pack_id=" + packId + "&pack_key=" + packKey;
  }

  private static boolean isValidHex(String value) {
    try {
      Hex.fromStringCondensed(value);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
