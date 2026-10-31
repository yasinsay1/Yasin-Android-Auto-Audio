package com.yasincarplay.audio.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.yasincarplay.audio.R
import com.yasincarplay.audio.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Üst köşe durum göstergeleri: bağlantı (WiFi/veri), GPS, hava durumu
 * ve alt köşe Bluetooth.
 *
 * Hava durumu: konum izni verilmişse cihazın konumuna göre Open-Meteo'dan
 * (anahtar gerektirmez) gerçek sıcaklık çekilir; izinsizse sıcaklık gösterilmez,
 * sahte veri üretilmez.
 */
class StatusMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connectionIcon: ImageView,
    private val connectionLabel: TextView,
    private val weatherIcon: ImageView,
    private val weatherLabel: TextView,
    private val bluetoothIcon: ImageView,
    private val locationTracker: LocationTracker,
) {

    private val connectivity: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            updateConnection()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            updateBluetooth()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        context.registerReceiver(receiver, filter)
        val btFilter = IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothReceiver, btFilter)
        updateConnection()
        updateBluetooth()
        refreshWeather()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
    }

    // --- Bağlantı ------------------------------------------------------------

    private fun updateConnection() {
        val caps = connectivity.activeNetwork
            ?.let { connectivity.getNetworkCapabilities(it) }
        val isWifi = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        when {
            isWifi -> {
                connectionIcon.setImageResource(R.drawable.ic_wifi)
                connectionLabel.text = context.getString(R.string.status_wifi)
            }
            isMobile -> {
                connectionIcon.setImageResource(R.drawable.ic_wifi)
                connectionLabel.text = context.getString(R.string.status_mobile)
            }
            else -> {
                connectionIcon.setImageResource(R.drawable.ic_wifi)
                connectionLabel.text = context.getString(R.string.status_offline)
            }
        }
    }

    // --- Bluetooth -------------------------------------------------------------

    private fun updateBluetooth() {
        val enabled = try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
        bluetoothIcon.alpha = if (enabled) 1f else 0.35f
    }

    // --- Hava durumu -------------------------------------------------------------

    private fun refreshWeather() {
        scope.launch {
            val (lat, lon) = withContext(Dispatchers.IO) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                var lat: Double? = null
                var lon: Double? = null
                try {
                    if (locationTracker.hasPermission()) {
                        val last = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (last != null) {
                            lat = last.latitude
                            lon = last.longitude
                        }
                    }
                } catch (e: Exception) {
                    // konum yoksa sahte hava durumu gösterilmez
                }
                lat to lon
            }
            if (lat == null || lon == null) {
                weatherLabel.text = context.getString(R.string.temp_unknown)
                return@launch
            }
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
                val body = withContext(Dispatchers.IO) { Http.getString(url) }
                val json = JSONObject(body)
                val temp = json.getJSONObject("current").optDouble("temperature_2m", Double.NaN)
                val code = json.getJSONObject("current").optInt("weather_code", -1)
                if (temp.isNaN()) return@launch
                weatherLabel.text = (if (temp >= 0) "+" else "") + temp.toInt() + "°"
                weatherIcon.setImageResource(weatherIconRes(code))
            } catch (e: Exception) {
                // ağ hatası: mevcut etiket korunur, sahte değer yazılmaz
            }
        }
    }

    private fun weatherIconRes(code: Int): Int = when (code) {
        in 1..3 -> R.drawable.ic_cloud
        in 45..48 -> R.drawable.ic_cloud
        in 51..67, in 80..82, 86, 87 -> R.drawable.ic_rain
        in 71..77, in 85..86 -> R.drawable.ic_rain
        in 88..91 -> R.drawable.ic_rain
        0 -> R.drawable.ic_sunny
        else -> R.drawable.ic_cloud
    }
}
