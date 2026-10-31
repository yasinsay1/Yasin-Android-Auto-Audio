package com.yasincarplay.audio.location

/** Bir GPS düzeltmesinin hızla ilgili kısmı. */
data class GpsFix(
    /** m/s cinsinden hız; düzeltme hız bilgisi taşımıyorsa null. */
    val speedMps: Float?,
    /** Düzeltmenin alındığı an (SystemClock.elapsedRealtime tabanlı, milisaniye). */
    val elapsedMs: Long,
)

/**
 * Hız göstergesinin gösterebileceği durumlar.
 * Sayı yalnızca [Valid] durumunda gösterilir; başka hiçbir durumda tahmini ya da sahte hız üretilmez.
 */
sealed interface SpeedReading {
    /** Kesin konum izni yok. [approximateOnly] true ise yalnızca yaklaşık konum verilmiş (GPS için yetmez). */
    data class NoPermission(val approximateOnly: Boolean) : SpeedReading

    /** Cihazda GPS sağlayıcısı yok. */
    data object NoGps : SpeedReading

    /** Konum/GPS kapalı. */
    data object ProviderOff : SpeedReading

    /** GPS açık ama henüz ilk düzeltme gelmedi. */
    data object Searching : SpeedReading

    /** Daha önce düzeltme vardı ama artık yeni veri gelmiyor (tünel, kapalı alan). */
    data object SignalLost : SpeedReading

    /** Düzeltme geldi ama hız bilgisi taşımıyor. */
    data object NoSpeed : SpeedReading

    /** Taze, gerçek GPS hızı (km/s). */
    data class Valid(val kmh: Float) : SpeedReading
}

/** Ham durumdan hangi [SpeedReading]'in gösterileceğine karar verir. Android'e bağımlı değildir. */
object SpeedEvaluator {

    /** Bu süreden eski düzeltme "sinyal kayboldu" sayılır. GPS normalde saniyede bir güncellenir. */
    const val STALE_AFTER_MS = 5_000L

    /** Bu hızın altı, duran araçtaki GPS gürültüsü sayılıp 0 gösterilir (gerçek düzeltme, yine de veri). */
    const val STANDSTILL_MPS = 0.5f

    fun evaluate(
        hasFinePermission: Boolean,
        hasCoarsePermission: Boolean,
        hasGpsProvider: Boolean,
        gpsEnabled: Boolean,
        fix: GpsFix?,
        nowElapsedMs: Long,
    ): SpeedReading {
        if (!hasFinePermission) return SpeedReading.NoPermission(approximateOnly = hasCoarsePermission)
        if (!hasGpsProvider) return SpeedReading.NoGps
        if (!gpsEnabled) return SpeedReading.ProviderOff
        if (fix == null) return SpeedReading.Searching
        if (nowElapsedMs - fix.elapsedMs > STALE_AFTER_MS) return SpeedReading.SignalLost
        val mps = fix.speedMps
        if (mps == null || mps.isNaN() || mps < 0f) return SpeedReading.NoSpeed
        val kmh = if (mps < STANDSTILL_MPS) 0f else mps * 3.6f
        return SpeedReading.Valid(kmh)
    }
}
