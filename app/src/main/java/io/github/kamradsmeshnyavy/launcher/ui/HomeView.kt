package io.github.kamradsmeshnyavy.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.IconStyle
import io.github.kamradsmeshnyavy.launcher.core.Weather
import io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog
import io.github.kamradsmeshnyavy.launcher.ui.desk.DeskHost
import io.github.kamradsmeshnyavy.launcher.ui.desk.DeskPage
import io.github.kamradsmeshnyavy.launcher.ui.desk.DeskWidget

/**
 * Домашний экран внутри карточки рамки: рабочие столы (core/Desk), как воркспейсы
 * Hyprland, — свайп вбок или индикатор листает их. На каждом столе — виджеты и приложения,
 * сеткой или стопкой (Customizer → Лаунчер → deskLayout). Первый стол — домашний экран
 * NIHIL: карточка часов, «сейчас играет», дела и избранное у большого пальца.
 *
 * Снаружи API прежний (updateClock, updateBattery…): события раздаются всем виджетам.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context), RootLayout.Pager {

    interface Host : DeskHost

    private val pager = Workspaces(context).apply { ownTouch = false }
    private val dots = FrameLayout(context)
    private var pages: List<DeskPage> = emptyList()
    private var built = -1

    // Последнее, что пришло: новым виджетам (после перестройки) — сразу.
    private var battery: Pair<Int, Boolean>? = null
    private var alarm: String? = null
    private var unread: Pair<List<String>, IconStyle> = emptyList<String>() to IconStyle.MONO
    private var weather: Weather.Now? = null
    private var favourites: Triple<List<Entry>, Boolean, IconStyle>? = null
    private var insets = intArrayOf(0, 0, 0, 0)

    private val scrim = Paint()
    private var bottomShade: LinearGradient? = null
    private var shadeFor = ""
    private var dim = false

    var onPage: ((Int) -> Unit)? = null
    /** После перестройки: сколько столов и какой текущий (для бара). */
    var onRebuilt: ((Int, Int) -> Unit)? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(pager, lp(MATCH, MATCH))
        addView(dots, lp(MATCH, WRAP, Gravity.BOTTOM))
        pager.onPage = { index ->
            Desk.state(context).current = index
            Desk.save(context)
            (dots.getChildAt(0) as? PageDots)?.select(index)
            onPage?.invoke(index)
        }
        rebuild()
    }

    val page: Int get() = pager.page
    val widgets: List<DeskWidget> get() = pages.flatMap { it.widgets }

    /** Собрать столы заново (после правки стола, смены раскладки или Look). */
    fun rebuild() {
        val mode = Desk.layoutMode(context)
        // Сетка не прокручивается: после любой правки всё должно влезть в строки экрана.
        if (mode == "grid" && Desk.Grid.rows > 0) Desk.fit(context, Desk.Grid.rows)
        val state = Desk.state(context)
        pager.removeAllViews()
        pages = state.spaces.map { space ->
            DeskPage(context, space, mode).also { page ->
                space.items.forEach { item -> Catalog.make(context, item, host)?.let { page.add(it) } }
                page.onDrop = { x, y -> drop(page, x, y) }
                page.onRows = { rows -> fitRows(rows) }
                pager.addView(page)
            }
        }
        dots.removeAllViews()
        if (pages.size > 1 && !io.github.kamradsmeshnyavy.launcher.core.BarSkin.enabled(context)) {
            dots.addView(PageDots(context, state.spaces.map { it.name }) { pager.show(it) }, lp(MATCH, WRAP))
        }
        built = Desk.version
        applyInsets()
        replay()
        val current = state.current.coerceIn(0, pages.size - 1)
        (dots.getChildAt(0) as? PageDots)?.select(current)
        onRebuilt?.invoke(pages.size, current)
        pager.post { pager.show(current, animate = false) }
    }

    /** Сетка узнала, сколько строк влезает: всё, что ниже, — уложить (или на новый стол). */
    private fun fitRows(rows: Int) {
        if (Desk.Grid.rows == rows) return
        Desk.Grid.rows = rows
        if (Desk.layoutMode(context) == "grid" && Desk.fit(context, rows)) rebuild()
    }

    /** Desk поменялся где-то ещё (Customizer, меню): перестроить при возврате. */
    fun rebuildIfStale() {
        if (built != Desk.version) rebuild()
    }

    fun showPage(index: Int) = pager.show(index)

    private fun replay() {
        widgets.forEach { w ->
            w.onClock(false)
            battery?.let { (l, c) -> w.onBattery(l, c) }
            w.onAlarm(alarm)
            w.onUnread(unread.first, unread.second)
            w.onWeather(weather)
            favourites?.let { (e, i, s) -> w.onFavorites(e, i, s) }
        }
    }

    // --- RootLayout.Pager -------------------------------------------------------------

    override val pageCount: Int get() = pages.size
    override fun beginDrag() = pager.beginDrag()
    override fun dragBy(dx: Float) = pager.dragBy(dx)
    override fun release(vx: Float) = pager.release(vx)

    // --- Перенос в сетке ---------------------------------------------------------------

    /** «Переместить»: элемент ведут пальцем по сетке текущего стола; [root] не перехватывает. */
    fun startMove(item: Desk.Item, root: RootLayout) {
        val page = pages.getOrNull(pager.page) ?: return
        root.locked = true
        page.moving = item
        movingRoot = root
    }

    private var movingRoot: RootLayout? = null

    /**
     * «Растянуть»: рамка с ручками вокруг [item] на текущем столе; нажатие мимо — готово.
     * Виджеты приложений — в пределах, что задало приложение, свои — по допустимым размерам.
     */
    fun startResize(item: Desk.Item, sizes: List<Pair<Int, Int>>?, root: RootLayout) {
        val page = pages.getOrNull(pager.page) ?: return
        root.locked = true
        page.sizes = { sizes }
        page.limits = { it ->
            if (it.kind != "appwidget") {
                null
            } else {
                io.github.kamradsmeshnyavy.launcher.core.AppWidgets.info(context, it.appWidgetId)?.let { info ->
                    // Пределы приложения (px) плюс поля карточки вокруг виджета.
                    val pad = context.dp(12)
                    io.github.kamradsmeshnyavy.launcher.core.AppWidgets.limits(context, info, it.w * page.board.cell, it.h * page.board.cellH)
                        .map { v -> if (v <= 0) 0 else v + pad }.toIntArray()
                }
            }
        }
        page.onResized = {
            root.locked = false
            Desk.save(context)
            rebuild()
        }
        page.resizing = item
    }

    private fun drop(page: DeskPage, x: Int, y: Int) {
        val item = page.moving ?: return
        Desk.Grid.move(page.space.items, item, x, y)
        page.moving = null
        movingRoot?.locked = false
        movingRoot = null
        Desk.save(context)
        rebuild()
    }

    // --- Прежний API --------------------------------------------------------------------

    fun setCard(left: Int, top: Int, right: Int, bottom: Int) {
        insets = intArrayOf(left, top, right, bottom)
        applyInsets()
        invalidate()
    }

    private fun applyInsets() {
        val (left, top, right, bottom) = insets.toList()
        val side = context.dp(14)
        val dotsH = if (dots.childCount > 0) context.dp(22) else 0
        pages.forEach { it.board.setPadding(left + side, top + side, right + side, bottom + context.dp(14) + dotsH) }
        dots.setPadding(left, 0, right, bottom + context.dp(6))
    }

    fun setDim(value: Boolean) {
        dim = value
        invalidate()
    }

    fun updateWeather(now: Weather.Now?) {
        weather = now
        widgets.forEach { it.onWeather(now) }
    }

    fun refreshTodo() = widgets.forEach { it.refresh() }

    fun updateClock(animate: Boolean) = widgets.forEach { it.onClock(animate) }

    fun updateBattery(level: Int, charging: Boolean) {
        battery = level to charging
        widgets.forEach { it.onBattery(level, charging) }
    }

    fun updateAlarm(text: String?) {
        alarm = text
        widgets.forEach { it.onAlarm(text) }
    }

    fun updateUnread(packages: List<String>, style: IconStyle) {
        unread = packages to style
        widgets.forEach { it.onUnread(packages, style) }
    }

    fun setFavorites(entries: List<Entry>, icons: Boolean, style: IconStyle) {
        favourites = Triple(entries, icons, style)
        widgets.forEach { it.onFavorites(entries, icons, style) }
    }

    /** Под (rawX, rawY) нет ничего нажимаемого: пустое место стола. */
    fun blankAt(rawX: Float, rawY: Float): Boolean {
        val page = pages.getOrNull(pager.page) ?: return true
        return !touchable(page, rawX, rawY)
    }

    private fun touchable(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        if (rawX < at[0] || rawX >= at[0] + view.width || rawY < at[1] || rawY >= at[1] + view.height) return false
        if (view is android.view.ViewGroup) {
            for (i in view.childCount - 1 downTo 0) if (touchable(view.getChildAt(i), rawX, rawY)) return true
        }
        return view !is DeskPage && (view.isClickable || view.isLongClickable)
    }

    /** Вертикальный жест из (rawX, rawY) прокручивает стол или виджет, а не открывает ящик. */
    fun favoritesScrollAt(rawX: Float, rawY: Float, dy: Float): Boolean =
        pages.getOrNull(pager.page)?.scrollableAt(rawX, rawY, dy) == true

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (dim) {
            canvas.drawColor(withAlpha(Nihil.VOID, 0.55f))
            return
        }
        // Мягкая тень внизу (цвета фона темы), чтобы избранное читалось на любых обоях.
        val cardEnd = h - insets[3]
        val bottomStart = cardEnd - h * 0.5f
        val key = "$h ${insets[3]}"
        if (key != shadeFor) {
            shadeFor = key
            bottomShade = LinearGradient(0f, bottomStart, 0f, cardEnd, withAlpha(Nihil.VOID, 0f), withAlpha(Nihil.VOID, 0.65f), Shader.TileMode.CLAMP)
        }
        scrim.shader = bottomShade
        canvas.drawRect(0f, bottomStart, w, h, scrim)
    }

    /** Для Glyph и др.: видна ли сейчас хоть одна часть стола. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) rebuildIfStale()
    }
}
