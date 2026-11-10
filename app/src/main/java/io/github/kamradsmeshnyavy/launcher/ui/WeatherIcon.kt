package io.github.kamradsmeshnyavy.launcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.github.kamradsmeshnyavy.launcher.core.Weather
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Значок погоды линиями, как LineIcon: 24 × 24 единицы, цвет текста темы. */
class WeatherIcon(private val kind: Weather.Kind, private val day: Boolean, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.6f
        this.color = color
    }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(s, s)
        when (kind) {
            Weather.Kind.CLEAR -> if (day) sun(canvas, 12f, 12f, 4.2f) else moon(canvas, 12f, 12f, 6f)
            Weather.Kind.PARTLY -> {
                if (day) sun(canvas, 8.5f, 8.5f, 3f) else moon(canvas, 8.5f, 8.5f, 4f)
                cloud(canvas, 13.5f, 15f)
            }
            Weather.Kind.CLOUDY -> cloud(canvas, 12f, 12.5f)
            Weather.Kind.FOG -> for (y in floatArrayOf(8f, 12f, 16f)) canvas.drawLine(4f, y, 20f, y, stroke)
            Weather.Kind.DRIZZLE -> {
                cloud(canvas, 12f, 9.5f)
                for (x in floatArrayOf(8f, 12f, 16f)) canvas.drawPoint(x, 18.5f, stroke.apply { strokeWidth = 2.2f })
                stroke.strokeWidth = 1.6f
            }
            Weather.Kind.RAIN -> {
                cloud(canvas, 12f, 9.5f)
                for (x in floatArrayOf(8f, 12f, 16f)) canvas.drawLine(x, 16.5f, x - 1.2f, 20f, stroke)
            }
            Weather.Kind.SNOW -> {
                cloud(canvas, 12f, 9.5f)
                for (x in floatArrayOf(8f, 12f, 16f)) {
                    canvas.drawLine(x - 1.4f, 18.5f, x + 1.4f, 18.5f, stroke)
                    canvas.drawLine(x, 17.1f, x, 19.9f, stroke)
                }
            }
            Weather.Kind.STORM -> {
                cloud(canvas, 12f, 9.5f)
                val p = android.graphics.Path().apply {
                    moveTo(13f, 15f); lineTo(10.5f, 18.5f); lineTo(13.5f, 18.5f); lineTo(11f, 22f)
                }
                canvas.drawPath(p, stroke)
            }
        }
        canvas.restore()
    }

    private fun sun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, stroke)
        for (i in 0 until 8) {
            val a = i * PI / 4
            val c = cos(a).toFloat()
            val n = sin(a).toFloat()
            canvas.drawLine(cx + c * (r + 2f), cy + n * (r + 2f), cx + c * (r + 3.6f), cy + n * (r + 3.6f), stroke)
        }
    }

    private fun moon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 60f, 240f, false, stroke)
        oval.set(cx - r * 0.3f, cy - r * 0.95f, cx + r * 1.2f, cy + r * 0.95f)
        canvas.drawArc(oval, 120f, 120f, false, stroke)
    }

    /** Облако: основание [cx] ± 7, низ на [cy] + 4. */
    private fun cloud(canvas: Canvas, cx: Float, cy: Float) {
        val p = android.graphics.Path().apply {
            moveTo(cx - 5f, cy + 4f)
            lineTo(cx + 5f, cy + 4f)
            arcTo(RectF(cx + 1f, cy - 2.5f, cx + 8f, cy + 4.5f), 90f, -180f)
            arcTo(RectF(cx - 4f, cy - 6f, cx + 4f, cy + 2f), -20f, -160f)
            arcTo(RectF(cx - 8f, cy - 2.5f, cx - 1f, cy + 4.5f), -90f, -180f)
            close()
        }
        canvas.drawPath(p, stroke)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
