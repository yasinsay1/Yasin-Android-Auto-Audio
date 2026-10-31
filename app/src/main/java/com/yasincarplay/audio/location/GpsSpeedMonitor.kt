package com.yasincarplay.audio.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat

/**
 * Telefonun GPS sağlayıcısından gerçek hızı okur ve her değişiklikte bir [SpeedReading] bildirir.
 *
 * - Yalnızca GPS sağlayıcısı dinlenir; ağ/tahmini konum ya da "son bilinen konum" kullanılmaz.
 * - Sahte konum sağlayıcılarından gelen düzeltmeler yok sayılır.
 * - Yalnızca [start] ile [stop] arasında (ekran açıkken) dinler; arka planda konum okumaz.
 * - Veri yoksa (izin yok, GPS kapalı, sinyal yok) sayı değil, durum bildirir; hiçbir koşulda hız uydurmaz.
 *
 * Tüm geri çağrılar ana iş parçacığında gelir.
 */
class GpsSpeedMonitor(
    context: Context,
    private val onReading: (SpeedReading) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var listening = false
    private var fix: GpsFix? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (isMock(location)) return
            fix = GpsFix(speedOf(location), location.elapsedRealtimeNanos / NANOS_PER_MS)
            publish()
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                fix = null
                publish()
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                fix = null
                publish()
            }
        }
    }

    /** Saniyede bir: izin sonradan verildiyse dinlemeye başlar, bayat düzeltmeyi "sinyal kayboldu"ya çevirir. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!started) return
            registerIfPossible()
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        fix = null
        registerIfPossible()
        publish()
        handler.postDelayed(ticker, TICK_MS)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(ticker)
        if (listening) {
            locationManager.removeUpdates(listener)
            listening = false
        }
        fix = null
    }

    /** İzin ya da GPS ayarı değişmiş olabilir; hemen yeniden değerlendirir. */
    fun refresh() {
        if (!started) return
        registerIfPossible()
        publish()
    }

    private fun registerIfPossible() {
        if (listening || !hasFinePermission() || !hasGpsProvider()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            listening = true
        } catch (e: SecurityException) {
            // İzin tam bu sırada geri alındı; bir sonraki saniyede yeniden denenir.
        } catch (e: IllegalArgumentException) {
            // GPS sağlayıcısı kayboldu; bir sonraki saniyede yeniden değerlendirilir.
        }
    }

    private fun publish() {
        onReading(
            SpeedEvaluator.evaluate(
                hasFinePermission = hasFinePermission(),
                hasCoarsePermission = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                hasGpsProvider = hasGpsProvider(),
                gpsEnabled = hasGpsProvider() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
                fix = fix,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        )
    }

    private fun hasFinePermission() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasGpsProvider() = locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)

    /** Düzeltme hız taşımıyorsa ya da değer geçersizse null: bu durumda sayı gösterilmez. */
    private fun speedOf(location: Location): Float? {
        if (!location.hasSpeed()) return null
        val speed = location.speed
        return if (speed.isNaN() || speed < 0f) null else speed
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val TICK_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
