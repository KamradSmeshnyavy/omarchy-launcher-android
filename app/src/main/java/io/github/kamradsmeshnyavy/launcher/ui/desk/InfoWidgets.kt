package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.Weather
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.WeatherIcon
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Погода: сейчас (значок, температура, место) и, если есть место, три дня вперёд. */
class WeatherWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var now: Weather.Now? = Weather.cached(context)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { context.startActivity(Weather.intent(context)) }
    }

    override fun onWeather(now: Weather.Now?) {
        if (now != null) this.now = now
        canvasView.invalidate()
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val n = now
        val d = resources.displayMetrics.density
        val units = Look.prefs(context).getString(Weather.UNITS, "auto") ?: "auto"
        if (n == null) {
            paint.typeface = fonts.mono
            paint.color = Nihil.MUTED
            paint.textSize = d * 12
            c.drawText(context.getString(R.string.cz_weather_failed), 0f, h / 2f, paint)
            return
        }
        val wide = w > h * 1.4f
        val nowH = if (wide || n.days.isEmpty()) h.toFloat() else h * 0.58f
        val nowW = if (wide && n.days.isNotEmpty()) w * 0.45f else w.toFloat()
        // Сейчас.
        val icon = min(nowH * 0.42f, nowW * 0.35f).toInt()
        WeatherIcon(n.kind, n.day, Nihil.INK).apply { setBounds(0, 0, icon, icon) }.draw(c)
        val t = Weather.degrees(n.temperature, units, n.country) + "°"
        if (dots) {
            val pitch = min((nowH - icon * 0.2f) * 0.55f / Dots.ROWS, (nowW - icon - d * 8) / Dots.columns(t))
            Dots.draw(c, t, icon + d * 10, (nowH * 0.62f - Dots.ROWS * pitch) / 2, pitch, Nihil.INK, 0, paint)
        } else {
            paint.typeface = fonts.sansMedium
            paint.color = Nihil.INK
            paint.textSize = nowH * 0.4f
            c.drawText(t, icon + d * 10, nowH * 0.4f, paint)
        }
        paint.typeface = fonts.mono
        paint.color = Nihil.DIM
        paint.textSize = min(d * 12, nowH * 0.14f)
        c.drawText(n.place, 0f, nowH * 0.9f, paint)
        // Три дня.
        val days = n.days.drop(1).take(3)
        if (days.isEmpty()) return
        val locale = Locale.getDefault()
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val name = SimpleDateFormat("EEE", locale)
        val x0 = if (wide) nowW + d * 8 else 0f
        val y0 = if (wide) 0f else nowH + d * 4
        val cw = (w - x0) / days.size
        val ch = h - y0
        days.forEachIndexed { i, day ->
            val x = x0 + i * cw
            paint.color = Nihil.MUTED
            paint.textSize = min(d * 11, ch * 0.2f)
            paint.textAlign = Paint.Align.CENTER
            val label = runCatching { name.format(parse.parse(day.date)!!) }.getOrDefault("").uppercase(locale)
            c.drawText(label, x + cw / 2, y0 + paint.textSize, paint)
            val s = min(cw * 0.42f, ch * 0.38f).toInt()
            val left = (x + (cw - s) / 2).toInt()
            val top = (y0 + paint.textSize * 1.4f).toInt()
            WeatherIcon(day.kind, true, Nihil.DIM).apply { setBounds(left, top, left + s, top + s) }.draw(c)
            paint.color = Nihil.INK
            c.drawText(Weather.degrees(day.max, units, n.country) + "° " + Weather.degrees(day.min, units, n.country) + "°", x + cw / 2, h - d * 2, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
}

/** Батарея кольцом из точек (или полосой LED), процент в центре; при зарядке — акцентом. */
class BatteryWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0
    private var charging = false
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { context.startActivity(android.content.Intent(android.content.Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onBattery(level: Int, charging: Boolean) {
        this.level = level
        this.charging = charging
        canvasView.invalidate()
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val on = when {
            charging -> Nihil.SIGNAL
            level <= 15 -> Nihil.DANGER
            else -> Nihil.INK
        }
        val text = "$level%"
        if (w > h * 1.6f) {
            // Полосой: LED-шкала и цифры.
            paint.typeface = fonts.mono
            paint.color = Nihil.MUTED
            paint.textSize = h * 0.2f
            c.drawText(context.getString(if (charging) R.string.battery_charging else R.string.w_battery).uppercase(), 0f, paint.textSize, paint)
            Dots.bar(c, 0f, h * 0.72f, w * 0.62f, 12, level / 100f, on, withAlpha(Nihil.INK, 0.12f), paint)
            val pitch = min(h * 0.45f / Dots.ROWS, w * 0.3f / Dots.columns(text))
            Dots.draw(c, text, w - Dots.columns(text) * pitch, h * 0.72f - Dots.ROWS * pitch / 2, pitch, Nihil.INK, 0, paint)
            return
        }
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.86f
        val count = 30
        val lit = (level / 100f * count + 0.5f).toInt()
        for (i in 0 until count) {
            val a = i * 2 * PI / count - PI / 2
            paint.color = if (i < lit) on else withAlpha(Nihil.INK, 0.12f)
            c.drawCircle(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), d * 3f, paint)
        }
        val pitch = min(r * 1.1f / Dots.columns(text), r * 0.7f / Dots.ROWS)
        if (dots) {
            Dots.draw(c, text, cx - Dots.columns(text) * pitch / 2, cy - Dots.ROWS * pitch / 2, pitch, Nihil.INK, 0, paint)
        } else {
            paint.typeface = fonts.sansMedium
            paint.color = Nihil.INK
            paint.textSize = r * 0.5f
            paint.textAlign = Paint.Align.CENTER
            c.drawText(text, cx, cy + paint.textSize * 0.36f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
}

/**
 * Заряд Bluetooth-устройств (наушники, часы): подключённые сопряжённые устройства и их
 * батарея, если они её сообщают. Нужно разрешение «Устройства поблизости» — по нажатию.
 */
class BluetoothBatteryWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap {
            if (!allowed()) host.ask(Manifest.permission.BLUETOOTH_CONNECT) { canvasView.invalidate() }
            else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun refresh() = canvasView.invalidate()

    private fun allowed() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun devices(): List<Pair<String, Int>> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.filter { connected(it) }.map { dev ->
                (dev.alias ?: dev.name ?: "?") to level(dev)
            }
        }.getOrDefault(emptyList())
    }

    // Эти два — скрытый API Android; у многих телефонов работают, иначе — без процента.
    private fun connected(dev: BluetoothDevice): Boolean =
        runCatching { dev.javaClass.getMethod("isConnected").invoke(dev) as Boolean }.getOrDefault(false)

    private fun level(dev: BluetoothDevice): Int =
        runCatching { dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as Int }.getOrDefault(-1)

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.MUTED
        c.drawText("BLUETOOTH", 0f, paint.textSize, paint)
        if (!allowed()) {
            paint.color = Nihil.DIM
            paint.textSize = d * 13
            c.drawText(context.getString(R.string.w_tap_to_allow), 0f, h / 2f, paint)
            return
        }
        val list = devices()
        if (list.isEmpty()) {
            paint.color = Nihil.DIM
            paint.textSize = d * 13
            c.drawText(context.getString(R.string.w_bt_none), 0f, h / 2f, paint)
            return
        }
        val rowH = (h - d * 18) / list.size.coerceAtMost(3)
        list.take(3).forEachIndexed { i, (name, lvl) ->
            val y = d * 18 + rowH * i + rowH / 2
            paint.typeface = fonts.sans
            paint.color = Nihil.INK
            paint.textSize = min(d * 15, rowH * 0.4f)
            c.drawText(name, 0f, y + paint.textSize * 0.35f, paint)
            if (lvl >= 0) {
                Dots.bar(c, w * 0.55f, y, w * 0.3f, 8, lvl / 100f, if (lvl <= 15) Nihil.DANGER else Nihil.INK, withAlpha(Nihil.INK, 0.12f), paint)
                paint.typeface = fonts.mono
                paint.textSize = min(d * 12, rowH * 0.34f)
                paint.textAlign = Paint.Align.RIGHT
                c.drawText("$lvl%", w.toFloat(), y + paint.textSize * 0.35f, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }
}
