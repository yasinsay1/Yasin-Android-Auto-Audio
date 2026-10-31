package com.yasincarplay.audio.ui.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gerçek GPS hız takibi.
 *
 * SAHTE HIZ GÖSTERMEZ:
 * - İzin yoksa [speedKmh] = 0 ve [hasData] = false kalır.
 * - Sadece GPS verisi geldiğinde hız güncellenir.
 *
 * Hız kaynağı:
 * 1) Konum sağlayıcısının bildirdiği hızı (Location.speed, m/s) — 0.5 m/s üzeriysa kullanılır.
 * 2) Yoksa son iki konumdan haversine mesafe / geçen süre ile hesaplanır.
 * 3) Araç duruyorsa (hız ~0) değer 0'a çekilir.
 */
class LocationTracker(private val context: Context) {

    var speedKmh: Float = 0f
        private set

    /** En az bir geçerli konum alındıysa true. */
    var hasData: Boolean = false
        private set

    var onSpeedUpdate: ((speedKmh: Float, hasData: Boolean) -> Unit)? = null

    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var lastLocation: Location? = null
    private var lastFixTime: Long = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** İzin varsa GPS + network sağlayıcıları ile takibe başlar. */
    @SuppressLint("MissingPermission")
    fun start() {
        val lm = locationManager ?: return
        if (!hasPermission()) return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            // İzin çalışma zamanında çekilmiş olabilir; sahte veri üretmeyiz.
        }
    }

    fun stop() {
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(listener)
        } catch (e: SecurityException) {
            // yoksay
        }
    }

    @SuppressLint("MissingPermission")
    private fun onNewLocation(location: Location) {
        if (!location.hasAccuracy() && !location.hasSpeed()) return
        val now = System.currentTimeMillis()
        val prev = lastLocation
        lastLocation = location
        if (lastFixTime == 0L) lastFixTime = now

        val elapsedSec = (now - lastFixTime) / 1000.0
        lastFixTime = now

        var kmh: Float = 0f
        if (location.hasSpeed()) {
            val mps = location.speed
            if (mps >= 0f) kmh = (mps * 3.6f)
        } else if (prev != null && elapsedSec >= 1.0 && location.hasAccuracy()) {
            val meters = haversineMeters(
                prev.latitude, prev.longitude, location.latitude, location.longitude,
            )
            if (meters >= 0) kmh = (meters / elapsedSec * 3.6f).toFloat()
        }

        // GPS hız gürültüsünü bastır: çok küçük değerleri 0'a yuvarla.
        if (kmh < 1.5f) kmh = 0f

        speedKmh = kmh
        hasData = true
        onSpeedUpdate?.invoke(kmh, true)
    }

    /** İki koordinat arası yaklaşık mesafe (metre). Geçersiz girişte -1. */
    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    ): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
