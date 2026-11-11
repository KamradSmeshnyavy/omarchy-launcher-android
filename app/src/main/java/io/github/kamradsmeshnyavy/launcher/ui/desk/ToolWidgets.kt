package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Process
import android.provider.Settings
import android.view.View
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Torch
import io.github.kamradsmeshnyavy.launcher.ui.LineIcon
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun Context.go(intent: Intent) = startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** Быстрые кнопки: фонарик, Wi-Fi, Bluetooth, звук — кружками, включённое горит. */
class TogglesWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    private var hits: List<Pair<RectF, () -> Unit>> = emptyList()
    private val torch: (Boolean) -> Unit = { canvasView.invalidate() }

    init {
        body.addView(canvasView)
        tapZones(canvasView) { x, y -> hits.firstOrNull { it.first.contains(x, y) }?.second?.invoke() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Torch.listen(torch)
    }

    override fun onDetachedFromWindow() {
        Torch.unlisten(torch)
        super.onDetachedFromWindow()
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val buttons: List<Triple<LineIcon.Shape, Boolean, () -> Unit>> = listOf(
            Triple(LineIcon.Shape.TORCH, Torch.on) { Torch.toggle(); Unit },
            Triple(LineIcon.Shape.WIFI, false) { context.go(Intent(Settings.Panel.ACTION_WIFI)) },
            Triple(LineIcon.Shape.BLUETOOTH, false) { context.go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            Triple(LineIcon.Shape.SOUND, false) { context.go(Intent(Settings.Panel.ACTION_VOLUME)) },
        )
        val cell = w / buttons.size.toFloat()
        val r = min(cell, h.toFloat()) * 0.4f
        hits = buttons.mapIndexed { i, (shape, on, act) ->
            val cx = (i + 0.5f) * cell
            val cy = h / 2f
            paint.style = Paint.Style.FILL
            paint.color = if (on) Nihil.INK else withAlpha(Nihil.INK, 0.08f)
            c.drawCircle(cx, cy, r, paint)
            if (!on) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = resources.displayMetrics.density
                paint.color = Nihil.LINE
                c.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
            }
            val s = (r * 0.9f).toInt()
            LineIcon(shape, if (on) Nihil.VOID else Nihil.INK).apply {
                setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            }.draw(c)
            RectF(cx - r, cy - r, cx + r, cy + r) to act
        }
    }
}

/** Компас: кольцо точек поворачивается с телефоном, север — акцентом, курс — числом. */
class CompassWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host), SensorEventListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var heading = 0f
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    private val matrix = FloatArray(9)
    private val angles = FloatArray(3)

    init {
        body.addView(canvasView)
    }

    /** Датчик — только пока лаунчер на экране (onWindowVisibilityChanged зовётся и при показе). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sensors?.unregisterListener(this)
        if (visibility == View.VISIBLE && isAttachedToWindow) {
            sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    override fun onDetachedFromWindow() {
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, angles)
        val next = ((Math.toDegrees(angles[0].toDouble()) + 360) % 360).toFloat()
        if (kotlin.math.abs(next - heading) > 0.8f) {
            heading = next
            canvasView.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.86f
        for (i in 0 until 36) {
            val a = Math.toRadians(i * 10.0 - heading - 90)
            val north = i == 0
            paint.color = if (north) Nihil.SIGNAL else withAlpha(Nihil.INK, if (i % 9 == 0) 0.8f else 0.25f)
            c.drawCircle(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), d * (if (north) 4f else if (i % 9 == 0) 2.6f else 1.6f), paint)
        }
        val text = heading.roundToInt().toString() + "°"
        val pitch = min(r * 1.0f / Dots.columns(text), r * 0.6f / Dots.ROWS)
        Dots.draw(c, text, cx - Dots.columns(text) * pitch / 2, cy - Dots.ROWS * pitch / 2, pitch, Nihil.INK, 0, paint)
    }
}

/** Шаги за сегодня (датчик шагов телефона); цель — опция goal, кольцо — сколько пройдено. */
class StepsWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host), SensorEventListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private var steps = -1
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { if (!allowed()) host.ask(Manifest.permission.ACTIVITY_RECOGNITION) { start() } }
    }

    private fun allowed() = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (!allowed()) return
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Счётчик — с загрузки телефона: за сегодня — от первого значения за день.
        val total = event.values[0].toInt()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
        val base = prefs.getInt("base-$day", -1).let { if (it < 0 || it > total) total.also { t -> prefs.edit().clear().putInt("base-$day", t).apply() } else it }
        steps = total - base
        canvasView.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val goal = item.options.optInt("goal", 8000).coerceAtLeast(100)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.86f
        val count = 32
        val lit = if (steps < 0) 0 else (min(1f, steps / goal.toFloat()) * count).toInt()
        for (i in 0 until count) {
            val a = i * 2 * PI / count - PI / 2
            paint.color = if (i < lit) Nihil.INK else withAlpha(Nihil.INK, 0.12f)
            c.drawCircle(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat(), d * 2.8f, paint)
        }
        val text = if (!allowed()) "--" else if (steps < 0) "0" else steps.toString()
        val pitch = min(r * 1.2f / Dots.columns(text), r * 0.5f / Dots.ROWS)
        Dots.draw(c, text, cx - Dots.columns(text) * pitch / 2, cy - Dots.ROWS * pitch / 2 - d * 4, pitch, Nihil.INK, 0, paint)
        paint.typeface = fonts.mono
        paint.textSize = d * 10
        paint.color = Nihil.MUTED
        paint.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(if (allowed()) R.string.w_steps else R.string.w_tap_to_allow).uppercase(), cx, cy + Dots.ROWS * pitch / 2 + d * 10, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}

/** Обратный отсчёт: сколько дней до даты (опции title, date — ГГГГ-ММ-ДД). */
class CountdownWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap { host.editOptions(item) }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun days(): Long? {
        val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(item.options.optString("date")) }.getOrNull() ?: return null
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return TimeUnit.MILLISECONDS.toDays(date.time - today.timeInMillis)
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val left = days()
        val title = item.options.optString("title").ifEmpty { context.getString(R.string.w_countdown_set) }
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.MUTED
        c.drawText(title.uppercase(), 0f, paint.textSize, paint)
        val text = left?.toString() ?: "--"
        val top = d * 18
        val pitch = min((h - top - d * 14) / Dots.ROWS, w * 0.9f / Dots.columns(text))
        Dots.draw(c, text, 0f, top + (h - top - d * 14 - Dots.ROWS * pitch) / 2, pitch, if (left != null && left <= 3) Nihil.SIGNAL else Nihil.INK, withAlpha(Nihil.INK, 0.08f), paint)
        paint.textSize = d * 10
        c.drawText(context.getString(R.string.w_days).uppercase(), 0f, h - d * 2, paint)
    }
}

/** Экранное время за сегодня и три самых долгих приложения (доступ к статистике — по нажатию). */
class ScreenTimeWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap {
            context.go(if (allowed()) Intent(Settings.ACTION_SETTINGS) else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    override fun refresh() = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.MUTED
        c.drawText(context.getString(R.string.w_screen_time).uppercase(), 0f, paint.textSize, paint)
        if (!allowed()) {
            paint.color = Nihil.DIM
            paint.textSize = d * 13
            c.drawText(context.getString(R.string.w_tap_to_allow), 0f, h / 2f, paint)
            return
        }
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val stats = runCatching {
            context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(start, System.currentTimeMillis())
        }.getOrNull().orEmpty().values.filter { it.totalTimeInForeground > 60_000 && it.packageName != context.packageName }
        val total = stats.sumOf { it.totalTimeInForeground }
        val text = "${TimeUnit.MILLISECONDS.toHours(total)}:${(TimeUnit.MILLISECONDS.toMinutes(total) % 60).toString().padStart(2, '0')}"
        val top = d * 18
        val pitch = min(h * 0.35f / Dots.ROWS, w * 0.5f / Dots.columns(text))
        Dots.draw(c, text, 0f, top, pitch, Nihil.INK, 0, paint)
        val pm = context.packageManager
        val y0 = top + Dots.ROWS * pitch + d * 10
        val rows = stats.sortedByDescending { it.totalTimeInForeground }.take(3)
        val rowH = min(d * 22, (h - y0) / 3)
        rows.forEachIndexed { i, s ->
            val y = y0 + rowH * i + rowH / 2
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
            paint.typeface = fonts.sans
            paint.textSize = min(d * 13, rowH * 0.6f)
            paint.color = Nihil.INK
            c.drawText(name, 0f, y + paint.textSize * 0.35f, paint)
            Dots.bar(c, w * 0.55f, y, w * 0.3f, 8, s.totalTimeInForeground / total.coerceAtLeast(1).toFloat(), Nihil.INK, withAlpha(Nihil.INK, 0.1f), paint)
            paint.typeface = fonts.mono
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Nihil.DIM
            c.drawText("${TimeUnit.MILLISECONDS.toMinutes(s.totalTimeInForeground)}m", w.toFloat(), y + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }
}
