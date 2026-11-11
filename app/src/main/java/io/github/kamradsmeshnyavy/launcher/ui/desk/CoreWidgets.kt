package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.Context
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Apps
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.IconStyle
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.Weather
import io.github.kamradsmeshnyavy.launcher.ui.ClockLook
import io.github.kamradsmeshnyavy.launcher.ui.EASE
import io.github.kamradsmeshnyavy.launcher.ui.LedGauge
import io.github.kamradsmeshnyavy.launcher.ui.MATCH
import io.github.kamradsmeshnyavy.launcher.ui.MediaCard
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.ReorderGrid
import io.github.kamradsmeshnyavy.launcher.ui.TodoCard
import io.github.kamradsmeshnyavy.launcher.ui.WRAP
import io.github.kamradsmeshnyavy.launcher.ui.WeatherIcon
import io.github.kamradsmeshnyavy.launcher.ui.bindIcon
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.llp
import io.github.kamradsmeshnyavy.launcher.ui.lp
import io.github.kamradsmeshnyavy.launcher.ui.mono
import io.github.kamradsmeshnyavy.launcher.ui.ripple
import io.github.kamradsmeshnyavy.launcher.ui.rounded
import io.github.kamradsmeshnyavy.launcher.ui.rule
import io.github.kamradsmeshnyavy.launcher.ui.text
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Карточка часов NIHIL (home/shell/background/Desktop.qml): время точками, день и дата,
 * погода, под линией батарея светодиодами, будильник и приложения с непрочитанным.
 * Вид — по clockStyle/widgetStyle Customizer'а (ClockLook).
 */
class ClockWidget(context: Context, item: Desk.Item, host: DeskHost) : DeskWidget(context, item, host) {
    private val fonts = Nihil.fonts(context)
    private var baseClockSize = 0f

    override fun onFixed(fixed: Boolean) {
        // В сетке: карточка во все клетки, содержимое по центру, цифры крупнее на высоких.
        fill(card, fixed)
        card.gravity = (if (clockLook.style == "big") Gravity.CENTER_HORIZONTAL else Gravity.START) or
            (if (fixed) Gravity.CENTER_VERTICAL else Gravity.TOP)
        val grow = if (fixed) (item.h / 2.4f).coerceIn(1f, 1.7f) else 1f
        if (baseClockSize > 0f) clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseClockSize * grow)
    }

    private val clockLook = ClockLook(Look.now, DateFormat.is24HourFormat(context))
    private val ticker = object : Runnable {
        override fun run() {
            onClock(animate = false)
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val clock = when (clockLook.style) {
        "stacked" -> context.text("", 64f, Nihil.INK, if (clockLook.dots) fonts.dot else fonts.sansMedium).apply {
            gravity = Gravity.START
            maxLines = 2
            setLineSpacing(0f, 0.86f)
        }
        "terminal" -> context.text("", 40f, Nihil.INK, fonts.mono).apply {
            gravity = Gravity.START
            maxLines = 1
        }
        "minimal" -> context.text("", 58f, Nihil.INK, if (clockLook.dots) fonts.dot else fonts.sans, -0.02f).apply {
            gravity = Gravity.START
            maxLines = 1
        }
        else -> context.text("", 68f, Nihil.INK, if (clockLook.dots) fonts.dot else fonts.sansMedium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
        }
    }
    private val day = context.mono(size = 12f, color = Nihil.DIM)
    private val date = context.text("", 14f, Nihil.MUTED)
    private val weatherIcon = ImageView(context)
    private val weatherText = context.mono(size = 12f, color = Nihil.DIM)
    private val weather = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(context.dp(6), context.dp(4), context.dp(8), context.dp(4))
        background = ripple(context.dp(12f))
        addView(weatherIcon, llp(context.dp(20), context.dp(20)))
        addView(weatherText, llp(WRAP, WRAP).apply { marginStart = context.dp(8) })
        setOnClickListener { runCatching { context.startActivity(Weather.intent(context)) } }
    }
    private val gauge = LedGauge(context)
    private val battery = context.mono(size = 11f, color = Nihil.DIM)
    private val alarm = context.mono(size = 11f, color = Nihil.DIM)
    private val unread = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val unreadMore = context.mono(size = 11f, color = Nihil.DIM)

    init {
        clipChildren = false
        card.background = if (clockLook.style == "minimal") null else rounded(Nihil.VOID, context.dp(22f), context.dp(1), Nihil.LINE)
        card.setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
        if (clockLook.style != "big") card.gravity = Gravity.START
        if (clockLook.show) {
            card.addView(clock, llp(MATCH, WRAP))
            card.addView(day, llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
            card.addView(date, llp(WRAP, WRAP).apply { topMargin = context.dp(4) })
            card.addView(weather, llp(WRAP, WRAP).apply { topMargin = context.dp(6) })
            card.addView(context.rule(), llp(MATCH, context.dp(1)).apply {
                topMargin = context.dp(16)
                bottomMargin = context.dp(12)
            })
        }
        if (clockLook.style == "minimal") {
            clock.setShadowLayer(context.dp(10f), 0f, 0f, withAlpha(Nihil.VOID, 0.4f))
            date.setShadowLayer(context.dp(6f), 0f, 0f, withAlpha(Nihil.VOID, 0.4f))
        }
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gauge, llp(WRAP, WRAP))
            addView(battery, llp(0, WRAP, 1f).apply { marginStart = context.dp(10) })
            addView(alarm, llp(WRAP, WRAP).apply { marginStart = context.dp(10) })
        }
        card.addView(status, llp(MATCH, WRAP))
        unread.addView(unreadMore)
        unread.setOnClickListener { host.openShade() }
        unread.background = ripple(context.dp(12f))
        unread.setPadding(0, context.dp(6), context.dp(6), context.dp(2))
        card.addView(unread, llp(MATCH, WRAP).apply { topMargin = context.dp(8) })
        card.isLongClickable = true
        card.setOnLongClickListener { performLongClick() }
        addView(card, lp(MATCH, WRAP, Gravity.TOP))
        onClock(animate = false)
        unread.visibility = View.GONE
        baseClockSize = clock.textSize
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Секунды тикают, только пока лаунчер на экране.
        removeCallbacks(ticker)
        if (clockLook.seconds && visibility == View.VISIBLE && isAttachedToWindow) post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onClock(animate: Boolean) {
        val now = Date()
        val pattern = (if (clockLook.is24) "HH:mm" else "h:mm") + if (clockLook.seconds) ":ss" else ""
        val formatted = SimpleDateFormat(pattern, Locale.ROOT).format(now)
        val time = when (clockLook.style) {
            "stacked" -> formatted.replaceFirst(":", "\n").replace(":", " ")
            "terminal" -> "> $formatted"
            else -> formatted
        }
        if (clock.text.toString() != time) {
            clock.text = time
            if (animate && !clockLook.seconds) {
                clock.alpha = 0.4f
                clock.animate().alpha(1f).setDuration(Nihil.NORMAL).setInterpolator(EASE).start()
            }
        }
        val locale = Locale.getDefault()
        day.text = SimpleDateFormat("EEEE", locale).format(now).replaceFirstChar { it.titlecase(locale) }
        date.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale).format(now)
        if (clockLook.style == "terminal") {
            day.text = "# " + day.text.toString().lowercase(locale)
            date.typeface = fonts.mono
        }
    }

    override fun onBattery(level: Int, charging: Boolean) {
        gauge.set(level, charging)
        val percent = context.getString(R.string.battery_percent, level)
        battery.text = if (charging) "$percent, ${context.getString(R.string.battery_charging)}" else percent
        battery.setTextColor(if (gauge.low) Nihil.SIGNAL else Nihil.DIM)
    }

    override fun onAlarm(text: String?) {
        alarm.visibility = if (text == null) View.GONE else View.VISIBLE
        alarm.text = text.orEmpty()
    }

    override fun onUnread(packages: List<String>, style: IconStyle) {
        while (unread.childCount > 1) unread.removeViewAt(0)
        val apps = packages.mapNotNull { Apps.byPackage(it) }.distinctBy { it.packageName }
        val size = context.dp(18)
        apps.take(MAX_UNREAD).forEachIndexed { index, entry ->
            val icon = ImageView(context)
            icon.bindIcon(entry, size, if (style == IconStyle.COLOR) IconStyle.COLOR else IconStyle.MONO)
            unread.addView(icon, index, llp(size, size).apply { marginEnd = context.dp(10) })
        }
        val extra = apps.size - MAX_UNREAD
        unreadMore.text = if (extra > 0) "+$extra" else ""
        unreadMore.visibility = if (extra > 0) View.VISIBLE else View.GONE
        unread.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
        unread.contentDescription = apps.joinToString { it.label }
    }

    override fun onWeather(now: Weather.Now?) {
        val sp = Look.prefs(context)
        if (now == null || !sp.getBoolean(Weather.SHOW, true)) {
            weather.visibility = View.GONE
            return
        }
        weather.visibility = View.VISIBLE
        weatherIcon.setImageDrawable(WeatherIcon(now.kind, now.day, Nihil.INK))
        weatherText.text = listOf(Weather.format(now, sp.getString(Weather.UNITS, "auto") ?: "auto"), now.place)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        weather.contentDescription = weatherText.text
    }

    private companion object {
        const val MAX_UNREAD = 6
    }
}

/** «Сейчас играет» (MediaCard) как элемент стола; без сессии — пустое место не занимает в стопке. */
class MediaWidget(context: Context, item: Desk.Item, host: DeskHost) : DeskWidget(context, item, host) {
    private val card = MediaCard(context)

    init {
        card.isLongClickable = true
        card.setOnLongClickListener { performLongClick() }
        addView(card, lp(MATCH, WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun onFixed(fixed: Boolean) {
        fill(card, fixed, Gravity.CENTER_VERTICAL)
        if (fixed) card.gravity = Gravity.CENTER_VERTICAL
    }
}

/** Список дел (TodoCard) — доска, общая с компьютером. */
class TodoWidget(context: Context, item: Desk.Item, host: DeskHost) : DeskWidget(context, item, host) {
    private val card = TodoCard(context, item.options.optInt("max", 8).coerceIn(1, 20)) { host.openTasks() }

    init {
        card.isLongClickable = true
        card.setOnLongClickListener { performLongClick() }
        addView(card, lp(MATCH, WRAP, Gravity.TOP))
    }

    override fun onFixed(fixed: Boolean) = fill(card, fixed)

    override fun refresh() = card.refresh()
}

/**
 * Избранное NIHIL: приложения и сайты списком, крупно, у большого пальца; порядок —
 * перетаскиванием (держать и вести). Больше, чем помещается, — прокрутка под соседями.
 */
class FavouritesWidget(context: Context, item: Desk.Item, host: DeskHost) : DeskWidget(context, item, host) {
    private val fonts = Nihil.fonts(context)
    private val list = ReorderGrid(context, 1, context.dp(52))
    private val holder = FrameLayout(context)
    private val scroll = object : ScrollView(context) {
        override fun getBottomFadingEdgeStrength(): Float = 0f
    }.apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(context.dp(56))
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
    }
    private val empty = context.mono(context.getString(R.string.favorites_empty), 11f, Nihil.DIM)
    private var shownKeys: List<String> = emptyList()

    init {
        // Промежутки избранного — «пустое место» домашнего экрана (меню по долгому нажатию).
        isLongClickable = false
        setOnLongClickListener(null)
        holder.setPadding(0, 0, 0, 0)
        holder.addView(list, lp(MATCH, WRAP, Gravity.BOTTOM))
        scroll.addView(holder, ViewGroup.LayoutParams(MATCH, WRAP))
        addView(scroll, lp(MATCH, MATCH))
        addView(empty, lp(MATCH, WRAP, Gravity.BOTTOM))
        empty.setShadowLayer(context.dp(6f), 0f, 0f, withAlpha(Nihil.VOID, 0.6f))
        list.onReorder = { from, to ->
            val keys = shownKeys.toMutableList()
            keys.add(to, keys.removeAt(from))
            shownKeys = keys
            host.orderFavorites(keys)
        }
    }

    override fun scrollableAt(rawX: Float, rawY: Float, dy: Float): Boolean =
        inside(scroll, rawX, rawY) && scroll.canScrollVertically(if (dy < 0) 1 else -1)

    override fun onFavorites(entries: List<Entry>, icons: Boolean, style: IconStyle) {
        list.removeAllViews()
        shownKeys = entries.map { it.key }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val size = context.dp(24)
        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(8), 0, context.dp(14), 0)
                background = ripple(context.dp(16f))
                contentDescription = entry.label
                setOnClickListener { host.launch(entry, this) }
                setOnLongClickListener {
                    host.menu(entry, this)
                    list.arm(this) { host.closeMenu() }
                    true
                }
            }
            list.movable(row)
            if (icons) {
                val icon = ImageView(context)
                icon.bindIcon(entry, size, style)
                row.addView(icon, llp(size, size).apply { marginEnd = context.dp(16) })
            }
            row.addView(context.text(entry.label, 24f, Nihil.INK, fonts.sans, -0.01f).apply {
                maxLines = 1
                setShadowLayer(context.dp(8f), 0f, 0f, withAlpha(Nihil.VOID, 0.5f))
            }, llp(WRAP, WRAP))
            list.addView(row)
        }
    }
}

/** Приложение или сайт на столе: значок и подпись, как ячейка лаунчера Android. */
class AppCellWidget(context: Context, item: Desk.Item, host: DeskHost, private val entry: Entry) : DeskWidget(context, item, host) {
    init {
        val size = context.dp(48)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ripple(context.dp(18f))
            contentDescription = entry.label
            setOnClickListener { host.launch(entry, this) }
            setOnLongClickListener {
                host.menu(entry, this)
                true
            }
            addView(ImageView(context).apply { bindIcon(entry, size, Io.style(context)) }, llp(size, size))
            addView(context.mono(entry.label, 11f, Nihil.INK).apply {
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(context.dp(6f), 0f, 0f, withAlpha(Nihil.VOID, 0.6f))
            }, llp(MATCH, WRAP).apply { topMargin = context.dp(6) })
        }
        addView(column, lp(MATCH, MATCH))
    }

    private object Io {
        fun style(context: Context) = io.github.kamradsmeshnyavy.launcher.core.Prefs.get(context).iconStyle
    }
}
