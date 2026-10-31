package com.yasincarplay.audio.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.yasincarplay.audio.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AGAMA referansındaki dairesel GPS hız göstergesi.
 * Hız yalnızca [LocationTracker] tarafından sağlanan gerçek GPS verisinden gelir;
 * [hasGpsData] false ise sahte hız değil, 0 ve "GPS verisi yok" etiketi çizilir.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var speedKmh: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    var hasGpsData: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val maxSpeed = 240f
    private val startAngle = 135f
    private val sweepAngle = 270f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val colorGreen = resources.getColor(R.color.speed_green, null)
    private val colorGold = resources.getColor(R.color.speed_gold, null)
    private val colorRed = resources.getColor(R.color.speed_red, null)
    private val colorGoldBright = resources.getColor(R.color.gold_bright, null)
    private val colorDim = resources.getColor(R.color.text_dim, null)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    private fun angleFor(speed: Float) = startAngle + (speed / maxSpeed) * sweepAngle

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val size = min(width, height).toFloat()
        val r = size / 2f - dp(6f)

        // 1) Zemin halka
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = 0x33D9B86C
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, paint)

        // 2) Bölge yayları: yeşil (0-80), altın (80-140), kırmızı (140-240)
        val zoneWidth = dp(10f)
        paint.strokeWidth = zoneWidth
        drawZone(canvas, cx, cy, r, 0f, 80f, colorGreen, 0x66)
        drawZone(canvas, cx, cy, r, 80f, 140f, colorGold, 0x66)
        drawZone(canvas, cx, cy, r, 140f, maxSpeed, colorRed, 0x66)

        // 3) İnce tıklar (10 km/h'de bir), kalın tıklar (20 km/h'de bir)
        val tickOuter = r - zoneWidth - dp(2f)
        for (s in 0..maxSpeed step 10) {
            val major = s % 20 == 0
            val a = Math.toRadians(angleFor(s).toDouble())
            val inner = tickOuter - (if (major) dp(14f) else dp(8f))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (major) dp(2.5f) else dp(1.2f)
            paint.color = if (major) 0xB3FFFFFF else 0x4DFFFFFF
            canvas.drawLine(
                cx + cos(a) * inner, cy + sin(a) * inner,
                cx + cos(a) * tickOuter, cy + sin(a) * tickOuter,
                paint,
            )
        }

        // 4) Mevcut hız yayı (parlak altın)
        if (hasGpsData && speedKmh > 0.5f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = zoneWidth + dp(4f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = colorGoldBright
            val end = angleFor(min(speedKmh, maxSpeed))
            canvas.drawArc(arcRect, startAngle, end - startAngle, false, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }

        // 5) Merkez metinler
        val displaySpeed = if (hasGpsData) speedKmh else 0f
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER

        val numberSize = size * 0.30f
        paint.textSize = numberSize
        paint.color = if (hasGpsData) colorGoldBright else 0x80D9B86C
        paint.isFakeBoldText = true
        canvas.drawText(displaySpeed.toInt().toString(), cx, cy + numberSize * 0.32f, paint)

        paint.isFakeBoldText = false
        paint.textSize = dp(15f)
        paint.color = colorGold
        canvas.drawText(context.getString(R.string.gps_unit), cx, cy + numberSize * 0.32f + dp(26f), paint)

        paint.textSize = dp(12f)
        paint.color = colorDim
        val statusText =
            if (hasGpsData) "GPS" else context.getString(R.string.gps_no_data)
        canvas.drawText(statusText, cx, cy - numberSize * 0.55f, paint)
    }

    private fun drawZone(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        from: Float, to: Float, color: Int, alpha: Int,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = (color and 0x00FFFFFF) or (alpha shl 24)
        val start = angleFor(from)
        val end = angleFor(to)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, start, end - start, false, paint)
    }
}
