package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.DotRender
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.system.GlyphListener
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.sin

private val worker = Executors.newSingleThreadExecutor()

/**
 * Обложка того, что играет, — светодиодами (Dotify обложки), под ней LED-полосы,
 * которые «играют», пока идёт музыка (без микрофона: по состоянию плеера), и название.
 * Нажатие — пауза/пуск. Нужен доступ к уведомлениям (тот же, что у Glyph).
 */
class CoverWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var art: Bitmap? = null
    private var dotted: Bitmap? = null
    private var title = ""
    private var playing = false
    private var phase = 0f
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    private val frame = object : Runnable {
        override fun run() {
            phase += 0.35f
            canvasView.invalidate()
            if (playing && isAttachedToWindow) main.postDelayed(this, 90)
        }
    }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    init {
        body.addView(canvasView)
        onTap {
            controller?.let { c -> if (playing) c.transportControls.pause() else c.transportControls.play() }
        }
    }

    private fun manager() = context.getSystemService(MediaSessionManager::class.java)
    private fun listener() = ComponentName(context, GlyphListener::class.java)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { manager()?.addOnActiveSessionsChangedListener(sessions, listener(), main) }
        pick()
    }

    override fun onDetachedFromWindow() {
        runCatching { manager()?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        main.removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    private fun pick() {
        val list = runCatching { manager()?.getActiveSessions(listener()) }.getOrNull().orEmpty()
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        val meta = next?.metadata
        title = listOfNotNull(meta?.getString(MediaMetadata.METADATA_KEY_TITLE), meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" · ")
        val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp !== art) {
            art = bmp
            dotted = null
        }
        val was = playing
        playing = next?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing && !was) main.post(frame)
        canvasView.invalidate()
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val d = resources.displayMetrics.density
        val side = min(w.toFloat(), h * 0.7f).toInt().coerceAtLeast(1)
        val left = (w - side) / 2f
        val a = art
        if (a != null) {
            if (dotted == null || dotted!!.width != side) {
                val square = Bitmap.createScaledBitmap(a, side, side, true)
                dotted = if (dots) runCatching {
                    DotRender.render(DotRender.grid(square, (side / 28).coerceAtLeast(3)), side, side, Nihil.INK, Nihil.VOID)
                }.getOrNull() ?: square else square
            }
            c.drawBitmap(dotted!!, left, 0f, paint)
        } else {
            // Без обложки — точки-плейсхолдер.
            for (y in 0 until 12) for (x in 0 until 12) {
                paint.color = withAlpha(Nihil.INK, 0.08f)
                c.drawCircle(left + (x + 0.5f) * side / 12, (y + 0.5f) * side / 12, side / 60f, paint)
            }
        }
        // LED-полосы.
        val bars = 16
        val bw = w / bars.toFloat()
        val top = side + d * 6
        val bh = h - top - d * 18
        for (i in 0 until bars) {
            val level = if (playing) (0.35f + 0.65f * ((sin(phase + i * 0.8f) + sin(phase * 1.7f + i * 0.37f)) / 4f + 0.5f)) else 0.08f
            val n = (level * 5).toInt().coerceIn(1, 5)
            for (k in 0 until 5) {
                paint.color = if (k < n) (if (k == 4) Nihil.SIGNAL else Nihil.INK) else withAlpha(Nihil.INK, 0.1f)
                c.drawCircle((i + 0.5f) * bw, top + bh - (k + 0.5f) * bh / 5, min(bw, bh / 5) * 0.3f, paint)
            }
        }
        paint.typeface = fonts.mono
        paint.textSize = d * 11
        paint.color = Nihil.DIM
        val shown = if (title.isEmpty()) context.getString(R.string.deck_nothing_playing) else title
        c.drawText(ellipsize(shown, w.toFloat()), 0f, h - d * 3, paint)
    }

    private fun ellipsize(text: String, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}

/**
 * Фоторамка: свои фото (системный выбор, доступ сохраняется), по одной, смена по
 * интервалу или нажатию; опция dotify — светодиодами в цветах темы.
 */
class PhotoWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private var shown: Bitmap? = null
    private var index = 0
    private val canvasView = CardCanvas(context) { c, w, h -> draw(c, w, h) }
    private val next = Runnable { advance() }

    init {
        body.setPadding(0, 0, 0, 0)
        body.addView(canvasView)
        onTap {
            if (uris().isEmpty()) {
                host.pickImages { picked ->
                    item.options.put("uris", JSONArray(picked.map { it.toString() }))
                    Desk.save(context)
                    index = 0
                    load()
                }
            } else {
                advance()
            }
        }
    }

    private fun uris(): List<Uri> = item.options.optJSONArray("uris")?.let { a -> List(a.length()) { Uri.parse(a.getString(it)) } } ?: emptyList()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        load()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(next)
        super.onDetachedFromWindow()
    }

    private fun advance() {
        index++
        load()
    }

    private fun load() {
        main.removeCallbacks(next)
        val list = uris()
        if (list.isEmpty() || width == 0) {
            canvasView.invalidate()
            if (width == 0) post { if (uris().isNotEmpty()) load() }
            return
        }
        val uri = list[index % list.size]
        val w = width
        val h = height
        val dotify = item.options.optBoolean("dotify", false)
        val tint = Nihil.INK
        val bg = Nihil.VOID
        val resolver = context.contentResolver
        worker.execute {
            val bmp = runCatching {
                val cover = DotRender.decodeCover(ImageDecoder.createSource(resolver, uri), w, h)
                if (dotify) DotRender.render(DotRender.grid(cover, (w / 40).coerceAtLeast(4)), w, h, tint, bg) else cover
            }.getOrNull()
            main.post {
                shown = bmp
                canvasView.invalidate()
            }
        }
        val minutes = item.options.optInt("interval", 10).coerceIn(1, 240)
        if (list.size > 1) main.postDelayed(next, minutes * 60_000L)
    }

    private fun draw(c: Canvas, w: Int, h: Int) {
        val b = shown
        if (b != null) {
            c.drawBitmap(b, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
            return
        }
        val d = resources.displayMetrics.density
        paint.typeface = fonts.mono
        paint.textSize = d * 12
        paint.color = Nihil.DIM
        paint.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.w_photo_pick), w / 2f, h / 2f, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}

/** Цитата дня: своя на каждый день, нажатие — следующая. */
class QuoteWidget(context: Context, item: Desk.Item, host: DeskHost) : CardWidget(context, item, host) {
    private val quote = android.widget.TextView(context)
    private val who = android.widget.TextView(context)
    private var shift = 0

    init {
        val col = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(quote)
            addView(who)
        }
        quote.typeface = if (dots) fonts.dot else fonts.sans
        quote.setTextColor(Nihil.INK)
        quote.textSize = 17f
        quote.setLineSpacing(0f, 1.1f)
        who.typeface = fonts.mono
        who.setTextColor(Nihil.MUTED)
        who.textSize = 11f
        who.setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, 0)
        body.addView(col)
        onTap {
            shift++
            show()
        }
        show()
    }

    private fun show() {
        val list = QUOTES
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val (text, author) = list[(day + shift) % list.size]
        quote.text = "«$text»"
        who.text = "— $author"
    }

    override fun onClock(animate: Boolean) = show()

    private companion object {
        // Короткие и общеизвестные; авторы — давно ставшие общественным достоянием.
        val QUOTES = listOf(
            "Simplicity is the ultimate sophistication" to "Leonardo da Vinci",
            "Well begun is half done" to "Aristotle",
            "The only true wisdom is in knowing you know nothing" to "Socrates",
            "Nothing will come of nothing" to "William Shakespeare",
            "Brevity is the soul of wit" to "William Shakespeare",
            "Knowledge is power" to "Francis Bacon",
            "I think, therefore I am" to "René Descartes",
            "Less is more" to "Robert Browning",
            "Краткость — сестра таланта" to "А. П. Чехов",
            "Красота спасёт мир" to "Ф. М. Достоевский",
            "Всё к лучшему в этом лучшем из миров" to "Вольтер",
            "Ученье — свет, а неученье — тьма" to "Пословица",
            "Тише едешь — дальше будешь" to "Пословица",
            "The journey of a thousand miles begins with one step" to "Laozi",
            "Waste no more time arguing what a good man should be. Be one" to "Marcus Aurelius",
            "We suffer more often in imagination than in reality" to "Seneca",
        )
    }
}

