package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.Context
import io.github.kamradsmeshnyavy.launcher.R
import io.github.kamradsmeshnyavy.launcher.core.Apps
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Prefs

/**
 * Свои виджеты: вид, группа в выборе, размеры (в клетках сетки) и как сделать.
 * Новые виджеты добавляются сюда одной строкой.
 */
object Catalog {
    /** Настройка виджета: text | enum | bool | num | date. */
    class Opt(val key: String, val type: String, val title: Int, val values: List<String> = emptyList(), val default: Any = "", val min: Double = 0.0, val max: Double = 0.0)

    class Spec(
        val type: String,
        val title: Int,
        val group: Int,
        /** Размеры, которые виджет умеет, как (w, h); первый — по умолчанию. */
        val sizes: List<Pair<Int, Int>>,
        val options: List<Opt> = emptyList(),
        val make: (Context, Desk.Item, DeskHost) -> DeskWidget,
    )

    private val specs = ArrayList<Spec>()

    fun register(spec: Spec) {
        specs.removeAll { it.type == spec.type }
        specs += spec
    }

    val all: List<Spec> get() = specs.toList()

    fun spec(type: String): Spec? = specs.firstOrNull { it.type == type }

    /** Группы выбора, по порядку. */
    val GROUPS = listOf(R.string.wg_time, R.string.wg_weather, R.string.wg_media, R.string.wg_work, R.string.wg_nothing)

    init {
        // Время и дата.
        register(Spec("clock", R.string.w_clock_card, R.string.wg_time, listOf(4 to 3, 4 to 2)) { c, i, h -> ClockWidget(c, i, h) })
        register(Spec("dotclock", R.string.w_dotclock, R.string.wg_time, listOf(4 to 2, 2 to 1, 4 to 1, 2 to 2)) { c, i, h -> DotClockWidget(c, i, h) })
        register(Spec("analog", R.string.w_analog, R.string.wg_time, listOf(2 to 2, 3 to 3, 4 to 4)) { c, i, h -> AnalogWidget(c, i, h) })
        register(Spec("worldclock", R.string.w_worldclock, R.string.wg_time, listOf(4 to 2, 2 to 2),
            listOf(Opt("zones", "text", R.string.wo_zones, default = "Europe/London,America/New_York,Asia/Tokyo"))) { c, i, h -> WorldClockWidget(c, i, h) })
        register(Spec("month", R.string.w_month, R.string.wg_time, listOf(2 to 2, 4 to 3, 4 to 2)) { c, i, h -> MonthWidget(c, i, h) })
        register(Spec("alarm", R.string.w_alarm, R.string.wg_time, listOf(2 to 1, 2 to 2, 4 to 1)) { c, i, h -> AlarmWidget(c, i, h) })
        // Погода и батарея.
        register(Spec("weather", R.string.w_weather, R.string.wg_weather, listOf(4 to 2, 2 to 2, 4 to 3)) { c, i, h -> WeatherWidget(c, i, h) })
        register(Spec("battery", R.string.w_battery, R.string.wg_weather, listOf(2 to 2, 2 to 1, 4 to 1)) { c, i, h -> BatteryWidget(c, i, h) })
        register(Spec("btbattery", R.string.w_btbattery, R.string.wg_weather, listOf(4 to 2, 2 to 2)) { c, i, h -> BluetoothBatteryWidget(c, i, h) })
        // Медиа и фото.
        register(Spec("media", R.string.w_media_card, R.string.wg_media, listOf(4 to 1, 4 to 2)) { c, i, h -> MediaWidget(c, i, h) })
        register(Spec("cover", R.string.w_cover, R.string.wg_media, listOf(2 to 3, 4 to 4, 3 to 4)) { c, i, h -> CoverWidget(c, i, h) })
        register(Spec("photo", R.string.w_photo, R.string.wg_media, listOf(2 to 2, 4 to 3, 4 to 4),
            listOf(Opt("dotify", "bool", R.string.wo_dotify, default = false), Opt("interval", "num", R.string.wo_interval, default = 10.0, min = 1.0, max = 120.0))) { c, i, h -> PhotoWidget(c, i, h) })
        register(Spec("quote", R.string.w_quote, R.string.wg_media, listOf(4 to 2, 4 to 1)) { c, i, h -> QuoteWidget(c, i, h) })
        // Продуктивность и компьютер.
        register(Spec("todo", R.string.w_todo, R.string.wg_work, listOf(4 to 2, 2 to 2, 4 to 3),
            listOf(Opt("max", "num", R.string.wo_max, default = 8.0, min = 1.0, max = 20.0))) { c, i, h -> TodoWidget(c, i, h) })
        register(Spec("events", R.string.w_events, R.string.wg_work, listOf(4 to 2, 4 to 3, 2 to 2)) { c, i, h -> EventsWidget(c, i, h) })
        register(Spec("pc", R.string.w_pc, R.string.wg_work, listOf(4 to 3, 4 to 2)) { c, i, h -> PcWidget(c, i, h) })
        // Инструменты (в духе Nothing OS).
        register(Spec("toggles", R.string.w_toggles, R.string.wg_nothing, listOf(4 to 1, 2 to 1)) { c, i, h -> TogglesWidget(c, i, h) })
        register(Spec("compass", R.string.w_compass, R.string.wg_nothing, listOf(2 to 2, 3 to 3)) { c, i, h -> CompassWidget(c, i, h) })
        register(Spec("steps", R.string.w_steps, R.string.wg_nothing, listOf(2 to 2, 3 to 3),
            listOf(Opt("goal", "num", R.string.wo_goal, default = 8000.0, min = 1000.0, max = 30000.0))) { c, i, h -> StepsWidget(c, i, h) })
        register(Spec("countdown", R.string.w_countdown, R.string.wg_nothing, listOf(2 to 2, 4 to 2, 2 to 1),
            listOf(Opt("title", "text", R.string.wo_title), Opt("date", "date", R.string.wo_date))) { c, i, h -> CountdownWidget(c, i, h) })
        register(Spec("screentime", R.string.w_screen_time, R.string.wg_nothing, listOf(4 to 2, 4 to 3)) { c, i, h -> ScreenTimeWidget(c, i, h) })
    }

    /** Голос виджета — общая настройка: auto (по стилю), dots (DOT-MATRIX), style. */
    val VOICE = Opt("voice", "enum", R.string.wo_voice, listOf("auto", "dots", "style"), "auto")

    /** Вид элемента стола; null — нечего показать (приложение удалено, виджет неизвестен). */
    fun make(context: Context, item: Desk.Item, host: DeskHost, extra: ((Context, Desk.Item, DeskHost) -> DeskWidget?)? = null): DeskWidget? =
        when (item.kind) {
            "favourites" -> FavouritesWidget(context, item, host)
            "widget" -> spec(item.type)?.make?.invoke(context, item, host)
            "app" -> entry(context, item.type)?.let { AppCellWidget(context, item, host, it) }
            "appwidget" -> AppWidgetItem(context, item, host)
            else -> extra?.invoke(context, item, host)
        }

    private fun entry(context: Context, key: String) =
        Apps.find(key) ?: Prefs.get(context).webs.firstOrNull { it.key == key }
}
