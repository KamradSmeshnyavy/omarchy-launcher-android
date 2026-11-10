package io.github.kamradsmeshnyavy.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.kamradsmeshnyavy.launcher.core.Look

/**
 * Живая сцена стиля в цветах его темы, как карточка «Стиль» в Customizer: фон, карточка
 * в материале стиля с часами (точками у DOT-MATRIX), строки текста и акцент.
 */
class StylePreview(context: Context, private val style: Look.Style, theme: Look.Theme?) : View(context) {
    private val p = Look.palette(theme, mono = false, values = emptyMap())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val material = style.look.optString("material", "solid")
    private val corner = style.look.optDouble("cornerScale", 1.0).toFloat()
    private val elevation = style.look.optInt("elevation", 0)
    private val dots = style.id == "dotmatrix"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = p.bg
        val outer = 12 * d * corner.coerceAtMost(1.2f)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, outer, outer, paint)
        // Фон стекла — пятна акцента, чтобы было что размывать.
        if (material == "glass" || material == "auto" || material == "glow") {
            paint.color = withAlpha(p.signal, 0.35f)
            canvas.drawCircle(w * 0.78f, h * 0.25f, h * 0.32f, paint)
            paint.color = withAlpha(p.danger, 0.25f)
            canvas.drawCircle(w * 0.2f, h * 0.85f, h * 0.28f, paint)
        }
        val r = 10 * d * corner
        rect.set(w * 0.08f, h * 0.14f, w * 0.92f, h * 0.86f)
        // Тень: жёсткая у брутализма, мягкая двойная у неоморфизма, обычная по elevation.
        when {
            material == "brutal" -> {
                paint.color = p.fg
                canvas.drawRoundRect(rect.left + 4 * d, rect.top + 4 * d, rect.right + 4 * d, rect.bottom + 4 * d, r, r, paint)
            }
            material == "soft" -> {
                paint.color = Look.mix(p.bg, 0xFF000000.toInt(), 0.25f)
                canvas.drawRoundRect(rect.left + 3 * d, rect.top + 3 * d, rect.right + 3 * d, rect.bottom + 3 * d, r, r, paint)
                paint.color = Look.mix(p.bg, 0xFFFFFFFF.toInt(), 0.12f)
                canvas.drawRoundRect(rect.left - 2 * d, rect.top - 2 * d, rect.right - 2 * d, rect.bottom - 2 * d, r, r, paint)
            }
            elevation > 0 -> {
                paint.color = withAlpha(0xFF000000.toInt(), 0.18f * elevation)
                canvas.drawRoundRect(rect.left, rect.top + 2 * d * elevation, rect.right, rect.bottom + 2 * d * elevation, r, r, paint)
            }
        }
        paint.color = when (material) {
            "glass", "auto" -> withAlpha(p.surface, 0.62f)
            "soft" -> p.bg
            else -> p.surface
        }
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = when (material) {
            "brutal" -> 2.5f * d
            "soft" -> 0f
            else -> d
        }
        paint.color = when (material) {
            "brutal" -> p.fg
            "glow" -> p.signal
            "glass", "auto" -> withAlpha(p.fg, 0.2f)
            "bevel" -> p.dim
            else -> p.line
        }
        if (paint.strokeWidth > 0) canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL

        // Часы: точками или полосой.
        val left = rect.left + 12 * d
        val top = rect.top + 12 * d
        if (dots) {
            val step = 4.2f * d
            paint.color = p.fg
            val pattern = arrayOf("111 111", "101 001", "101 111", "101 100", "111 111")
            pattern.forEachIndexed { row, line ->
                line.forEachIndexed { col, ch ->
                    if (ch == '1') canvas.drawCircle(left + col * step, top + row * step, step * 0.36f, paint)
                }
            }
        } else {
            paint.color = p.fg
            canvas.drawRoundRect(left, top, left + rect.width() * 0.42f, top + 16 * d, 3 * d * corner, 3 * d * corner, paint)
        }
        // Строки текста.
        paint.color = p.dim
        val lineTop = rect.top + rect.height() * 0.55f
        canvas.drawRoundRect(left, lineTop, left + rect.width() * 0.6f, lineTop + 5 * d, 2.5f * d, 2.5f * d, paint)
        paint.color = p.muted
        canvas.drawRoundRect(left, lineTop + 10 * d, left + rect.width() * 0.4f, lineTop + 15 * d, 2.5f * d, 2.5f * d, paint)
        // Акцент.
        paint.color = p.signal
        val ar = 6 * d
        canvas.drawCircle(rect.right - 14 * d, rect.bottom - 14 * d, ar, paint)
    }
}

/** Плитка темы: миниатюра обоев (если есть в сборке) или полосы палитры, внизу — цвета ролей. */
class ThemeSwatchView(context: Context, private val theme: Look.Theme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val c = theme.colors
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Nihil.corner(10 * d)
        val save = canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        val bg = c["background"] ?: 0xFF000000.toInt()
        paint.color = bg
        canvas.drawRect(0f, 0f, w, h, paint)
        val bmp = bitmap
        val bandTop = h * 0.78f
        if (bmp != null) {
            val scale = maxOf(w / bmp.width, bandTop / bmp.height)
            val bw = bmp.width * scale
            val bh = bmp.height * scale
            rect.set((w - bw) / 2, (bandTop - bh) / 2, (w + bw) / 2, (bandTop + bh) / 2)
            canvas.save()
            canvas.clipRect(0f, 0f, w, bandTop)
            canvas.drawBitmap(bmp, null, rect, paint)
            canvas.restore()
        } else {
            // Без обоев: текст темы на её фоне.
            paint.color = c["foreground"] ?: 0xFFFFFFFF.toInt()
            val x = w * 0.1f
            canvas.drawRoundRect(x, h * 0.2f, x + w * 0.5f, h * 0.2f + 6 * d, 3 * d, 3 * d, paint)
            paint.color = c["muted"] ?: paint.color
            canvas.drawRoundRect(x, h * 0.2f + 12 * d, x + w * 0.35f, h * 0.2f + 18 * d, 3 * d, 3 * d, paint)
            paint.color = c["accent"] ?: paint.color
            canvas.drawCircle(w * 0.84f, h * 0.24f, 7 * d, paint)
        }
        val keys = listOf("background", "foreground", "accent", "red", "green", "yellow", "blue", "magenta", "cyan")
        val band = keys.mapNotNull { c[it] }
        val cw = w / band.size
        band.forEachIndexed { i, color ->
            paint.color = color
            canvas.drawRect(i * cw, bandTop, (i + 1) * cw + 1, h, paint)
        }
        canvas.restoreToCount(save)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = Nihil.LINE
        canvas.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, r, r, paint)
        paint.style = Paint.Style.FILL
    }
}

/** Образец профиля шрифтов: «12:45» точками и строка текста его голосами. */
fun Context.fontSample(profile: String): View {
    val fonts = Nihil.Fonts(this, profile)
    return android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        addView(text("12:45", 30f, Nihil.INK, fonts.dot))
        addView(text("Съешь же ещё этих мягких булок — Sphinx of black quartz", 13f, Nihil.DIM, fonts.sans), llp(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(text("0xC0FFEE · 4.2 GB · 87%", 11f, Nihil.MUTED, fonts.mono), llp(MATCH, WRAP).apply { topMargin = dp(2) })
    }
}
