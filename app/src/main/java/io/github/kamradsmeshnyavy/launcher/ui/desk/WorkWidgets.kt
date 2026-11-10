package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import io.github.kamradsmeshnyavy.launcher.DeckActivity
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.deck.DeckClient
import io.github.kamradsmeshnyavy.launcher.deck.DeckStore
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

private val io = Executors.newSingleThreadExecutor()

/**
 * Ближайшие события календаря (сегодня и два дня вперёд): время и название; точка —
 * цвет календаря. Разрешение «Календарь» — по нажатию; потом нажатие открывает календарь.
 */
class EventsWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private class Event(val start: Long, val title: String, val color: Int, val allDay: Boolean)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var events: List<Event> = emptyList()
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }

    init {
        body.addView(canvasView)
        onTap {
            if (!allowed()) host.ask(Manifest.permission.READ_CALENDAR) { refresh() }
            else context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), System.currentTimeMillis())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        refresh()
    }

    private fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun refresh() {
        if (!allowed()) {
            canvasView.invalidate()
            return
        }
        val resolver = context.contentResolver
        io.execute {
            val now = System.currentTimeMillis()
            val end = now + 3 * 24 * 3600 * 1000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, now - 3600_000L)
                ContentUris.appendId(it, end)
            }.build()
            val found = ArrayList<Event>()
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.ALL_DAY),
                    null, null, CalendarContract.Instances.BEGIN + " ASC",
                )?.use { cur ->
                    while (cur.moveToNext() && found.size < 6) {
                        if (cur.getLong(1) < now) continue
                        found += Event(cur.getLong(0), cur.getString(2).orEmpty(), cur.getInt(3), cur.getInt(4) == 1)
                    }
                }
            }
            post {
                events = found
                canvasView.invalidate()
            }
        }
    }

    override fun onClock(animate: Boolean) = canvasView.invalidate()

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.MUTED
        c.drawText(context.getString(R.string.w_events).uppercase(), 0f, paint.textSize, paint)
        val msg = when {
            !allowed() -> context.getString(R.string.w_tap_to_allow)
            events.isEmpty() -> context.getString(R.string.w_events_none)
            else -> null
        }
        if (msg != null) {
            paint.color = Nihil.DIM
            paint.textSize = d * 13
            c.drawText(msg, 0f, h / 2f, paint)
            return
        }
        val fmtDay = SimpleDateFormat("EEE", Locale.getDefault())
        val fmtTime = SimpleDateFormat("HH:mm", Locale.ROOT)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val rowH = min(d * 34, (h - d * 18) / 3f)
        val rows = ((h - d * 18) / rowH).toInt().coerceAtLeast(1)
        events.take(rows).forEachIndexed { i, e ->
            val y = d * 20 + rowH * i + rowH / 2
            paint.color = e.color or 0xFF000000.toInt()
            c.drawCircle(d * 4, y, d * 3.5f, paint)
            val cal = Calendar.getInstance().apply { timeInMillis = e.start }
            val time = when {
                e.allDay -> "··:··"
                cal.get(Calendar.DAY_OF_YEAR) == today -> fmtTime.format(Date(e.start))
                else -> fmtDay.format(Date(e.start)) + " " + fmtTime.format(Date(e.start))
            }
            paint.typeface = fonts.mono
            paint.color = Nihil.DIM
            paint.textSize = d * 12
            c.drawText(time, d * 14, y + paint.textSize * 0.35f, paint)
            paint.typeface = fonts.sans
            paint.color = Nihil.INK
            paint.textSize = min(d * 15, rowH * 0.45f)
            val x = d * 14 + d * 82
            var t = e.title
            while (t.isNotEmpty() && paint.measureText(t) > w - x) t = t.dropLast(1)
            c.drawText(t, x, y + paint.textSize * 0.35f, paint)
        }
    }
}

/**
 * Компьютер через omadeck: процессор, память, батарея и температура ноутбука, что играет;
 * кнопки — пауза/пуск, дальше и блокировка. Обновляется, пока виден.
 */
class PcWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private var stats: JSONObject? = null
    private var media: JSONObject? = null
    private var name = DeckStore.get(context).computer?.name.orEmpty()
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    private val poll = object : Runnable {
        override fun run() {
            fetch()
            if (isAttachedToWindow) main.postDelayed(this, 15_000)
        }
    }
    private var buttons: List<Pair<android.graphics.RectF, String>> = emptyList()

    init {
        body.addView(canvasView)
        canvasView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val hit = buttons.firstOrNull { it.first.contains(ev.x, ev.y) }
                if (hit != null) send(hit.second) else context.startActivity(Intent(context, DeckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        }
    }

    private fun client(): DeckClient? {
        val store = DeckStore.get(context)
        val computer = store.computer ?: return null
        return DeckClient(computer, store.token() ?: return null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.post(poll)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    override fun refresh() = fetch()

    private fun fetch() {
        val c = client() ?: run {
            canvasView.invalidate()
            return
        }
        val app = context.applicationContext
        io.execute {
            val s = c.call("GET", "/v1/stats", null, app)?.takeIf { it.status == 200 }?.json
            val st = c.call("GET", "/v1/state")?.takeIf { it.status == 200 }?.json
            post {
                stats = s
                media = st?.optJSONObject("media")
                canvasView.invalidate()
            }
        }
    }

    private fun send(what: String) {
        val c = client() ?: return
        io.execute {
            when (what) {
                "toggle", "next" -> c.call("POST", "/v1/media", JSONObject().put("action", what))
                "lock" -> c.call("POST", "/v1/lock", JSONObject())
            }
            main.postDelayed({ fetch() }, 600)
        }
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.MUTED
        c.drawText((name.ifEmpty { context.getString(R.string.deck_title) }).uppercase(), 0f, paint.textSize, paint)
        val s = stats
        if (client() == null || s == null) {
            paint.color = Nihil.DIM
            paint.textSize = d * 13
            c.drawText(context.getString(if (client() == null) R.string.w_pc_unpaired else R.string.cz_walls_offline), 0f, h / 2f, paint)
            buttons = emptyList()
            return
        }
        // Показатели: подпись, LED-шкала, число.
        val rows = listOf(
            "CPU" to s.optDouble("cpu", -1.0),
            "RAM" to s.optDouble("memory", -1.0),
            "BAT" to s.optDouble("battery", -1.0),
            "TEMP" to s.optDouble("temperature", -1.0),
        ).filter { it.second >= 0 }
        val rowH = min(d * 22, (h * 0.62f - d * 18) / rows.size.coerceAtLeast(1))
        rows.forEachIndexed { i, (label, v) ->
            val y = d * 22 + rowH * i + rowH / 2
            paint.typeface = fonts.mono
            paint.textSize = d * 11
            paint.color = Nihil.DIM
            c.drawText(label, 0f, y + paint.textSize * 0.35f, paint)
            val ratio = if (label == "TEMP") (v / 100.0) else v / 100.0
            val hot = (label == "TEMP" && v >= 80) || (label == "BAT" && v <= 15) || (label != "BAT" && label != "TEMP" && v >= 90)
            Dots.bar(c, d * 44, y, w - d * 44 - d * 46, 14, ratio.toFloat(), if (hot) Nihil.DANGER else Nihil.INK, withAlpha(Nihil.INK, 0.1f), paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Nihil.INK
            c.drawText(if (label == "TEMP") "${v.toInt()}°" else "${v.toInt()}%", w.toFloat(), y + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        // Что играет и кнопки.
        val by = h - d * 26
        val m = media
        paint.typeface = fonts.sans
        paint.textSize = d * 12
        paint.color = Nihil.DIM
        var t = m?.optString("title").orEmpty().ifEmpty { context.getString(R.string.deck_nothing_playing) }
        val room = w - d * 120
        while (t.isNotEmpty() && paint.measureText(t) > room) t = t.dropLast(1)
        c.drawText(t, 0f, by + d * 4, paint)
        val labels = listOf((if (m?.optBoolean("playing") == true) "❚❚" else "▶") to "toggle", "▶▶" to "next", "🔒" to "lock")
        val bw = d * 36
        buttons = labels.mapIndexed { i, (glyph, action) ->
            val x = w - (labels.size - i) * bw
            val r = android.graphics.RectF(x, by - d * 16, x + bw - d * 4, by + d * 16)
            paint.color = withAlpha(Nihil.INK, 0.08f)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
            paint.color = Nihil.INK
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = fonts.mono
            paint.textSize = d * 12
            c.drawText(glyph, r.centerX(), r.centerY() + paint.textSize * 0.35f, paint)
            paint.textAlign = Paint.Align.LEFT
            r to action
        }
    }
}
