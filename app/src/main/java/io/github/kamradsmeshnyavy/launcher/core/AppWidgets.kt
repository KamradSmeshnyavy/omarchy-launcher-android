package io.github.kamradsmeshnyavy.launcher.core

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.SparseIntArray

/**
 * Виджеты приложений: один AppWidgetHost на лаунчер (слушает, пока домашний экран виден),
 * размеры в клетках по minWidth/minHeight провайдера, и цвета Material You из темы.
 */
object AppWidgets {
    private const val HOST_ID = 0x0D0A

    @Volatile
    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: synchronized(this) {
            host ?: object : AppWidgetHost(context.applicationContext, HOST_ID) {
                override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): android.appwidget.AppWidgetHostView =
                    HostView(context)
            }.also { host = it }
        }

    /**
     * Вид чужого виджета: долгое нажатие ловится ещё на перехвате (кнопки виджета забирают
     * касания себе), как в Launcher3, — и отдаётся родителю (меню правки элемента стола).
     */
    class HostView(context: Context) : android.appwidget.AppWidgetHostView(context) {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        private val longPress = Runnable {
            fired = true
            (parent as? android.view.View)?.performLongClick()
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fired = false
                    downX = ev.x
                    downY = ev.y
                    postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(ev.x - downX) > slop || kotlin.math.abs(ev.y - downY) > slop) removeCallbacks(longPress)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return fired
        }

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            val handled = super.dispatchTouchEvent(ev)
            when (ev.actionMasked) {
                // Касание не взял ни виджет, ни его кнопки: остальное (и UP) уйдёт родителю мимо
                // перехвата — ждать долгое нажатие здесь нельзя, его ловит сама карточка стола.
                android.view.MotionEvent.ACTION_DOWN -> if (!handled) removeCallbacks(longPress)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return handled
        }

        /** Список виджета начал прокрутку: перехват больше не зовётся — долгое нажатие снять. */
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            if (disallowIntercept) removeCallbacks(longPress)
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                removeCallbacks(longPress)
                fired = false
            }
            return fired || super.onTouchEvent(event)
        }
    }

    fun manager(context: Context): AppWidgetManager = AppWidgetManager.getInstance(context)

    fun providers(context: Context): List<AppWidgetProviderInfo> =
        runCatching { manager(context).getInstalledProvidersForProfile(null) }.getOrDefault(emptyList())

    fun info(context: Context, id: Int): AppWidgetProviderInfo? = runCatching { manager(context).getAppWidgetInfo(id) }.getOrNull()

    fun delete(context: Context, id: Int) {
        if (id >= 0) runCatching { host(context).deleteAppWidgetId(id) }
    }

    /** Размер в клетках сетки по минимальному размеру провайдера (клетка ≈ 74 dp). */
    fun cells(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
        val d = context.resources.displayMetrics.density
        fun n(px: Int) = ((px / d + 16) / 74f).toInt().coerceIn(1, Desk.Grid.COLUMNS) + 0
        val w = if (info.targetCellWidth > 0) info.targetCellWidth else n(info.minWidth)
        val h = if (info.targetCellHeight > 0) info.targetCellHeight else n(info.minHeight)
        return w.coerceIn(1, Desk.Grid.COLUMNS) to h.coerceIn(1, 6)
    }

    /**
     * Пределы растяжения в px: minW, minH, maxW, maxH (0 — без предела). Если приложение не даёт
     * тянуть по оси (resizeMode), минимум и максимум по ней — текущий размер ([curW], [curH]).
     */
    fun limits(context: Context, info: AppWidgetProviderInfo, curW: Int, curH: Int): IntArray {
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minW = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val minH = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return intArrayOf(
            if (horizontal) minW else curW,
            if (vertical) minH else curH,
            if (horizontal) info.maxResizeWidth else curW,
            if (vertical) info.maxResizeHeight else curH,
        )
    }

    /**
     * Цвета Material You для виджетов (android.R.color.system_accent1..3_*, system_neutral1..2_*)
     * из палитры темы: акцент, смесь акцента и текста, опасный, и нейтральные — от фона.
     */
    fun colorResources(palette: Look.Palette): SparseIntArray {
        val out = SparseIntArray()
        fun put(prefix: String, ramp: IntArray) {
            WidgetTint.TONES.forEachIndexed { i, tone ->
                val id = runCatching { android.R.color::class.java.getField("${prefix}_$tone").getInt(null) }.getOrNull() ?: return@forEachIndexed
                out.put(id, ramp[i])
            }
        }
        put("system_accent1", WidgetTint.ramp(palette.signal))
        put("system_accent2", WidgetTint.ramp(Look.mix(palette.signal, palette.fg, 0.45f)))
        put("system_accent3", WidgetTint.ramp(palette.danger))
        put("system_neutral1", WidgetTint.ramp(palette.bg, WidgetTint.hsl(palette.bg).second.coerceAtMost(0.12f)))
        put("system_neutral2", WidgetTint.ramp(palette.surface, WidgetTint.hsl(palette.surface).second.coerceAtMost(0.18f)))
        return out
    }
}
