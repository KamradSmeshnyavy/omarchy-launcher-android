package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.AlarmClock
import android.text.format.DateFormat
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun is24(context: Context): Boolean = (Look.now.values["clock24"] as? Boolean) ?: DateFormat.is24HourFormat(context)

private fun openClock(context: Context) {
    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Время крупно: светодиодами 5 × 7 или шрифтом стиля; под ним — день и дата (если есть место). */
class DotClockWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { openClock(context) }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val now = Date()
        val time = SimpleDateFormat(if (is24(context)) "HH:mm" else "h:mm", Locale.ROOT).format(now)
        val locale = Locale.getDefault()
        val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale).format(now)
        val small = h < w * 0.45f
        val dateH = if (small) 0f else h * 0.22f
        if (dots) {
            val pitch = Dots.fit(time, w.toFloat(), h - dateH)
            val tw = Dots.columns(time) * pitch
            Dots.draw(c, time, (w - tw) / 2, (h - dateH - Dots.ROWS * pitch) / 2, pitch, Nihil.INK, withAlpha(Nihil.INK, 0.1f), paint)
        } else {
            paint.typeface = fonts.sansMedium
            paint.color = Nihil.INK
            paint.textSize = min((h - dateH) * 0.8f, w / (time.length * 0.62f))
            paint.textAlign = Paint.Align.CENTER
            c.drawText(time, w / 2f, (h - dateH) / 2 + paint.textSize * 0.36f, paint)
        }
        if (!small) {
            paint.typeface = fonts.mono
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dateH * 0.45f
            paint.color = Nihil.DIM
            c.drawText(date.uppercase(locale), w / 2f, h - dateH * 0.3f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }
}

/** Циферблат из точек: 60 минутных точек, часовые крупнее, стрелки — пунктиром точек. */
class AnalogWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { openClock(context) }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.92f
        val d = resources.displayMetrics.density
        for (i in 0 until 60) {
            val a = i * PI / 30 - PI / 2
            val hour = i % 5 == 0
            paint.color = if (hour) Nihil.INK else withAlpha(Nihil.INK, 0.25f)
            c.drawCircle(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), d * (if (hour) 2.6f else 1.3f), paint)
        }
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.MINUTE)
        val hr = cal.get(Calendar.HOUR) + m / 60f
        fun hand(angle: Double, length: Float, color: Int, size: Float) {
            val steps = (length / (d * 6)).toInt().coerceAtLeast(3)
            paint.color = color
            for (s in 1..steps) {
                val l = length * s / steps
                c.drawCircle(cx + (cos(angle) * l).toFloat(), cy + (sin(angle) * l).toFloat(), size, paint)
            }
        }
        hand(hr * PI / 6 - PI / 2, r * 0.5f, Nihil.INK, d * 2.6f)
        hand(m * PI / 30 - PI / 2, r * 0.78f, Nihil.INK, d * 2f)
        paint.color = Nihil.SIGNAL
        c.drawCircle(cx, cy, d * 3.4f, paint)
    }
}

/** Мировое время: города из опции zones (через запятую, IANA), по строке на город. */
class WorldClockWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { openClock(context) }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun zones(): List<TimeZone> = item.options.optString("zones", "Europe/London,America/New_York,Asia/Tokyo")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { TimeZone.getTimeZone(it) }.take(4)

    private fun draw(c: Canvas, w: Int, h: Int) {
        val list = zones()
        if (list.isEmpty()) return
        val rowH = h.toFloat() / list.size
        val fmt = SimpleDateFormat(if (is24(context)) "HH:mm" else "h:mm", Locale.ROOT)
        list.forEachIndexed { i, zone ->
            fmt.timeZone = zone
            val time = fmt.format(Date())
            val city = zone.id.substringAfterLast('/').replace('_', ' ')
            val y = rowH * i
            paint.typeface = fonts.sans
            paint.color = Nihil.DIM
            paint.textSize = min(rowH * 0.32f, resources.displayMetrics.density * 16)
            c.drawText(city, 0f, y + rowH / 2 + paint.textSize * 0.35f, paint)
            if (dots) {
                val pitch = min(rowH * 0.6f / Dots.ROWS, w * 0.5f / Dots.columns(time))
                val tw = Dots.columns(time) * pitch
                Dots.draw(c, time, w - tw, y + (rowH - Dots.ROWS * pitch) / 2, pitch, Nihil.INK, 0, paint)
            } else {
                paint.typeface = fonts.mono
                paint.color = Nihil.INK
                paint.textSize = rowH * 0.42f
                paint.textAlign = Paint.Align.RIGHT
                c.drawText(time, w.toFloat(), y + rowH / 2 + paint.textSize * 0.35f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }
}

/** Месяц точками: дни — точки, сегодня — горит акцентом, прошедшие — тусклые. Нажатие — календарь. */
class MonthWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val locale = Locale.getDefault()
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_MONTH)
        val title = SimpleDateFormat("LLLL yyyy", locale).format(cal.time).replaceFirstChar { it.titlecase(locale) }
            .let { full ->
                // Узкий виджет: месяц без года, а если и так не влезает — коротко.
                paint.textSize = h * 0.18f * 0.7f
                when {
                    paint.measureText(full) <= w -> full
                    paint.measureText(full.substringBefore(' ')) <= w -> full.substringBefore(' ')
                    else -> SimpleDateFormat("LLL", locale).format(cal.time).replaceFirstChar { it.titlecase(locale) }
                }
            }
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val first = (cal.get(Calendar.DAY_OF_WEEK) - cal.firstDayOfWeek + 7) % 7
        val head = h * 0.18f
        paint.typeface = if (dots) fonts.dot else fonts.sansMedium
        paint.color = Nihil.INK
        paint.textSize = head * 0.7f
        c.drawText(title, 0f, head * 0.75f, paint)
        val rows = (first + days + 6) / 7
        val cw = w / 7f
        val ch = (h - head * 1.2f) / rows
        val d = resources.displayMetrics.density
        for (day in 1..days) {
            val i = first + day - 1
            val x = (i % 7 + 0.5f) * cw
            val y = head * 1.2f + (i / 7 + 0.5f) * ch
            if (dots || ch < d * 18) {
                paint.color = when {
                    day == today -> Nihil.SIGNAL
                    day < today -> withAlpha(Nihil.INK, 0.25f)
                    else -> Nihil.INK
                }
                c.drawCircle(x, y, min(cw, ch) * (if (day == today) 0.24f else 0.14f), paint)
            } else {
                paint.typeface = fonts.mono
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = min(ch * 0.5f, d * 14)
                if (day == today) {
                    paint.color = Nihil.SIGNAL
                    c.drawCircle(x, y, min(cw, ch) * 0.42f, paint)
                    paint.color = Nihil.ON_SIGNAL
                } else {
                    paint.color = if (day < today) Nihil.MUTED else Nihil.INK
                }
                c.drawText(day.toString(), x, y + paint.textSize * 0.35f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }
}

/** Ближайший будильник крупно; без будильника — «нет». Нажатие — будильники. */
class AlarmWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { openClock(context) }
    }

    override fun onAlarm(text: String?) = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val time = next?.let { SimpleDateFormat(if (is24(context)) "HH:mm" else "h:mm", Locale.ROOT).format(Date(it.triggerTime)) } ?: "--:--"
        paint.typeface = fonts.mono
        paint.color = Nihil.MUTED
        paint.textSize = h * 0.16f
        c.drawText(context.getString(R.string.w_alarm).uppercase(), 0f, paint.textSize, paint)
        val top = h * 0.25f
        if (dots) {
            val pitch = Dots.fit(time.replace('-', '-'), w.toFloat(), h - top)
            Dots.draw(c, time, 0f, top + (h - top - Dots.ROWS * pitch) / 2, pitch, if (next == null) Nihil.MUTED else Nihil.INK, withAlpha(Nihil.INK, 0.08f), paint)
        } else {
            paint.typeface = fonts.sansMedium
            paint.color = if (next == null) Nihil.MUTED else Nihil.INK
            paint.textSize = min((h - top) * 0.7f, w / (time.length * 0.62f))
            c.drawText(time, 0f, top + (h - top) / 2 + paint.textSize * 0.36f, paint)
        }
    }
}
