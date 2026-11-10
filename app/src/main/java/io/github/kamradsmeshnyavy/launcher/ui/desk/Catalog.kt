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
    class Spec(
        val type: String,
        val title: Int,
        val group: Int,
        /** Размеры, которые виджет умеет, как (w, h); первый — по умолчанию. */
        val sizes: List<Pair<Int, Int>>,
        val make: (Context, Desk.Item, DeskHost) -> DeskWidget,
    )

    private val specs = ArrayList<Spec>()

    fun register(spec: Spec) {
        specs.removeAll { it.type == spec.type }
        specs += spec
    }

    val all: List<Spec> get() = specs.toList()

    fun spec(type: String): Spec? = specs.firstOrNull { it.type == type }

    init {
        register(Spec("clock", R.string.w_clock_card, R.string.wg_time, listOf(4 to 3, 4 to 2)) { c, i, h -> ClockWidget(c, i, h) })
        register(Spec("media", R.string.w_media_card, R.string.wg_media, listOf(4 to 1, 4 to 2)) { c, i, h -> MediaWidget(c, i, h) })
        register(Spec("todo", R.string.w_todo, R.string.wg_work, listOf(4 to 2, 2 to 2, 4 to 3)) { c, i, h -> TodoWidget(c, i, h) })
    }

    /** Вид элемента стола; null — нечего показать (приложение удалено, виджет неизвестен). */
    fun make(context: Context, item: Desk.Item, host: DeskHost, extra: ((Context, Desk.Item, DeskHost) -> DeskWidget?)? = null): DeskWidget? =
        when (item.kind) {
            "favourites" -> FavouritesWidget(context, item, host)
            "widget" -> spec(item.type)?.make?.invoke(context, item, host)
            "app" -> entry(context, item.type)?.let { AppCellWidget(context, item, host, it) }
            else -> extra?.invoke(context, item, host)
        }

    private fun entry(context: Context, key: String) =
        Apps.find(key) ?: Prefs.get(context).webs.firstOrNull { it.key == key }
}
