package com.yasincarplay.audio.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * AGAMA tarzı siyah karbon fiber arka plan.
 * Doku, bir kez küçük bir karo (tile) olarak çizilir ve BitmapShader ile tekrarlanır;
 * üzerine kenarlara doğru kararan hafif bir vinyet eklenir. Harici görsel dosyası gerekmez.
 */
class CarbonFiberDrawable : Drawable() {

    private val tile: Bitmap by lazy { buildTile() }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, tilePaint)
        val radius = max(b.width(), b.height()) * 0.75f
        vignettePaint.shader = RadialGradient(
            b.exactCenterX(), b.exactCenterY(), radius,
            intArrayOf(Color.TRANSPARENT, 0x59000000),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(b, vignettePaint)
    }

    override fun setAlpha(alpha: Int) {
        tilePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        tilePaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.OPAQUE

    /** 64x64 karo: çapraz yönlerde ışınlı ince hücrelerle twill dokuma görünümü. */
    private fun buildTile(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0A0A0C.toInt())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = 8
        for (y in 0 until size step cell) {
            for (x in 0 until size step cell) {
                val diagonal = ((x + y) / cell) % 2 == 0
                val rect = RectF(x + 0.5f, y + 0.5f, x + cell - 0.5f, y + cell - 0.5f)
                paint.shader = if (diagonal) {
                    LinearGradient(
                        x, y, x + cell, y + cell,
                        0xFF17181D.toInt(), 0xFF0D0E11.toInt(), Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        x + cell, y, x, y + cell,
                        0xFF14151A.toInt(), 0xFF0C0D10.toInt(), Shader.TileMode.CLAMP,
                    )
                }
                c.drawRoundRect(rect, 1.5f, 1.5f, paint)
            }
        }
        return bmp
    }
}
