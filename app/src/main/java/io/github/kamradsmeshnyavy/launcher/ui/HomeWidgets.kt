package io.github.kamradsmeshnyavy.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.TaskBoard
import io.github.kamradsmeshnyavy.launcher.deck.TasksStore
import io.github.kamradsmeshnyavy.launcher.system.GlyphListener

/**
 * «Сейчас играет», как виджет media на десктопе: что играет в любом плеере телефона,
 * и назад / пауза / вперёд. Сессии видны только с доступом к уведомлениям (тот же
 * GlyphListener, что для Glyph); без него карточки нет.
 */
class MediaCard(context: Context) : LinearLayout(context) {
    private val title = context.text("", 15f, Nihil.INK, Nihil.fonts(context).sans).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = context.mono("", 11f, Nihil.DIM).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val play = button(LineIcon.Shape.PLAY) { controller?.let { c -> if (playing) c.transportControls.pause() else c.transportControls.play() } }
    private var controller: MediaController? = null
    private var playing = false
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = pick()
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Nihil.VOID, context.dp(18f), context.dp(1), Nihil.LINE)
        setPadding(context.dp(16), context.dp(12), context.dp(10), context.dp(12))
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(artist, llp(MATCH, WRAP).apply { topMargin = context.dp(3) })
        }
        addView(texts, llp(0, WRAP, 1f))
        addView(button(LineIcon.Shape.PREVIOUS) { controller?.transportControls?.skipToPrevious() })
        addView(play)
        addView(button(LineIcon.Shape.NEXT) { controller?.transportControls?.skipToNext() })
        visibility = GONE
    }

    private fun button(shape: LineIcon.Shape, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(LineIcon(shape, Nihil.INK))
        val pad = context.dp(10)
        setPadding(pad, pad, pad, pad)
        background = ripple(context.dp(18f))
        layoutParams = llp(context.dp(42), context.dp(42))
        setOnClickListener { onClick() }
    }

    private fun manager(): MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)

    private fun listener() = ComponentName(context, GlyphListener::class.java)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { manager()?.addOnActiveSessionsChangedListener(sessions, listener(), main) }
        pick()
    }

    override fun onDetachedFromWindow() {
        runCatching { manager()?.removeOnActiveSessionsChangedListener(sessions) }
        controller?.unregisterCallback(callback)
        controller = null
        super.onDetachedFromWindow()
    }

    /** Играющая сессия, а нет — первая. */
    private fun pick() {
        val list = runCatching { manager()?.getActiveSessions(listener()) }.getOrNull().orEmpty()
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        val meta = c?.metadata
        val name = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || name.isNullOrEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        title.text = name
        artist.text = listOfNotNull(
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.ifEmpty { null },
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull(),
        ).joinToString(" · ")
        playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        (play.drawable as? LineIcon)?.let { play.setImageDrawable(LineIcon(if (playing) LineIcon.Shape.PAUSE else LineIcon.Shape.PLAY, Nihil.INK)) }
    }
}

/** Список дел, как виджет todo десктопа: несделанные карточки доски (её же синхронизирует deck). */
class TodoCard(context: Context, private val max: Int, private val onOpen: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = rounded(Nihil.VOID, context.dp(18f), context.dp(1), Nihil.LINE)
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        setOnClickListener { onOpen() }
        refresh()
    }

    fun refresh() {
        removeAllViews()
        val open = TaskBoard.ordered(TasksStore.get(context).board.cards).filter { it.column < TaskBoard.COLUMNS - 1 }
        if (open.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        addView(context.mono(context.getString(R.string.tasks_title).uppercase(), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { bottomMargin = context.dp(6) })
        open.take(max).forEach { card ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { background = rounded(if (card.column == 1) Nihil.SIGNAL else Nihil.DIM, context.dp(3f)) }, llp(context.dp(6), context.dp(6)).apply { marginEnd = context.dp(10) })
                addView(context.text(card.text, 14f, Nihil.INK, Nihil.fonts(context).sans).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, llp(0, WRAP, 1f))
            }, llp(MATCH, WRAP).apply { topMargin = context.dp(4) })
        }
        if (open.size > max) addView(context.mono("+${open.size - max}", 11f, Nihil.DIM), llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
    }
}

/** Как рисуются часы: стиль виджетов Customizer (widgetStyle, clockStyle, clock24, clockSeconds). */
class ClockLook(look: Look.Resolved, system24: Boolean) {
    val show = look.bool("clock", true)
    val style = look.str("clockStyle", "big")
    val seconds = look.bool("clockSeconds", false)
    val is24 = (look.values["clock24"] as? Boolean) ?: system24
    /** Точками: виджеты DOT-MATRIX, или «по стилю» у стиля DOT-MATRIX / точечных шрифтов. */
    val dots = when (look.str("widgetStyle", "style")) {
        "dotmatrix" -> true
        "default" -> false
        else -> look.style?.id == "dotmatrix" || look.fontProfile != "stock"
    }
    val media = look.bool("media", true)
    val todo = look.bool("todo", false)
    val todoMax = look.num("todoMax", 8.0).toInt().coerceIn(1, 20)
}
