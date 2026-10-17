/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import java.util.Locale

/**
 * Tellomi (spec attachment-files-location L-11): "Directions" lists only the map apps installed on this phone.
 *
 * Amap, Baidu and Tencent (what most phones in mainland China have) by their own route links; every other app that opens
 * `geo:` (Google Maps, Petal Maps, …) by `geo:`. The packages and `geo:` are declared under `<queries>` in the manifest, or
 * Android 11+ would hide them. One app: opened straight away; several: a list to pick from (iOS `TellomiMapApp`, after
 * Telegram iOS `OpenInOptions`; Telegram Android only offers Google Maps).
 *
 * Coordinates are passed as they are (L-10 is not decided): MapKit / Amap pick points in GCJ-02 in mainland China, so Amap
 * gets `dev=0`, Baidu `coord_type=gcj02`, Tencent its default GCJ-02. Outside the mainland the two systems are the same.
 */
object TellomiMapApps {

  const val AMAP_PACKAGE = "com.autonavi.minimap"
  const val BAIDU_PACKAGE = "com.baidu.BaiduMap"
  const val TENCENT_PACKAGE = "com.tencent.map"

  enum class Directions { DRIVING, WALKING }

  data class MapApp(val packageName: String, val label: CharSequence, val icon: Drawable?, val intent: Intent)

  /** The route / place link for a known Chinese map app, or null for any other package (that one gets [geoIntent]). */
  @JvmStatic
  fun routeUri(packageName: String, location: TellomiLocationMessage, fallbackName: String, directions: Directions = Directions.DRIVING): Uri? {
    val lat = format(location.latitude)
    val lng = format(location.longitude)
    val name = location.name ?: location.address ?: fallbackName
    return when (packageName) {
      // https://lbs.amap.com/api/amap-mobilemap/guide/android/route
      AMAP_PACKAGE -> Uri.parse("amapuri://route/plan/").buildUpon()
        .appendQueryParameter("sourceApplication", "Tellomi")
        .appendQueryParameter("dlat", lat)
        .appendQueryParameter("dlon", lng)
        .appendQueryParameter("dname", name)
        .appendQueryParameter("dev", "0")
        .appendQueryParameter("t", if (directions == Directions.DRIVING) "0" else "2")
        .build()
      // https://lbsyun.baidu.com/faq/api?title=webapi/uri/andriod
      BAIDU_PACKAGE -> Uri.parse("baidumap://map/direction").buildUpon()
        .appendQueryParameter("destination", "name:$name|latlng:$lat,$lng")
        .appendQueryParameter("coord_type", "gcj02")
        .appendQueryParameter("mode", if (directions == Directions.DRIVING) "driving" else "walking")
        .appendQueryParameter("src", "andr.tellomi.chat")
        .build()
      // https://lbs.qq.com/webApi/uriV1/uriGuide/uriMobileRoute
      TENCENT_PACKAGE -> Uri.parse("qqmap://map/routeplan").buildUpon()
        .appendQueryParameter("type", if (directions == Directions.DRIVING) "drive" else "walk")
        .appendQueryParameter("to", name)
        .appendQueryParameter("tocoord", "$lat,$lng")
        .appendQueryParameter("referer", "Tellomi")
        .build()
      else -> null
    }
  }

  /** `geo:lat,lng?q=lat,lng(name)`: any map app shows the point (with its name as the label). */
  @JvmStatic
  fun geoUri(location: TellomiLocationMessage, fallbackName: String): Uri {
    val lat = format(location.latitude)
    val lng = format(location.longitude)
    val name = (location.name ?: location.address ?: fallbackName).replace("(", " ").replace(")", " ").trim()
    return Uri.parse("geo:$lat,$lng?q=" + Uri.encode("$lat,$lng($name)"))
  }

  @JvmStatic
  fun geoIntent(location: TellomiLocationMessage, fallbackName: String): Intent = Intent(Intent.ACTION_VIEW, geoUri(location, fallbackName))

  /** The installed map apps: Amap, Baidu, Tencent first (when installed), then every other app that opens `geo:`. */
  @JvmStatic
  fun installed(packageManager: PackageManager, location: TellomiLocationMessage, fallbackName: String, directions: Directions = Directions.DRIVING): List<MapApp> {
    val apps = mutableListOf<MapApp>()
    for (packageName in listOf(AMAP_PACKAGE, BAIDU_PACKAGE, TENCENT_PACKAGE)) {
      val uri = routeUri(packageName, location, fallbackName, directions) ?: continue
      val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
      val resolved = packageManager.resolveActivity(intent, 0) ?: continue
      apps += MapApp(packageName, resolved.loadLabel(packageManager), resolved.loadIcon(packageManager), intent)
    }
    val known = apps.map { it.packageName }.toSet()
    val geo = geoIntent(location, fallbackName)
    for (resolved in packageManager.queryIntentActivities(geo, 0)) {
      val packageName = resolved.activityInfo?.packageName ?: continue
      if (packageName in known || apps.any { it.packageName == packageName }) continue
      apps += MapApp(packageName, resolved.loadLabel(packageManager), resolved.loadIcon(packageManager), Intent(geo).setPackage(packageName))
    }
    return apps
  }

  private fun format(degrees: Double): String = String.format(Locale.US, "%.6f", degrees)
}
