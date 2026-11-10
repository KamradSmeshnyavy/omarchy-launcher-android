package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Torch
import io.github.kamradsmeshnyavy.launcher.core.Weather
import io.github.kamradsmeshnyavy.launcher.ui.LineIcon
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.llp
import io.github.kamradsmeshnyavy.launcher.ui.rounded
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Ещё виджеты в духе Nothing OS — свой рисунок: точки, светодиоды, моно-подписи, цвета темы.
 * Каждый — в двух голосах (dots — цифры светодиодами 5×7, style — шрифтом стиля).
 */

private fun Context.launch(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/** Карточка-холст: подпись сверху, крупное число (точками или шрифтом), кольца и полосы точек. */
abstract class CanvasWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    protected val d = context.resources.displayMetrics.density

    /** Где было последнее касание (в px холста) — для виджетов с зонами. */
    protected var touchX = 0f
    protected var touchY = 0f

    init {
        body.addView(canvasView)
        @Suppress("ClickableViewAccessibility")
        canvasView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touchX = e.x
                touchY = e.y
            }
            false
        }
    }

    protected abstract fun draw(c: Canvas, w: Int, h: Int)

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    override fun refresh() = canvasView.invalidate()

    /** Моно-подпись заглавными (как подписи Nothing): слева сверху на [y]. */
    protected fun label(c: Canvas, text: String, x: Float = 0f, y: Float = d * 11, color: Int = Nihil.MUTED, size: Float = 11f, align: Paint.Align = Paint.Align.LEFT) {
        paint.typeface = fonts.mono
        paint.textSize = d * size
        paint.color = color
        paint.textAlign = align
        c.drawText(text.uppercase(), x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /**
     * Крупно [text] в прямоугольник (x, y, w, h): в голосе dots — светодиодами (если все знаки
     * есть в матрице 5×7), иначе шрифтом стиля. [center] — по центру по горизонтали.
     */
    protected fun big(c: Canvas, text: String, x: Float, y: Float, w: Float, h: Float, color: Int = Nihil.INK, center: Boolean = false) {
        if (dots && text.all { it.isDigit() || it in ":.%-−°+ " }) {
            val pitch = Dots.fit(text, w, h)
            val tw = Dots.columns(text) * pitch
            val ox = if (center) x + (w - tw) / 2 else x
            Dots.draw(c, text, ox, y + (h - Dots.ROWS * pitch) / 2, pitch, color, withAlpha(Nihil.INK, 0.07f), paint)
            return
        }
        paint.typeface = fonts.sansMedium
        paint.color = color
        paint.textSize = h * 0.92f
        val tw = paint.measureText(text)
        if (tw > w) paint.textSize *= w / tw
        val fm = paint.fontMetrics
        val base = y + (h - (fm.descent - fm.ascent)) / 2 - fm.ascent
        paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(text, if (center) x + w / 2 else x, base, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    /** Кольцо из [count] точек; горят первые [lit] (по часовой от верха). */
    protected fun ring(c: Canvas, cx: Float, cy: Float, r: Float, count: Int, lit: Float, on: Int = Nihil.INK, off: Int = withAlpha(Nihil.INK, 0.12f)) {
        val dot = (r * PI.toFloat() / count * 0.62f).coerceAtMost(d * 4)
        for (i in 0 until count) {
            val a = -PI / 2 + 2 * PI * i / count
            paint.color = if (i < lit) on else off
            c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), if (i < lit) dot else dot * 0.7f, paint)
        }
    }

    protected fun twoDigits(n: Long) = n.toString().padStart(2, '0')
}

// --- Время ------------------------------------------------------------------------------

/** Большие часы: часы над минутами во весь виджет, минуты приглушены. */
class BigClockWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    init {
        onTap { context.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val now = Calendar.getInstance()
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val hour = if (h24) now.get(Calendar.HOUR_OF_DAY) else (now.get(Calendar.HOUR) + 11) % 12 + 1
        val gap = h * 0.06f
        val half = (h - gap) / 2
        big(c, twoDigits(hour.toLong()), 0f, 0f, w.toFloat(), half, Nihil.INK, center = true)
        big(c, twoDigits(now.get(Calendar.MINUTE).toLong()), 0f, half + gap, w.toFloat(), half, withAlpha(Nihil.INK, 0.5f), center = true)
    }
}

/** Дата крупно: день недели, число во всю высоту, месяц. */
class BigDateWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    init {
        onTap { context.launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) }
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val now = Calendar.getInstance()
        val locale = Locale.getDefault()
        label(c, SimpleDateFormat("EEEE", locale).format(now.time), color = Nihil.SIGNAL)
        label(c, SimpleDateFormat("LLLL", locale).format(now.time), y = h - d * 2)
        big(c, now.get(Calendar.DAY_OF_MONTH).toString(), 0f, d * 18, w.toFloat(), h - d * 38, Nihil.INK, center = w > h * 1.4f)
    }
}

/** Секундомер: нажатие — пуск/пауза; на паузе нажатие в правой нижней четверти — сброс. */
class StopwatchWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private val tick = object : Runnable {
        override fun run() {
            canvasView.invalidate()
            if (running) postDelayed(this, 100)
        }
    }
    private val running get() = item.options.optLong("start", 0L) > 0L
    private fun elapsed(): Long = item.options.optLong("acc", 0L) +
        if (running) System.currentTimeMillis() - item.options.optLong("start") else 0L

    init {
        onTap {
            val o = item.options
            when {
                running -> o.put("acc", elapsed()).put("start", 0L)
                o.optLong("acc", 0L) > 0L && touchX > canvasView.width * 0.6f && touchY > canvasView.height * 0.5f -> o.put("acc", 0L)
                else -> o.put("start", System.currentTimeMillis())
            }
            Desk.saveQuiet(context)
            removeCallbacks(tick)
            post(tick)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val ms = elapsed()
        val min = ms / 60_000
        val sec = ms / 1000 % 60
        label(c, context.getString(R.string.w_stopwatch), color = if (running) Nihil.SIGNAL else Nihil.MUTED)
        big(c, "${twoDigits(min)}:${twoDigits(sec)}", 0f, d * 18, w.toFloat(), h - d * 36, Nihil.INK)
        label(c, ".${ms / 100 % 10}", y = h - d * 2, color = Nihil.DIM)
        if (!running && ms > 0) label(c, context.getString(R.string.w_reset), x = w.toFloat(), y = h - d * 2, align = Paint.Align.RIGHT)
    }
}

/**
 * Таймер: кольцо из 60 точек гаснет по мере хода. Нажатие — запустить (и системный таймер
 * Часов, чтобы прозвенел), ещё раз — остановить здесь и открыть таймеры Часов.
 */
class TimerWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private val tick = object : Runnable {
        override fun run() {
            canvasView.invalidate()
            if (left() > 0) postDelayed(this, 500) else if (item.options.optLong("end", 0L) > 0L) {
                item.options.put("end", 0L)
                Desk.saveQuiet(context)
            }
        }
    }
    private val minutes get() = item.options.optDouble("minutes", 5.0).toInt().coerceIn(1, 600)
    private fun left(): Long = (item.options.optLong("end", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    init {
        onTap {
            if (left() > 0) {
                item.options.put("end", 0L)
                context.launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))
            } else {
                item.options.put("end", System.currentTimeMillis() + minutes * 60_000L)
                context.launch(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, context.getString(R.string.w_timer)),
                )
            }
            Desk.saveQuiet(context)
            removeCallbacks(tick)
            post(tick)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val left = left()
        val total = minutes * 60_000L
        val r = min(w, h) / 2f - d * 6
        val cx = w / 2f
        val cy = h / 2f
        ring(c, cx, cy, r, 60, if (left > 0) 60f * left / total else 60f, if (left > 0) Nihil.SIGNAL else Nihil.INK)
        val text = if (left > 0) "${twoDigits(left / 60_000)}:${twoDigits(left / 1000 % 60)}" else "$minutes:00"
        big(c, text, cx - r * 0.62f, cy - r * 0.3f, r * 1.24f, r * 0.6f, Nihil.INK, center = true)
        label(c, context.getString(if (left > 0) R.string.w_timer_stop else R.string.w_timer), cx, cy + r * 0.62f, size = 10f, align = Paint.Align.CENTER)
    }
}

// --- Погода и небо ---------------------------------------------------------------------

/** Восход и закат: дуга дня, солнце — точка на ней сейчас; ночью — приглушено. */
class SunWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private var now: Weather.Now? = Weather.cached(context)

    init {
        onTap { context.launch(Weather.intent(context)) }
    }

    override fun onWeather(now: Weather.Now?) {
        if (now != null) this.now = now
        canvasView.invalidate()
    }

    private fun minutes(iso: String): Int? = iso.substringAfter('T', "").split(':').takeIf { it.size >= 2 }
        ?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val n = now
        val rise = n?.sunrise?.let(::minutes)
        val set = n?.sunset?.let(::minutes)
        label(c, context.getString(R.string.w_sun))
        if (rise == null || set == null) {
            label(c, context.getString(R.string.w_no_weather), y = h / 2f, size = 10f)
            return
        }
        val cal = Calendar.getInstance()
        val t = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val f = ((t - rise).toFloat() / (set - rise)).coerceIn(-0.05f, 1.05f)
        // Дуга — полуэллипс из точек над линией горизонта.
        val baseY = h - d * 26
        val rx = w / 2f - d * 10
        val ry = (baseY - d * 24).coerceAtLeast(d * 10)
        val cx = w / 2f
        val count = 25
        for (i in 0..count) {
            val a = PI - PI * i / count
            val passed = i.toFloat() / count <= f
            paint.color = if (passed) withAlpha(Nihil.INK, 0.6f) else withAlpha(Nihil.INK, 0.14f)
            c.drawCircle(cx + rx * cos(a).toFloat(), baseY - ry * sin(a).toFloat(), d * 1.8f, paint)
        }
        paint.color = withAlpha(Nihil.INK, 0.2f)
        c.drawRect(d * 4, baseY + d * 4, w - d * 4, baseY + d * 5, paint)
        if (f in 0f..1f) {
            val a = PI - PI * f
            paint.color = Nihil.SIGNAL
            c.drawCircle(cx + rx * cos(a).toFloat(), baseY - ry * sin(a).toFloat(), d * 6, paint)
        }
        fun hhmm(m: Int) = "${m / 60}:${twoDigits((m % 60).toLong())}"
        label(c, "↑ " + hhmm(rise), y = h - d * 2, color = Nihil.INK)
        label(c, hhmm(set) + " ↓", x = w.toFloat(), y = h - d * 2, color = Nihil.INK, align = Paint.Align.RIGHT)
    }
}

/** Качество воздуха: индекс крупно, уровень словами и полоса из 6 точек-ступеней. */
class AirWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private var now: Weather.Now? = Weather.cached(context)

    init {
        onTap { context.launch(Weather.intent(context)) }
    }

    override fun onWeather(now: Weather.Now?) {
        if (now != null) this.now = now
        canvasView.invalidate()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val us = item.options.optString("scale", "eu") == "us"
        val value = now?.let { if (us) it.aqiUs else it.aqiEu } ?: -1
        label(c, context.getString(R.string.w_air) + if (us) " · US AQI" else " · EAQI")
        if (value < 0) {
            label(c, context.getString(R.string.w_no_weather), y = h / 2f, size = 10f)
            return
        }
        // Ступени: EAQI 0–20–40–60–80–100+, US AQI 0–50–100–150–200–300+.
        val edges = if (us) intArrayOf(50, 100, 150, 200, 300) else intArrayOf(20, 40, 60, 80, 100)
        val level = edges.indexOfFirst { value <= it }.let { if (it < 0) 5 else it }
        val names = context.resources.getStringArray(R.array.w_air_levels)
        val wide = w > h * 1.6f
        val numberW = if (wide) w * 0.4f else w.toFloat()
        big(c, value.toString(), 0f, d * 18, numberW, (h - d * 44).coerceAtLeast(d * 12), if (level >= 3) Nihil.SIGNAL else Nihil.INK)
        val step = (w - d * 4) / 6f
        for (i in 0 until 6) {
            paint.color = if (i <= level) (if (level >= 3) Nihil.SIGNAL else Nihil.INK) else withAlpha(Nihil.INK, 0.12f)
            c.drawCircle(d * 2 + step * (i + 0.5f), h - d * 22, d * 3.4f, paint)
        }
        label(c, names.getOrElse(level) { "" }, y = h - d * 2, color = Nihil.INK)
    }
}

/** Фаза Луны: диск из точек, освещённая часть горит; название фазы и освещённость. */
class MoonWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    /** Возраст Луны 0..1 (0 — новолуние, 0.5 — полнолуние) по средней синодической длине. */
    private fun age(ms: Long): Double {
        val days = (ms - 947_182_440_000L) / 86_400_000.0 // 2000-01-06 18:14 UTC — новолуние
        val f = days / 29.530588853 % 1.0
        return if (f < 0) f + 1 else f
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val a = age(System.currentTimeMillis())
        val lit = (1 - cos(2 * PI * a)) / 2
        val names = context.resources.getStringArray(R.array.w_moon_phases)
        val phase = ((a * 8 + 0.5).toInt() % 8)
        val wide = w > h * 1.4f
        val size = if (wide) h - d * 8f else min(w.toFloat(), h - d * 40)
        val r = size / 2
        val cx = if (wide) r + d * 2 else w / 2f
        val cy = if (wide) h / 2f else d * 2 + r
        // Терминатор: x² … освещено, если точка по нужную сторону эллипса k·√(1−y²).
        val n = 15
        val step = size / n
        val k = cos(2 * PI * a).toFloat()
        for (iy in 0 until n) for (ix in 0 until n) {
            val x = (ix + 0.5f) / n * 2 - 1
            val y = (iy + 0.5f) / n * 2 - 1
            if (x * x + y * y > 1f) continue
            val edge = k * sqrt(1 - y * y)
            val on = if (a < 0.5) x > edge else x < -edge
            paint.color = if (on) Nihil.INK else withAlpha(Nihil.INK, 0.1f)
            c.drawCircle(cx - r + (ix + 0.5f) * step, cy - r + (iy + 0.5f) * step, step * (if (on) 0.4f else 0.25f), paint)
        }
        val tx = if (wide) cx + r + d * 14 else 0f
        val ty = if (wide) h / 2f else h - d * 18
        label(c, names.getOrElse(phase) { "" }, x = tx, y = ty, color = Nihil.INK)
        label(c, "${(lit * 100).toInt()}%", x = tx, y = ty + d * 15)
    }
}

// --- Инструменты --------------------------------------------------------------------------

/** Диктофон: большая кнопка записи (кольцо точек, красная середина) — системный диктофон. */
class RecorderWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    init {
        onTap {
            context.launch(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)) ||
                context.packageManager.getLaunchIntentForPackage("com.google.android.apps.recorder")?.let { context.launch(it) } == true
        }
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val wide = w > h * 1.5f
        val r = (if (wide) h / 2f else min(w, h) / 2f - d * 8) - d * 4
        val cx = if (wide) r + d * 6 else w / 2f
        val cy = h / 2f
        ring(c, cx, cy, r, 32, 32f, withAlpha(Nihil.INK, 0.35f))
        paint.color = Nihil.SIGNAL
        c.drawCircle(cx, cy, r * 0.42f, paint)
        if (wide) label(c, context.getString(R.string.w_recorder), x = cx + r + d * 10, y = cy + d * 4, color = Nihil.INK, size = if (w - cx - r < d * 80) 10f else 12f)
    }
}

/** Камера: объектив из колец точек; нажатие — съёмка. */
class CameraWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    init {
        onTap { context.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val wide = w > h * 1.5f
        val r = (if (wide) h / 2f else min(w, h) / 2f) - d * 6
        val cx = if (wide) r + d * 6 else w / 2f
        val cy = h / 2f
        ring(c, cx, cy, r, 36, 36f, withAlpha(Nihil.INK, 0.3f))
        ring(c, cx, cy, r * 0.66f, 24, 24f, Nihil.INK)
        paint.color = Nihil.INK
        c.drawCircle(cx, cy, r * 0.3f, paint)
        paint.color = Nihil.VOID
        c.drawCircle(cx - r * 0.1f, cy - r * 0.1f, r * 0.08f, paint)
        if (wide) label(c, context.getString(R.string.w_camera), x = cx + r + d * 10, y = cy + d * 4, color = Nihil.INK, size = if (w - cx - r < d * 80) 10f else 12f)
    }
}

/** Фонарик одной плиткой: включён — горит заливкой. */
class TorchWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private val listener: (Boolean) -> Unit = { canvasView.invalidate() }
    private val icon = LineIcon(LineIcon.Shape.TORCH, Nihil.INK)

    init {
        onTap {
            Torch.toggle()
            canvasView.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Torch.listen(listener)
    }

    override fun onDetachedFromWindow() {
        Torch.unlisten(listener)
        super.onDetachedFromWindow()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val on = Torch.on
        val r = min(w, h) / 2f - d * 2
        paint.color = if (on) Nihil.INK else withAlpha(Nihil.INK, 0.08f)
        c.drawCircle(w / 2f, h / 2f, r, paint)
        val s = (r * 0.9f).toInt()
        icon.color = if (on) Nihil.VOID else Nihil.INK
        icon.setBounds((w / 2f - s / 2f).toInt(), (h / 2f - s / 2f).toInt(), (w / 2f + s / 2f).toInt(), (h / 2f + s / 2f).toInt())
        icon.draw(c)
    }
}

/** Режим звонка: звук → вибрация → без звука (если есть доступ к «Не беспокоить») → звук. */
class RingModeWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = canvasView.invalidate()
    }

    init {
        onTap {
            val notes = context.getSystemService(NotificationManager::class.java)
            val next = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE ->
                    if (notes.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            runCatching { audio.ringerMode = next }
            canvasView.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(receiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val mode = audio.ringerMode
        label(c, context.getString(R.string.w_ring))
        val name = context.getString(
            when (mode) {
                AudioManager.RINGER_MODE_VIBRATE -> R.string.w_ring_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.string.w_ring_silent
                else -> R.string.w_ring_sound
            },
        )
        // Три точки-состояния и активное — крупно.
        val step = d * 14
        for (i in 0 until 3) {
            val on = i == when (mode) {
                AudioManager.RINGER_MODE_NORMAL -> 0
                AudioManager.RINGER_MODE_VIBRATE -> 1
                else -> 2
            }
            paint.color = if (on) (if (mode == AudioManager.RINGER_MODE_SILENT) Nihil.SIGNAL else Nihil.INK) else withAlpha(Nihil.INK, 0.14f)
            c.drawCircle(d * 5 + step * i, h - d * 8, d * (if (on) 4.5f else 3f), paint)
        }
        paint.typeface = fonts.sansMedium
        paint.color = Nihil.INK
        paint.textSize = min(h * 0.32f, d * 26)
        c.drawText(name, 0f, h / 2f + paint.textSize * 0.3f, paint)
    }
}

/** Память: занято из всего — числом и полосой из точек. */
class StorageWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    init {
        onTap { context.launch(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
    }

    override fun draw(c: Canvas, w: Int, h: Int) {
        val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() ?: return
        val total = fs.totalBytes.toDouble()
        val used = total - fs.availableBytes
        val f = (used / total).toFloat()
        val gb = 1_000_000_000.0
        label(c, context.getString(R.string.w_storage))
        label(c, "${(total / gb).toInt()} GB", x = w.toFloat(), align = Paint.Align.RIGHT)
        big(c, "${(f * 100).toInt()}%", 0f, d * 18, w.toFloat(), (h - d * 44).coerceAtLeast(d * 10), if (f > 0.9f) Nihil.SIGNAL else Nihil.INK)
        Dots.bar(c, 0f, h - d * 12, w.toFloat(), (w / (d * 9)).toInt().coerceIn(8, 40), f, if (f > 0.9f) Nihil.SIGNAL else Nihil.INK, withAlpha(Nihil.INK, 0.12f), paint)
    }
}

/** Трафик за месяц: мобильный и Wi-Fi (нужен доступ к статистике использования). */
class DataUsageWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private var cache: Pair<Long, Long>? = null
    private var at = 0L

    init {
        onTap { context.launch(Intent(if (allowed()) Settings.ACTION_DATA_USAGE_SETTINGS else Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun usage(): Pair<Long, Long>? {
        if (System.currentTimeMillis() - at < 5 * 60_000L) cache?.let { return it }
        val stats = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val end = System.currentTimeMillis()
        fun total(type: Int): Long = runCatching {
            val b: NetworkStats.Bucket = stats.querySummaryForDevice(type, null, start, end)
            b.rxBytes + b.txBytes
        }.getOrDefault(0L)
        return (total(ConnectivityManager.TYPE_MOBILE) to total(ConnectivityManager.TYPE_WIFI)).also {
            cache = it
            at = end
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1f", bytes / 1e9)
        else -> (bytes / 1_000_000L).toString()
    }

    private fun unit(bytes: Long) = if (bytes >= 1_000_000_000L) "GB" else "MB"

    override fun draw(c: Canvas, w: Int, h: Int) {
        label(c, context.getString(R.string.w_data))
        if (!allowed()) {
            label(c, context.getString(R.string.w_tap_allow), y = h / 2f, color = Nihil.INK, size = 10f)
            return
        }
        val (mobile, wifi) = usage() ?: return
        val wide = w > h * 1.6f
        if (wide) {
            big(c, human(mobile), 0f, d * 16, w * 0.4f, h - d * 24, Nihil.INK)
            label(c, unit(mobile) + " · " + context.getString(R.string.w_data_mobile), x = w * 0.45f, y = h / 2f, color = Nihil.INK)
            label(c, human(wifi) + " " + unit(wifi) + " · Wi-Fi", x = w * 0.45f, y = h / 2f + d * 16)
        } else {
            big(c, human(mobile), 0f, d * 18, w.toFloat(), h - d * 56, Nihil.INK)
            label(c, unit(mobile) + " · " + context.getString(R.string.w_data_mobile), y = h - d * 18, color = Nihil.INK)
            label(c, human(wifi) + " " + unit(wifi) + " · Wi-Fi", y = h - d * 2)
        }
    }
}

// --- Люди и поиск ---------------------------------------------------------------------------

/** Избранные контакты (со звёздочкой): инициалы в кольцах точек; нажатие — карточка контакта. */
class ContactsWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val hint = TextView(context).apply {
        typeface = fonts.mono
        textSize = 11f
        setTextColor(Nihil.MUTED)
    }

    init {
        body.addView(row, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(hint, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL))
        onTap {
            if (!granted()) host.ask(Manifest.permission.READ_CONTACTS) { refresh() } else context.launch(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        }
        refresh()
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private class Person(val name: String, val uri: android.net.Uri)

    private fun starred(): List<Person> = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.STARRED}=1", null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cur ->
            val out = ArrayList<Person>()
            while (cur.moveToNext() && out.size < 8) {
                out += Person(cur.getString(2).orEmpty(), ContactsContract.Contacts.getLookupUri(cur.getLong(0), cur.getString(1)))
            }
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    override fun refresh() {
        row.removeAllViews()
        if (!granted()) {
            hint.text = context.getString(R.string.w_contacts_allow)
            hint.visibility = VISIBLE
            return
        }
        val people = starred().take(item.w * item.h.coerceAtMost(2))
        hint.text = context.getString(R.string.w_contacts_none)
        hint.visibility = if (people.isEmpty()) VISIBLE else GONE
        people.forEach { p ->
            val initials = p.name.split(' ', '-').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val face = TextView(context).apply {
                    text = initials
                    gravity = Gravity.CENTER
                    typeface = if (dots) fonts.dot else fonts.sansMedium
                    textSize = 18f
                    setTextColor(Nihil.INK)
                    background = rounded(io.github.kamradsmeshnyavy.launcher.ui.withAlpha(Nihil.INK, 0.06f), context.dp(40f), context.dp(1), Nihil.LINE)
                }
                addView(face, llp(context.dp(52), context.dp(52)))
                addView(TextView(context).apply {
                    text = p.name.substringBefore(' ')
                    typeface = fonts.mono
                    textSize = 10f
                    maxLines = 1
                    setTextColor(Nihil.MUTED)
                }, llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
                setOnClickListener { context.launch(Intent(Intent.ACTION_VIEW, p.uri)) }
                isLongClickable = true
                setOnLongClickListener { this@ContactsWidget.performLongClick() }
            }
            row.addView(cell, llp(0, WRAP, 1f))
        }
    }
}

/** Строка поиска: нажатие открывает поиск лаунчера (ящик с клавиатурой). */
class SearchWidget(context: Context, item: Desk.Item, host: DeskHost) : CanvasWidget(context, item, host) {
    private val icon = LineIcon(LineIcon.Shape.SEARCH, Nihil.MUTED)

    init {
        onTap { host.openSearch() }
    }

    override fun stackHeight(w: Int): Int = context.dp(64)

    override fun draw(c: Canvas, w: Int, h: Int) {
        val s = (d * 22).toInt()
        val cy = h / 2f
        icon.setBounds(0, (cy - s / 2f).toInt(), s, (cy + s / 2f).toInt())
        icon.draw(c)
        paint.typeface = fonts.sans
        paint.textSize = d * 16
        paint.color = Nihil.MUTED
        c.drawText(context.getString(R.string.w_search_hint), s + d * 14, cy + paint.textSize * 0.35f, paint)
        // Справа — три точки, как курсор матрицы.
        for (i in 0 until 3) {
            paint.color = if (i == 2) Nihil.SIGNAL else withAlpha(Nihil.INK, 0.2f)
            c.drawCircle(w - d * 6 - i * d * 10, cy, d * 2.6f, paint)
        }
    }
}
