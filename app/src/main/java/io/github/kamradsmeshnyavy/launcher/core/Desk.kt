package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Рабочие столы, как воркспейсы Hyprland: у каждого свой набор виджетов и приложений.
 *
 * Элемент стола — виджет (свой: часы, погода… или приложения через AppWidgetHost),
 * приложение/сайт (значок), или «избранное» (список NIHIL). Раскладка — сеткой
 * [Grid.COLUMNS] × N (x, y, w, h в клетках) или стопкой (по [Item.order]; w ≥ 3 —
 * во всю ширину, иначе по две в ряд). Обе раскладки читают одни и те же элементы,
 * переключение в Customizer (deskLayout) ничего не теряет.
 *
 * Хранится в SharedPreferences "desk" одним JSON. Чистая логика (раскладка, миграция,
 * сериализация) — без Android, тесты на JVM.
 */
object Desk {
    const val FILE = "desk"
    private const val KEY = "desk"
    const val LAYOUT = "deskLayout"

    class Item(
        val id: String,
        /** widget | appwidget | app | favourites */
        val kind: String,
        /** Вид своего виджета (clock, weather…) или ключ приложения (Entry.key). */
        val type: String = "",
        val appWidgetId: Int = -1,
        var x: Int = 0,
        var y: Int = 0,
        var w: Int = 4,
        var h: Int = 2,
        var order: Int = 0,
        val options: JSONObject = JSONObject(),
    ) {
        val wide: Boolean get() = w >= 3

        fun json(): JSONObject = JSONObject()
            .put("id", id).put("kind", kind).put("type", type).put("appWidgetId", appWidgetId)
            .put("x", x).put("y", y).put("w", w).put("h", h).put("order", order).put("options", options)

        companion object {
            fun from(o: JSONObject) = Item(
                o.getString("id"), o.getString("kind"), o.optString("type"), o.optInt("appWidgetId", -1),
                o.optInt("x"), o.optInt("y"), o.optInt("w", 4).coerceIn(1, Grid.COLUMNS), o.optInt("h", 2).coerceAtLeast(1),
                o.optInt("order"), o.optJSONObject("options") ?: JSONObject(),
            )
        }
    }

    class Space(val id: String, var name: String, val items: MutableList<Item> = ArrayList()) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name)
            .put("items", JSONArray().apply { items.forEach { put(it.json()) } })

        companion object {
            fun from(o: JSONObject): Space {
                val a = o.optJSONArray("items") ?: JSONArray()
                return Space(o.getString("id"), o.optString("name"), MutableList(a.length()) { Item.from(a.getJSONObject(it)) })
            }
        }
    }

    class State(val spaces: MutableList<Space>, var current: Int = 0) {
        fun json(): JSONObject = JSONObject().put("current", current)
            .put("spaces", JSONArray().apply { spaces.forEach { put(it.json()) } })

        companion object {
            fun from(o: JSONObject): State {
                val a = o.optJSONArray("spaces") ?: JSONArray()
                val spaces = MutableList(a.length()) { Space.from(a.getJSONObject(it)) }
                if (spaces.isEmpty()) spaces += initial()
                return State(spaces, o.optInt("current").coerceIn(0, spaces.size - 1))
            }
        }
    }

    private var counter = 0L

    /** Новый id: время + счётчик, уникален в пределах телефона. */
    fun newId(prefix: String): String = prefix + "-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (counter++).toString(36)

    /**
     * Первый стол — то, что было на домашнем экране NIHIL: часы (с погодой, батареей,
     * будильником), «сейчас играет», список дел и избранное. Вид не меняется.
     */
    fun initial(): Space = Space(
        "space-1", "1",
        mutableListOf(
            Item("w-clock", "widget", "clock", x = 0, y = 0, w = 4, h = 2, order = 0),
            Item("w-media", "widget", "media", x = 0, y = 2, w = 4, h = 1, order = 1),
            Item("w-todo", "widget", "todo", x = 0, y = 3, w = 4, h = 2, order = 2),
            Item("w-favourites", "favourites", "", x = 0, y = 5, w = 4, h = 3, order = 3),
        ),
    )

    // --- Хранение ------------------------------------------------------------------------

    @Volatile
    private var cached: State? = null

    fun state(context: Context): State =
        cached ?: synchronized(this) {
            cached ?: run {
                val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                val text = sp.getString(KEY, null)
                (text?.let { runCatching { State.from(JSONObject(it)) }.getOrNull() } ?: State(mutableListOf(initial())))
            }.also { cached = it }
        }

    fun save(context: Context) {
        write(context)
        version++
    }

    /** Запомнить текущий стол: это не правка, перестраивать столы не нужно. */
    fun saveCurrent(context: Context, index: Int) {
        state(context).current = index
        write(context)
    }

    /** Сохранить состояние виджета (секундомер, таймер): вид не меняется, перестраивать не нужно. */
    fun saveQuiet(context: Context) = write(context)

    private fun write(context: Context) {
        val s = cached ?: return
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, s.json().toString()).apply()
    }

    /** Растёт при каждой правке: дом перестраивается, если его версия старше. */
    @Volatile
    var version = 0
        private set

    fun layoutMode(context: Context): String = Look.current(context).str(LAYOUT, "stack")

    // --- Правки ------------------------------------------------------------------------------

    fun addSpace(context: Context, name: String): Int {
        val s = state(context)
        s.spaces += Space(newId("space"), name)
        save(context)
        return s.spaces.size - 1
    }

    fun removeSpace(context: Context, index: Int): List<Item> {
        val s = state(context)
        if (s.spaces.size <= 1 || index !in s.spaces.indices) return emptyList()
        val gone = s.spaces.removeAt(index).items
        if (s.current > index) s.current--
        s.current = s.current.coerceIn(0, s.spaces.size - 1)
        save(context)
        return gone
    }

    fun moveSpace(context: Context, from: Int, to: Int) {
        val s = state(context)
        if (from !in s.spaces.indices || to !in s.spaces.indices) return
        s.spaces.add(to, s.spaces.removeAt(from))
        save(context)
    }

    /**
     * Кладёт [item] на стол [space]: в сетке — в первое свободное место в пределах [Grid.rows]
     * (нет места — на следующий стол, где есть, или на новый), в стопке — в конец.
     * Возвращает номер стола, куда лёг элемент.
     */
    fun add(context: Context, space: Int, item: Item): Int {
        val s = state(context)
        if (space !in s.spaces.indices) return space
        val rows = if (layoutMode(context) == "grid") Grid.rows else 0
        var target = space
        if (rows > 0) {
            item.h = item.h.coerceAtMost(rows)
            val order = listOf(space) + s.spaces.indices.filter { it > space }
            target = order.firstOrNull { Grid.place(s.spaces[it].items, item.w, item.h, rows) != null } ?: run {
                s.spaces += Space(newId("space"), (s.spaces.size + 1).toString())
                s.spaces.size - 1
            }
        }
        val items = s.spaces[target].items
        val spot = if (rows > 0) Grid.place(items, item.w, item.h, rows) else Grid.firstFree(items, item.w, item.h)
        item.x = spot?.first ?: 0
        item.y = spot?.second ?: Grid.bottom(items)
        item.order = (items.maxOfOrNull { it.order } ?: -1) + 1
        items += item
        save(context)
        return target
    }

    /**
     * Сетка не прокручивается: всё должно влезть в [rows] строк. Уплотняет столы; что не
     * влезло — на свободное место того же стола, иначе на новый стол в конце (ничего не
     * теряется). true — что-то поменялось.
     */
    fun fit(context: Context, rows: Int): Boolean {
        val s = state(context)
        val before = s.json().toString()
        var i = 0
        while (i < s.spaces.size) {
            // Не влезло — на следующий стол, где есть место, иначе на новый в конце.
            for (item in Grid.fit(s.spaces[i].items, rows)) {
                val space = s.spaces.drop(i + 1).firstOrNull { Grid.place(it.items, item.w, item.h, rows) != null }
                    ?: Space(newId("space"), (s.spaces.size + 1).toString()).also { s.spaces += it }
                val (x, y) = Grid.place(space.items, item.w, item.h, rows) ?: (0 to 0)
                item.x = x
                item.y = y
                space.items += item
            }
            i++
        }
        if (s.json().toString() == before) return false
        save(context)
        return true
    }

    fun remove(context: Context, id: String): Item? {
        for (space in state(context).spaces) {
            val i = space.items.indexOfFirst { it.id == id }
            if (i >= 0) {
                val gone = space.items.removeAt(i)
                save(context)
                return gone
            }
        }
        return null
    }

    fun find(context: Context, id: String): Pair<Int, Item>? {
        state(context).spaces.forEachIndexed { s, space -> space.items.firstOrNull { it.id == id }?.let { return s to it } }
        return null
    }

    // --- Сетка ----------------------------------------------------------------------------------

    object Grid {
        const val COLUMNS = 4

        /** Сколько строк влезает на экран (считает страница стола; 0 — ещё неизвестно). */
        @Volatile
        var rows = 0

        /** Свободное место [w] × [h] целиком внутри [rows] строк. */
        fun place(items: List<Item>, w: Int, h: Int, rows: Int): Pair<Int, Int>? {
            if (h > rows || w > COLUMNS) return null
            for (y in 0..(rows - h)) for (x in 0..(COLUMNS - w)) if (free(items, x, y, w, h)) return x to y
            return null
        }

        /** Уплотнить и уложить в [rows] строк; что не влезло — убрано из [items] и возвращено. */
        fun fit(items: MutableList<Item>, rows: Int): List<Item> {
            compact(items)
            val out = ArrayList<Item>()
            for (item in items.filter { it.y + it.h > rows }.sortedWith(compareBy({ it.y }, { it.x }))) {
                items.remove(item)
                item.h = item.h.coerceAtMost(rows)
                val spot = place(items, item.w, item.h, rows)
                if (spot == null) {
                    out += item
                } else {
                    item.x = spot.first
                    item.y = spot.second
                    items += item
                }
            }
            return out
        }

        fun overlaps(a: Item, x: Int, y: Int, w: Int, h: Int): Boolean =
            a.x < x + w && x < a.x + a.w && a.y < y + h && y < a.y + a.h

        fun free(items: List<Item>, x: Int, y: Int, w: Int, h: Int, except: String? = null): Boolean =
            x >= 0 && y >= 0 && x + w <= COLUMNS && items.none { it.id != except && overlaps(it, x, y, w, h) }

        fun bottom(items: List<Item>): Int = items.maxOfOrNull { it.y + it.h } ?: 0

        /** Первое свободное место размером [w] × [h], сверху вниз, слева направо (до низа + 1). */
        fun firstFree(items: List<Item>, w: Int, h: Int, rows: Int = bottom(items) + h): Pair<Int, Int>? {
            for (y in 0..rows) for (x in 0..(COLUMNS - w)) if (free(items, x, y, w, h)) return x to y
            return null
        }

        /**
         * [item] на (x, y): если место занято, мешающие сдвигаются вниз (как в лаунчерах Android),
         * каскадом; true — получилось.
         */
        fun move(items: MutableList<Item>, item: Item, x: Int, y: Int): Boolean {
            val nx = x.coerceIn(0, COLUMNS - item.w)
            val ny = y.coerceAtLeast(0)
            item.x = nx
            item.y = ny
            push(items, item)
            return true
        }

        fun resize(items: MutableList<Item>, item: Item, w: Int, h: Int) {
            item.w = w.coerceIn(1, COLUMNS)
            item.h = h.coerceAtLeast(1)
            item.x = item.x.coerceAtMost(COLUMNS - item.w)
            push(items, item)
        }

        /** Всё, что перекрывает [fixed], — ниже его, и так дальше, пока перекрытий нет. */
        private fun push(items: MutableList<Item>, fixed: Item) {
            val queue = ArrayDeque<Item>()
            queue += fixed
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 1000) {
                val top = queue.removeFirst()
                for (other in items) {
                    if (other === top || other.id == fixed.id) continue
                    if (overlaps(other, top.x, top.y, top.w, top.h)) {
                        other.y = top.y + top.h
                        queue += other
                    }
                }
            }
        }

        /** Подтянуть всё вверх, пока есть место (после удаления). */
        fun compact(items: MutableList<Item>) {
            for (item in items.sortedWith(compareBy({ it.y }, { it.x }))) {
                while (item.y > 0 && free(items, item.x, item.y - 1, item.w, item.h, except = item.id)) item.y--
            }
        }
    }

    // --- Стопка ----------------------------------------------------------------------------------

    /**
     * Строки стопки: широкие элементы — по одному, узкие — по два, по порядку [Item.order].
     */
    fun stackRows(items: List<Item>): List<List<Item>> {
        val rows = ArrayList<List<Item>>()
        var half: Item? = null
        for (item in items.sortedBy { it.order }) {
            if (item.wide) {
                half?.let { rows += listOf(it); half = null }
                rows += listOf(item)
            } else if (half == null) {
                half = item
            } else {
                rows += listOf(half!!, item)
                half = null
            }
        }
        half?.let { rows += listOf(it) }
        return rows
    }

    /** В стопке: [item] на одно место выше/ниже по порядку. */
    fun shift(items: MutableList<Item>, item: Item, by: Int) {
        val sorted = items.sortedBy { it.order }.toMutableList()
        val from = sorted.indexOf(item)
        if (from < 0) return
        val to = (from + by).coerceIn(0, sorted.size - 1)
        sorted.add(to, sorted.removeAt(from))
        sorted.forEachIndexed { i, it -> it.order = i }
    }
}
