/*
 * Copyright 2026 重庆半格智能科技有限公司
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.maps

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.dp
import org.signal.core.util.logging.Log
import org.signal.glide.decryptableuri.DecryptableUri
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.giph.ui.AspectRatioImageView
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import org.thoughtcrime.securesms.util.DynamicTheme
import java.util.Locale

/**
 * Tellomi (spec attachment-files-location L-11): the page a location message opens.
 *
 * No map SDK in this cut (Amap #1124 needs the owner's key): the map is the snapshot that came with the message. Below it,
 * after Telegram Android's `LocationActivity` in view mode: an info row (icon, name or "Location", "address • distance") and
 * a "Directions" cell (`LocationDirectionCell`). "Directions" lists the installed map apps ([TellomiMapApps]); one app opens
 * straight away. The menu copies the address or shares the location.
 *
 * The distance is shown only when location permission was already granted; this page never asks for it (the first-use page
 * L-2 belongs to the picker). Without an address in the message, the platform geocoder fills it in when it can.
 */
class TellomiLocationActivity : AppCompatActivity() {

  companion object {
    private val TAG = Log.tag(TellomiLocationActivity::class.java)

    private const val EXTRA_LATITUDE = "latitude"
    private const val EXTRA_LONGITUDE = "longitude"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_ADDRESS = "address"
    private const val EXTRA_MAP = "map"

    /** Under this many metres: "You are here" instead of a distance (Telegram: 10). */
    private const val HERE_METERS = 10.0

    @JvmStatic
    fun intent(context: Context, location: TellomiLocationMessage, map: Uri?): Intent {
      return Intent(context, TellomiLocationActivity::class.java)
        .putExtra(EXTRA_LATITUDE, location.latitude)
        .putExtra(EXTRA_LONGITUDE, location.longitude)
        .putExtra(EXTRA_NAME, location.name)
        .putExtra(EXTRA_ADDRESS, location.address)
        .putExtra(EXTRA_MAP, map)
    }

    /** "1.2 km" / "350 m" (miles and feet where the region uses them). */
    @JvmStatic
    @VisibleForTesting
    fun formatDistance(meters: Double, locale: Locale): String {
      val imperial = locale.country in setOf("US", "LR", "MM")
      return if (imperial) {
        val feet = meters * 3.28084
        if (feet < 1000) String.format(locale, "%d ft", feet.toInt()) else String.format(locale, "%.1f mi", meters / 1609.344)
      } else {
        if (meters < 1000) String.format(locale, "%d m", meters.toInt()) else String.format(locale, "%.1f km", meters / 1000)
      }
    }

    /** "address • distance"; either may be missing. */
    @JvmStatic
    @VisibleForTesting
    fun subtitle(address: String?, distance: String?): String = listOfNotNull(address?.takeIf { it.isNotEmpty() }, distance).joinToString(" • ")

    /** Name, address and the maps link, for sharing (every client recognises or at least opens the link). */
    @JvmStatic
    @VisibleForTesting
    fun shareText(location: TellomiLocationMessage, address: String?): String {
      val link = "https://maps.google.com/maps?q=${location.latitude}%2C${location.longitude}"
      return listOfNotNull(location.name, address?.takeIf { it.isNotEmpty() }, link).joinToString("\n")
    }
  }

  private val dynamicTheme: DynamicTheme = DynamicNoActionBarTheme()

  @VisibleForTesting
  lateinit var location: TellomiLocationMessage
    private set

  @VisibleForTesting
  var address: String? = null
    private set

  private lateinit var subtitleView: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    dynamicTheme.onCreate(this)
    setContentView(R.layout.tellomi_location_activity)

    location = TellomiLocationMessage(
      latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
      longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0),
      name = intent.getStringExtra(EXTRA_NAME),
      address = intent.getStringExtra(EXTRA_ADDRESS)
    )
    address = location.address

    val root = findViewById<View>(R.id.tellomi_location_root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
      insets
    }

    val toolbar = findViewById<MaterialToolbar>(R.id.tellomi_location_toolbar)
    toolbar.setNavigationOnClickListener { finish() }
    toolbar.setTitle(if (location.isPlace) R.string.TellomiLocation__title_place else R.string.TellomiLocation__title)
    toolbar.menu.add(0, R.id.tellomi_location_menu_copy, 0, R.string.TellomiLocation__copy_address)
    toolbar.menu.add(0, R.id.tellomi_location_menu_share, 1, R.string.TellomiLocation__share)
    toolbar.setOnMenuItemClickListener(::onMenuItem)
    updateMenu(toolbar)

    // Measured 2:1 before Glide sizes the image, so the snapshot is shown whole (not cropped to a placeholder height).
    val map = findViewById<AspectRatioImageView>(R.id.tellomi_location_map)
    map.aspectRatio = 2.0
    IntentCompat.getParcelableExtra(intent, EXTRA_MAP, Uri::class.java)?.let { uri ->
      Glide.with(this).load(DecryptableUri(uri)).centerCrop().into(map)
    }

    findViewById<ImageView>(R.id.tellomi_location_icon).setImageDrawable(iconDrawable())
    findViewById<TextView>(R.id.tellomi_location_title).text = location.name ?: getString(R.string.TellomiLocation__title)
    subtitleView = findViewById(R.id.tellomi_location_subtitle)
    updateSubtitle()

    findViewById<View>(R.id.tellomi_location_directions).setOnClickListener { showDirections() }

    if (address == null) {
      lookUpAddress(toolbar)
    }
  }

  override fun onResume() {
    super.onResume()
    dynamicTheme.onResume(this)
  }

  /** A filled circle in the accent colour with a white pin (Telegram: the venue icon; no venue type in this format). */
  private fun iconDrawable(): LayerDrawable {
    val circle = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(ContextCompat.getColor(this@TellomiLocationActivity, org.signal.core.ui.R.color.signal_colorPrimary))
    }
    val pin = AppCompatResources.getDrawable(this, R.drawable.ic_map_marker)!!.mutate().apply { setTint(Color.WHITE) }
    return LayerDrawable(arrayOf(circle, pin)).apply {
      val inset = 10.dp
      setLayerInset(1, inset, inset, inset, inset)
    }
  }

  private fun updateSubtitle() {
    subtitleView.text = subtitle(address ?: getString(R.string.TellomiLocation__locating), distanceText())
  }

  /** Only when permission was already granted; the last known position, no new request. */
  private fun distanceText(): String? {
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val manager = ContextCompat.getSystemService(this, LocationManager::class.java) ?: return null
    val lastKnown = try {
      manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (e: SecurityException) {
      Log.w(TAG, "No location.", e)
      null
    } ?: return null
    val results = FloatArray(1)
    Location.distanceBetween(lastKnown.latitude, lastKnown.longitude, location.latitude, location.longitude, results)
    val meters = results[0].toDouble()
    return if (meters < HERE_METERS) {
      getString(R.string.TellomiLocation__you_are_here)
    } else {
      getString(R.string.TellomiLocation__distance_away, formatDistance(meters, Locale.getDefault()))
    }
  }

  @Suppress("DEPRECATION")
  private fun lookUpAddress(toolbar: MaterialToolbar) {
    if (!Geocoder.isPresent()) {
      address = ""
      updateSubtitle()
      return
    }
    val geocoder = Geocoder(this)
    SignalExecutors.BOUNDED.execute {
      val found = try {
        geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.getAddressLine(0)
      } catch (e: Exception) {
        Log.w(TAG, "Reverse geocoding failed.", e)
        null
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        // Telegram: nothing found → no address, not "Locating…" forever.
        address = found ?: ""
        updateSubtitle()
        updateMenu(toolbar)
      }
    }
  }

  private fun updateMenu(toolbar: MaterialToolbar) {
    toolbar.menu.findItem(R.id.tellomi_location_menu_copy)?.isVisible = !address.isNullOrEmpty()
  }

  private fun onMenuItem(item: MenuItem): Boolean {
    return when (item.itemId) {
      R.id.tellomi_location_menu_copy -> {
        copyAddress()
        true
      }
      R.id.tellomi_location_menu_share -> {
        share()
        true
      }
      else -> false
    }
  }

  @VisibleForTesting
  fun copyAddress() {
    val text = listOfNotNull(location.name, address?.takeIf { it.isNotEmpty() }).joinToString(" ")
    if (text.isEmpty()) return
    val clipboard = ContextCompat.getSystemService(this, ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.TellomiLocation__copy_address), text))
    if (Build.VERSION.SDK_INT < 33) {
      Toast.makeText(this, R.string.TellomiLocation__address_copied, Toast.LENGTH_SHORT).show()
    }
  }

  private fun share() {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(location, address))
    startActivity(Intent.createChooser(send, getString(R.string.TellomiLocation__share)))
  }

  @VisibleForTesting
  fun showDirections() {
    val apps = TellomiMapApps.installed(packageManager, location, getString(R.string.TellomiLocation__title))
    when (apps.size) {
      0 -> Toast.makeText(this, R.string.TellomiLocation__no_map_app, Toast.LENGTH_SHORT).show()
      1 -> open(apps[0])
      else -> {
        val adapter = object : ArrayAdapter<TellomiMapApps.MapApp>(this, 0, apps) {
          override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.tellomi_location_app_row, parent, false)
            val app = getItem(position)!!
            row.findViewById<ImageView>(R.id.tellomi_location_app_icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.tellomi_location_app_label).text = app.label
            return row
          }
        }
        MaterialAlertDialogBuilder(this)
          .setTitle(R.string.TellomiLocation__open_in)
          .setAdapter(adapter) { _, which -> open(apps[which]) }
          .setNegativeButton(android.R.string.cancel, null)
          .show()
      }
    }
  }

  private fun open(app: TellomiMapApps.MapApp) {
    try {
      startActivity(app.intent)
    } catch (e: ActivityNotFoundException) {
      Log.w(TAG, "Map app went away: ${app.packageName}", e)
      Toast.makeText(this, R.string.TellomiLocation__no_map_app, Toast.LENGTH_SHORT).show()
    }
  }
}
