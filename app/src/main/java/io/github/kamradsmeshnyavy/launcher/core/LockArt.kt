package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.desk.Dots
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Дизайны экрана блокировки Omarchy (kamradsmeshnyavy.lock, 30 штук) на телефоне.
 *
 * Системный экран блокировки Android приложение заменить не может, поэтому дизайн живёт
 * в двух местах: обоями экрана блокировки ([live] = false — без своих часов: сверху лягут
 * системные, а время на картинке застыло бы) и заставкой (LockDream, [live] = true — с часами,
 * датой, батареей и тем, что играет). 30 дизайнов рисуются ~12 приёмами.
 *
 * Подложка — обои темы с компьютера, своё фото или градиент темы; размытие и затемнение —
 * wallpaperBlur и wallpaperDim (LOCK_TWEAKS Customizer'а).
 */
object LockArt {
    const val DESIGN = "lockDesign"
    const val SOURCE = "lockSource"
    const val PHOTO = "lockPhoto"

    class Design(val id: String, val name: String, val description: String, val descriptionRu: String, val tags: List<String>)

    class Live(val battery: Int, val charging: Boolean, val media: String, val weather: String)

    @Volatile
    private var designs: List<Design>? = null

    fun all(context: Context): List<Design> = designs ?: synchronized(this) {
        designs ?: runCatching {
            val a = JSONObject(context.assets.open("looks/locks.json").bufferedReader().use { it.readText() }).getJSONArray("designs")
            List(a.length()) { a.getJSONObject(it).let { o ->
                val t = o.optJSONArray("tags")
                Design(o.getString("id"), o.optString("name"), o.optString("description"), o.optString("description_ru"), List(t?.length() ?: 0) { i -> t!!.getString(i) })
            } }
        }.getOrDefault(emptyList()).also { designs = it }
    }

    /** Дизайн сейчас: свой, «как на компьютере» (lock-design.json) или DOT-MATRIX. */
    fun current(context: Context): String {
        val sp = Look.prefs(context)
        val own = sp.getString(DESIGN, null)
        if (own == "pc" || own == null) {
            sp.getString(Look.PC_SNAPSHOT, null)?.let { runCatching { JSONObject(it).optJSONObject("lock")?.optString("design") }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return own?.takeIf { it != "pc" } ?: "dotmatrix"
    }

    /** Подложка: файл, который ThemeWalls/фото положили в filesDir (без доступа к системным обоям). */
    fun baseFile(context: Context) = File(context.filesDir, "lock-base.jpg")

    fun base(context: Context, w: Int, h: Int): Bitmap? {
        val f = baseFile(context)
        if (!f.isFile || Look.prefs(context).getString(SOURCE, "theme") == "plain") return null
        return runCatching {
            val src = android.graphics.ImageDecoder.createSource(f)
            DotRender.decodeCover(src, w, h)
        }.getOrNull()
    }


    /** Картинка для экрана блокировки (без часов). */
    fun wallpaper(context: Context, design: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), context, design, w, h, base(context, w, h), null)
        return out
    }

    /** Мягкое размытие: вниз в [factor] раз и обратно с фильтрацией. */
    private fun blur(b: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.01f) return b
        val k = (4 + 28 * amount).toInt()
        val small = Bitmap.createScaledBitmap(b, (b.width / k).coerceAtLeast(2), (b.height / k).coerceAtLeast(2), true)
        return Bitmap.createScaledBitmap(small, b.width, b.height, true)
    }

    /**
     * Рисует дизайн [design] в ([w] × [h]); [base] — подложка (null — градиент темы);
     * [live] ≠ null — заставка: часы, дата, батарея, что играет.
     */
    fun draw(c: Canvas, context: Context, design: String, w: Int, h: Int, base: Bitmap?, live: Live?, shift: Float = 0f) {
        // Кисть — своя на каждый вызов: превью рисуются в несколько потоков.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val look = Look.now
        val p = look.palette
        // Единица — доля ширины (телефон 411 dp): превью и экран в одних пропорциях.
        val d = w / 411f
        val fonts = Nihil.fonts(context)
        val blurAmount = look.num("wallpaperBlur", if (design == "classic" || design.startsWith("style-")) 1.0 else 0.4).toFloat()
        val dim = look.num("wallpaperDim", 0.25).toFloat().coerceIn(0f, 0.9f)
        // Подложка.
        if (base != null && design != "rain" && design != "terminal" && design != "system") {
            val b = if (design == "dotmatrix") {
                runCatching { DotRender.render(DotRender.grid(base, (w / 42).coerceAtLeast(4)), w, h, p.fg, p.bg) }.getOrDefault(base)
            } else blur(base, blurAmount)
            c.drawBitmap(b, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(withAlpha(p.bg, dim))
        } else {
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p.bg, Look.mix(p.bg, p.signal, 0.12f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        }

        val now = Date()
        val is24 = (look.values["clock24"] as? Boolean) ?: true
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.ROOT).format(now)
        val locale = Locale.getDefault()
        val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now)
        val ox = shift * d * 6
        val oy = shift * d * 10

        fun text(t: String, x: Float, y: Float, size: Float, color: Int, face: Typeface, align: Paint.Align = Paint.Align.CENTER) {
            paint.typeface = face
            paint.textSize = size
            paint.color = color
            paint.textAlign = align
            c.drawText(t, x + ox, y + oy, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        fun dotTime(cx: Float, cy: Float, width: Float, color: Int = p.fg) {
            val pitch = width / Dots.columns(time)
            Dots.draw(c, time, cx - width / 2 + ox, cy - Dots.ROWS * pitch / 2 + oy, pitch, color, withAlpha(color, 0.08f), paint)
        }

        fun liveInfo(y: Float, align: Paint.Align = Paint.Align.CENTER, x: Float = w / 2f) {
            val l = live ?: return
            val parts = listOfNotNull("${l.battery}%" + if (l.charging) " ↯" else "", l.weather.ifEmpty { null }, l.media.ifEmpty { null })
            text(parts.joinToString("  ·  "), x, y, d * 13, p.dim, fonts.mono, align)
        }

        fun card(r: RectF, material: String) {
            val radius = when (material) {
                "brutal", "bevel" -> 0f
                "soft" -> d * 34
                "paper" -> d * 10
                else -> d * 26
            }
            when (material) {
                "brutal" -> {
                    paint.color = p.fg
                    c.drawRect(r.left + d * 8, r.top + d * 8, r.right + d * 8, r.bottom + d * 8, paint)
                }
                "soft" -> {
                    paint.color = Look.mix(p.bg, 0xFF000000.toInt(), 0.3f)
                    c.drawRoundRect(r.left + d * 6, r.top + d * 6, r.right + d * 6, r.bottom + d * 6, radius, radius, paint)
                }
            }
            paint.color = when (material) {
                "glass", "auto" -> withAlpha(p.surface, 0.55f)
                "soft" -> p.bg
                else -> p.surface
            }
            c.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = when (material) {
                "brutal" -> d * 3
                "soft" -> 0f
                else -> d
            }
            paint.color = when (material) {
                "brutal" -> p.fg
                "glow" -> p.signal
                "bevel" -> p.dim
                "glass", "auto" -> withAlpha(p.fg, 0.25f)
                else -> p.line
            }
            if (material == "glow") paint.maskFilter = BlurMaskFilter(d * 6, BlurMaskFilter.Blur.SOLID)
            if (paint.strokeWidth > 0) c.drawRoundRect(r, radius, radius, paint)
            paint.maskFilter = null
            paint.style = Paint.Style.FILL
        }

        fun ring(cx: Float, cy: Float, r: Float, count: Int, lit: Int) {
            for (i in 0 until count) {
                val a = i * 2 * PI / count - PI / 2
                paint.color = if (i < lit) p.fg else withAlpha(p.fg, 0.2f)
                c.drawCircle(cx + (cos(a) * r).toFloat() + ox, cy + (sin(a) * r).toFloat() + oy, d * 3, paint)
            }
        }

        val cx = w / 2f
        when (design) {
            "zen" -> {
                for (i in 0 until 6) {
                    paint.color = withAlpha(p.fg, if (i < 4) 0.9f else 0.3f)
                    c.drawCircle(cx - d * 50 + i * d * 20 + ox, h * 0.62f + oy, d * 4, paint)
                }
                if (live != null) text(time, cx, h * 0.45f, d * 72, p.fg, fonts.sans)
            }
            "dock" -> {
                rect.set(0f, h - d * 120, w.toFloat(), h.toFloat())
                paint.color = withAlpha(p.bg, 0.85f)
                c.drawRect(rect, paint)
                paint.color = p.line
                c.drawRect(0f, rect.top, w.toFloat(), rect.top + d, paint)
                if (live != null) {
                    text(time, d * 24, rect.top + d * 56, d * 44, p.fg, fonts.sansMedium, Paint.Align.LEFT)
                    text(date, d * 24, rect.top + d * 86, d * 14, p.dim, fonts.sans, Paint.Align.LEFT)
                }
                liveInfo(rect.top + d * 86, Paint.Align.RIGHT, w - d * 24f)
            }
            "island" -> {
                rect.set(cx - d * 150, h * 0.3f, cx + d * 150, h * 0.3f + d * 64)
                paint.color = withAlpha(p.bg, 0.9f)
                c.drawRoundRect(rect, d * 32, d * 32, paint)
                if (live != null) {
                    text(time, rect.left + d * 28, rect.centerY() + d * 12, d * 30, p.fg, fonts.sansMedium, Paint.Align.LEFT)
                    text(date, rect.right - d * 24, rect.centerY() + d * 5, d * 12, p.dim, fonts.mono, Paint.Align.RIGHT)
                } else {
                    for (i in 0 until 5) {
                        paint.color = withAlpha(p.fg, 0.5f)
                        c.drawCircle(rect.centerX() - d * 32 + i * d * 16, rect.centerY(), d * 3, paint)
                    }
                }
                liveInfo(rect.bottom + d * 30)
            }
            "split", "sheet" -> {
                rect.set(0f, 0f, w * 0.48f, h.toFloat())
                paint.color = if (design == "sheet") withAlpha(p.surface, 0.6f) else p.bg
                c.drawRect(rect, paint)
                paint.color = p.line
                c.drawRect(rect.right - d, 0f, rect.right, h.toFloat(), paint)
                if (live != null) {
                    text(time, rect.centerX(), h * 0.5f, d * 54, p.fg, fonts.sansMedium)
                    text(date, rect.centerX(), h * 0.5f + d * 34, d * 13, p.dim, fonts.sans)
                }
                liveInfo(h * 0.5f + d * 60, x = rect.centerX())
            }
            "cinema" -> {
                paint.color = 0xFF000000.toInt()
                c.drawRect(0f, 0f, w.toFloat(), h * 0.14f, paint)
                c.drawRect(0f, h * 0.86f, w.toFloat(), h.toFloat(), paint)
                if (live != null) {
                    text(time, cx, h * 0.93f + d * 4, d * 36, 0xFFF2F2F2.toInt(), fonts.sansMedium)
                    text(date.uppercase(locale), cx, h * 0.07f + d * 4, d * 12, 0xFFA3A3A3.toInt(), fonts.mono)
                }
            }
            "poster" -> {
                if (live != null) {
                    val hh = time.substringBefore(':')
                    val mm = time.substringAfter(':')
                    text(hh, cx, h * 0.45f, w * 0.62f, p.fg, fonts.sansMedium)
                    text(mm, cx, h * 0.45f + w * 0.55f, w * 0.62f, p.signal, fonts.sansMedium)
                } else {
                    paint.color = withAlpha(p.fg, 0.08f)
                    for (i in 0..6) c.drawRect(0f, h * (0.35f + i * 0.06f), w.toFloat(), h * (0.35f + i * 0.06f) + d * 2, paint)
                }
                liveInfo(h - d * 70)
            }
            "editorial" -> {
                paint.color = p.fg
                c.drawRect(d * 24, h * 0.36f, w - d * 24f, h * 0.36f + d * 2, paint)
                c.drawRect(d * 24, h * 0.62f, w - d * 24f, h * 0.62f + d, paint)
                text("DOT-MATRIX", d * 24, h * 0.36f - d * 10, d * 12, p.dim, fonts.mono, Paint.Align.LEFT)
                if (live != null) {
                    text(date, d * 24, h * 0.36f + d * 58, d * 38, p.fg, fonts.sansMedium, Paint.Align.LEFT)
                    text(time, d * 24, h * 0.62f - d * 20, d * 26, p.dim, fonts.mono, Paint.Align.LEFT)
                }
                liveInfo(h * 0.62f + d * 30, Paint.Align.LEFT, d * 24)
            }
            "analog", "ring", "music" -> {
                val r = min(w, h) * 0.34f
                val cy = h * 0.5f
                val cal = Calendar.getInstance()
                ring(cx, cy, r, 60, if (live != null && design == "ring") cal.get(Calendar.MINUTE) else 0)
                if (design == "music") {
                    paint.color = withAlpha(p.fg, 0.08f)
                    for (k in 1..5) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = d
                        c.drawCircle(cx + ox, cy + oy, r * (0.25f + k * 0.12f), paint)
                    }
                    paint.style = Paint.Style.FILL
                }
                if (live != null) {
                    if (design == "analog") {
                        val m = cal.get(Calendar.MINUTE)
                        val hr = cal.get(Calendar.HOUR) + m / 60f
                        paint.strokeCap = Paint.Cap.ROUND
                        paint.strokeWidth = d * 6
                        paint.color = p.fg
                        val ah = hr * PI / 6 - PI / 2
                        val am = m * PI / 30 - PI / 2
                        c.drawLine(cx + ox, cy + oy, cx + ox + (cos(ah) * r * 0.5f).toFloat(), cy + oy + (sin(ah) * r * 0.5f).toFloat(), paint)
                        paint.strokeWidth = d * 3
                        c.drawLine(cx + ox, cy + oy, cx + ox + (cos(am) * r * 0.82f).toFloat(), cy + oy + (sin(am) * r * 0.82f).toFloat(), paint)
                        paint.color = p.signal
                        c.drawCircle(cx + ox, cy + oy, d * 5, paint)
                    } else {
                        text(time, cx, cy + d * 16, d * 48, p.fg, fonts.sansMedium)
                    }
                }
                liveInfo(cy + r + d * 48)
            }
            "flip" -> {
                val fw = w * 0.36f
                for (k in 0..1) {
                    rect.set(cx - fw - d * 6 + k * (fw + d * 12), h * 0.38f, cx - d * 6 + k * (fw + d * 12), h * 0.38f + fw * 1.2f)
                    paint.color = p.surface
                    c.drawRoundRect(rect, d * 14, d * 14, paint)
                    paint.color = withAlpha(p.bg, 0.8f)
                    c.drawRect(rect.left, rect.centerY() - d, rect.right, rect.centerY() + d, paint)
                    if (live != null) text(if (k == 0) time.substringBefore(':') else time.substringAfter(':'), rect.centerX(), rect.centerY() + fw * 0.26f, fw * 0.72f, p.fg, fonts.sansMedium)
                }
                liveInfo(h * 0.38f + fw * 1.2f + d * 40)
            }
            "calendar" -> {
                val cal = Calendar.getInstance()
                val today = cal.get(Calendar.DAY_OF_MONTH)
                val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val first = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
                val cw = (w - d * 48) / 7
                val top = h * 0.55f
                for (day in 1..days) {
                    val i = first + day - 1
                    paint.color = if (live != null && day == today) p.signal else withAlpha(p.fg, 0.5f)
                    c.drawCircle(d * 24 + (i % 7 + 0.5f) * cw + ox, top + (i / 7) * cw * 0.8f + oy, d * (if (live != null && day == today) 6f else 3f), paint)
                }
                if (live != null) text(time, cx, h * 0.42f, d * 64, p.fg, fonts.sansMedium)
            }
            "terminal", "system" -> {
                c.drawColor(Look.mix(p.bg, 0xFF000000.toInt(), 0.4f))
                rect.set(d * 18, h * 0.3f, w - d * 18f, h * 0.7f)
                card(rect, "solid")
                paint.color = p.signal
                c.drawCircle(rect.left + d * 16, rect.top + d * 16, d * 4, paint)
                val lines = if (design == "system" && live != null) listOf("$ uptime", "  battery ${live.battery}%", "  ${live.weather}") else listOf("omarchy login: ", "Password: ", "$ _")
                lines.forEachIndexed { i, l -> text(l, rect.left + d * 16, rect.top + d * (52 + i * 24f), d * 14, if (i == lines.size - 1) p.fg else p.dim, fonts.mono, Paint.Align.LEFT) }
                if (live != null && design == "terminal") text(time, rect.right - d * 16, rect.bottom - d * 18, d * 22, p.fg, fonts.mono, Paint.Align.RIGHT)
            }
            "neon" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d * 3
                paint.color = p.signal
                paint.maskFilter = BlurMaskFilter(d * 10, BlurMaskFilter.Blur.SOLID)
                c.drawRoundRect(d * 20, h * 0.08f, w - d * 20f, h * 0.92f, d * 30, d * 30, paint)
                paint.maskFilter = null
                paint.style = Paint.Style.FILL
                if (live != null) {
                    paint.setShadowLayer(d * 14, 0f, 0f, p.signal)
                    text(time, cx, h * 0.5f, d * 80, p.fg, fonts.sansMedium)
                    paint.clearShadowLayer()
                }
                liveInfo(h * 0.5f + d * 60)
            }
            "aurora" -> {
                for (k in 0..2) {
                    val col = listOf(p.signal, p.danger, Look.mix(p.signal, p.fg, 0.5f))[k]
                    paint.shader = RadialGradient(w * (0.2f + k * 0.3f), h * (0.25f + k * 0.1f), w * 0.7f, withAlpha(col, 0.45f), 0, Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                }
                paint.shader = null
                if (live != null) text(time, cx, h * 0.5f, d * 76, p.fg, fonts.sans)
                liveInfo(h * 0.5f + d * 50)
            }
            "rain" -> {
                c.drawColor(Look.mix(p.bg, 0xFF000000.toInt(), 0.5f))
                val rnd = java.util.Random(7)
                val col = d * 14
                var x = 0f
                while (x < w) {
                    val len = 6 + rnd.nextInt(18)
                    val y0 = rnd.nextInt(h)
                    for (k in 0 until len) {
                        paint.color = withAlpha(p.signal, 0.15f + 0.85f * k / len)
                        text(((33 + rnd.nextInt(90)).toChar()).toString(), x, y0 + k * col, d * 12, paint.color, fonts.mono, Paint.Align.LEFT)
                    }
                    x += col
                }
                if (live != null) dotTime(cx, h * 0.5f, w * 0.7f, p.fg)
            }
            "constellation" -> {
                val rnd = java.util.Random(11)
                val stars = List(28) { (rnd.nextFloat() * w) to (rnd.nextFloat() * h * 0.8f + h * 0.1f) }
                paint.color = withAlpha(p.fg, 0.18f)
                paint.strokeWidth = d
                for (i in 1 until stars.size) if (i % 3 != 0) c.drawLine(stars[i - 1].first, stars[i - 1].second, stars[i].first, stars[i].second, paint)
                stars.forEachIndexed { i, (x, y) ->
                    paint.color = if (i % 5 == 0) p.signal else p.fg
                    c.drawCircle(x, y, d * (if (i % 5 == 0) 3.5f else 2f), paint)
                }
                if (live != null) text(time, cx, h * 0.5f, d * 64, p.fg, fonts.sans)
            }
            "dotmatrix" -> {
                // Бортик DOT-MATRIX: рамка по краю со скруглёнными внутренними углами.
                val bezel = d * 10
                paint.color = p.bg
                c.drawRect(0f, 0f, w.toFloat(), bezel, paint)
                c.drawRect(0f, h - bezel, w.toFloat(), h.toFloat(), paint)
                c.drawRect(0f, 0f, bezel, h.toFloat(), paint)
                c.drawRect(w - bezel, 0f, w.toFloat(), h.toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d
                paint.color = p.line
                c.drawRoundRect(bezel, bezel, w - bezel, h - bezel, d * 20, d * 20, paint)
                paint.style = Paint.Style.FILL
                // LED-«пилюля» пароля.
                rect.set(cx - d * 90, h * 0.74f, cx + d * 90, h * 0.74f + d * 36)
                paint.color = p.bg
                c.drawRoundRect(rect, d * 18, d * 18, paint)
                for (i in 0 until 8) {
                    paint.color = if (i < 4) p.fg else withAlpha(p.fg, 0.15f)
                    c.drawCircle(rect.left + d * 24 + i * d * 19, rect.centerY(), d * 3.5f, paint)
                }
                if (live != null) {
                    dotTime(cx, h * 0.42f, w * 0.72f)
                    text(date.uppercase(locale), cx, h * 0.42f + d * 70, d * 13, p.dim, fonts.mono)
                }
                liveInfo(h * 0.74f - d * 20)
            }
            else -> if (design == "card" || design.startsWith("style-")) {
                // Раскладка DOT-MATRIX в отделке стиля Customizer: карточка по центру.
                val material = when (design.removePrefix("style-")) {
                    "glass" -> "glass"; "fluent" -> "auto"; "paper" -> "paper"; "neumorph" -> "soft"
                    "brutal" -> "brutal"; "retro" -> "bevel"; "neon" -> "glow"; else -> "solid"
                }
                rect.set(d * 28, h * 0.32f, w - d * 28f, h * 0.68f)
                card(rect, material)
                if (live != null) {
                    if (design == "style-neon") paint.setShadowLayer(d * 12, 0f, 0f, p.signal)
                    text(time, cx, rect.centerY() + d * 10, d * 64, p.fg, fonts.sansMedium)
                    paint.clearShadowLayer()
                    text(date, cx, rect.centerY() + d * 44, d * 13, p.dim, fonts.sans)
                } else {
                    for (i in 0 until 6) {
                        paint.color = withAlpha(p.fg, if (i < 4) 0.7f else 0.2f)
                        c.drawCircle(cx - d * 50 + i * d * 20, rect.bottom - d * 36, d * 4, paint)
                    }
                }
                liveInfo(rect.bottom + d * 36)
            } else {
                // classic и всё неизвестное: размытая подложка и (в заставке) часы.
                if (live != null) {
                    text(time, cx, h * 0.45f, d * 76, p.fg, fonts.sans)
                    text(date, cx, h * 0.45f + d * 36, d * 15, p.dim, fonts.sans)
                }
                liveInfo(h * 0.45f + d * 70)
            }
        }
        paint.reset()
    }
}
