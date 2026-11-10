package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import org.json.JSONObject

/**
 * Тема Omarchy + стиль Customizer: всё, что лаунчер берёт для цвета, формы, движения и шрифтов.
 *
 * Каталог (темы, стили, схема настроек) лежит в assets/looks, его пишет tools/export-looks.py.
 * Выбор пользователя — в SharedPreferences "look": тема, стиль и значения настроек Customizer
 * под теми же ключами, что в его json (glyphColor, dotmatrixBg, clockStyle…). Снимок с ПК
 * («следовать ПК») лежит там же, в [PC_SNAPSHOT], и перекрывает локальный выбор.
 *
 * [current] пересчитывается при каждом изменении; [version] растёт, и активити, у которых
 * версия старше, пересоздаются (см. LookActivity).
 */
object Look {

    // --- Каталог -------------------------------------------------------------

    class Theme(val id: String, val name: String, val dark: Boolean, val colors: Map<String, Int>, val thumb: String?)

    class Style(val json: JSONObject) {
        val id: String = json.getString("id")
        val name: String = json.optString("name", id)
        val nameRu: String = json.optString("name_ru", name)
        val note: String = json.optString("note")
        val noteRu: String = json.optString("note_ru", note)
        val look: JSONObject = json.optJSONObject("look") ?: JSONObject()
        val themeDark: String? = json.optJSONObject("theme")?.optString("dark")?.ifEmpty { null }
        val themeLight: String? = json.optJSONObject("theme")?.optString("light")?.ifEmpty { null }
        val fonts: String = json.optString("fonts", "stock")
        val glyph: JSONObject = json.optJSONObject("glyph") ?: JSONObject()
        val sounds: JSONObject = json.optJSONObject("sounds") ?: JSONObject()
        val launcher: JSONObject = json.optJSONObject("launcher") ?: JSONObject()
        val widgets: JSONObject = json.optJSONObject("widgets") ?: JSONObject()
    }

    class Catalog(val themes: List<Theme>, val styles: List<Style>, val settings: JSONObject, val ru: Map<String, String>) {
        fun theme(id: String?): Theme? = themes.firstOrNull { it.id == id }
        fun style(id: String?): Style? = styles.firstOrNull { it.id == id }
    }

    @Volatile
    private var catalog: Catalog? = null

    fun catalog(context: Context): Catalog =
        catalog ?: synchronized(this) { catalog ?: loadCatalog(context.applicationContext).also { catalog = it } }

    private fun asset(context: Context, path: String): String? =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    private fun loadCatalog(context: Context): Catalog {
        val themes = ArrayList<Theme>()
        asset(context, "looks/themes.json")?.let { text ->
            val list = JSONObject(text).getJSONArray("themes")
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                themes += Theme(o.getString("id"), o.optString("name"), o.optString("mode") != "light", colors(o.getJSONObject("colors")), o.optString("thumb").ifEmpty { null })
            }
        }
        val styles = ArrayList<Style>()
        asset(context, "looks/styles.json")?.let { text ->
            val list = JSONObject(text).getJSONArray("styles")
            for (i in 0 until list.length()) styles += Style(list.getJSONObject(i))
        }
        val settings = asset(context, "looks/settings.json")?.let { JSONObject(it) } ?: JSONObject().put("tables", JSONArray())
        val ru = HashMap<String, String>()
        asset(context, "looks/i18n/ru.json")?.let { text ->
            val o = JSONObject(text)
            o.keys().forEach { ru[it] = o.getString(it) }
        }
        return Catalog(themes, styles, settings, ru)
    }

    fun colors(o: JSONObject): Map<String, Int> {
        val map = HashMap<String, Int>()
        o.keys().forEach { k -> parseColor(o.optString(k))?.let { map[k] = it } }
        return map
    }

    /** #rgb, #rrggbb, #aarrggbb → ARGB; null для всего остального. */
    fun parseColor(value: String?): Int? {
        val v = value?.trim()?.removePrefix("#") ?: return null
        val hex = when (v.length) {
            3 -> v.map { "$it$it" }.joinToString("")
            6, 8 -> v
            else -> return null
        }
        val n = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000 or n).toInt() else n.toInt()
    }

    // --- Выбор пользователя ----------------------------------------------------

    const val FILE = "look"
    const val THEME = "look.theme"
    const val STYLE = "look.style"
    const val FONT_PROFILE = "look.fontProfile"
    const val FOLLOW_SYSTEM = "look.followSystem"
    const val FOLLOW_PC = "look.followPc"
    const val PC_SNAPSHOT = "look.pc"
    const val DEFAULT_STYLE = "dotmatrix"
    const val DEFAULT_THEME = "nothing-steel"

    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Что получилось ----------------------------------------------------------

    /** Роли палитры, как в dotmatrix/Palette.qml: на них держится весь интерфейс. */
    class Palette(
        val bg: Int,
        val surface: Int,
        val raised: Int,
        val line: Int,
        val off: Int,
        val muted: Int,
        val dim: Int,
        val fg: Int,
        val signal: Int,
        val danger: Int,
        val accent: Int,
        val onSignal: Int,
        val dark: Boolean,
    )

    class Resolved(
        val theme: Theme?,
        val style: Style?,
        val palette: Palette,
        /** solid | glass | auto | brutal | glow | soft | paper | bevel */
        val material: String,
        val elevation: Int,
        val cornerScale: Float,
        val motion: Float,
        /** profile | shell | hand */
        val typeface: String,
        val texture: String?,
        /** Профиль шрифтов: stock | nothing | dot | pixel | dot-accents | dots-all | lumen | paper-ui | retro. */
        val fontProfile: String,
        /** Значения настроек Customizer: ключ → значение (стиль, затем свои правки, затем ПК). */
        val values: Map<String, Any>,
        /** Ночной режим системы, с которым это посчитано. */
        val night: Boolean = true,
    ) {
        fun bool(key: String, default: Boolean = false): Boolean = values[key] as? Boolean ?: default
        fun num(key: String, default: Double): Double = (values[key] as? Number)?.toDouble() ?: default
        fun str(key: String, default: String): String = values[key] as? String ?: default

        /** Цвет из настройки: роль темы (foreground, accent, urgent, color4, dotmatrix.fg…) или #hex. */
        fun color(key: String, default: String): Int = roleColor(str(key, default)) ?: roleColor(default) ?: palette.fg

        fun roleColor(name: String): Int? {
            parseColor(name)?.let { return it }
            return when (name) {
                "default" -> null
                "foreground", "fg", "dotmatrix.fg", "dotmatrix.lit" -> palette.fg
                "background", "bg", "dotmatrix.bg" -> palette.bg
                "accent", "dotmatrix.signal" -> palette.signal
                "urgent", "danger", "dotmatrix.danger" -> palette.danger
                "muted", "dotmatrix.muted" -> palette.muted
                "dim", "dotmatrix.dim" -> palette.dim
                "line", "dotmatrix.line" -> palette.line
                "surface", "dotmatrix.surface" -> palette.surface
                else -> theme?.colors?.get(name)
            }
        }

        val glass: Boolean get() = material == "glass" || (material == "auto" && !palette.dark)
    }

    @Volatile
    var version = 0
        private set

    @Volatile
    private var resolved: Resolved? = null

    private val listeners = ArrayList<() -> Unit>()

    fun current(context: Context): Resolved {
        resolved?.let { r ->
            // Ночной режим сменился, пока нас не было: светлая/тёмная пара стиля пересчитывается.
            if (r.night == systemDark(context)) return r
            invalidate(context)
            return resolved!!
        }
        return synchronized(this) { resolved ?: resolve(context.applicationContext).also { resolved = it } }
    }

    /** Без контекста: то, что уже посчитано (или монохром апстрима до первого вызова с контекстом). */
    val now: Resolved get() = resolved ?: FALLBACK

    fun listen(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    fun unlisten(listener: () -> Unit) = synchronized(listeners) { listeners -= listener }

    /** Пересчитать после любой правки выбора; слушатели и активити узнают по [version]. */
    fun invalidate(context: Context) {
        synchronized(this) {
            resolved = resolve(context.applicationContext)
            version++
        }
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun resolve(context: Context): Resolved {
        val cat = catalog(context)
        val sp = prefs(context)
        val pc = if (sp.getBoolean(FOLLOW_PC, false)) sp.getString(PC_SNAPSHOT, null)?.let { runCatching { JSONObject(it) }.getOrNull() } else null

        val styleId = pc?.optJSONObject("style")?.optString("id")?.takeIf { cat.style(it) != null }
            ?: sp.getString(STYLE, DEFAULT_STYLE)
        val style = cat.style(styleId) ?: cat.style(DEFAULT_STYLE) ?: cat.styles.firstOrNull()

        // Значения: стиль → свои правки → ПК.
        val values = HashMap<String, Any>()
        style?.let { s ->
            putAll(values, s.glyph)
            putAll(values, s.sounds)
            putAll(values, s.widgets)
            s.launcher.optDouble("backdrop").takeIf { !it.isNaN() }?.let { values["backdrop"] = it }
        }
        sp.all.forEach { (k, v) -> if (!k.startsWith("look.") && v != null) values[k] = v }
        pc?.let { o ->
            listOf("glyph", "sounds", "dotmatrix", "widgets").forEach { part -> o.optJSONObject(part)?.let { putAll(values, it) } }
        }

        // Тема: с ПК (палитрой целиком), своя, или пара светлая/тёмная стиля.
        val pcTheme = pc?.optJSONObject("theme")?.let { t ->
            t.optJSONObject("colors")?.let { Theme(t.optString("id", "pc"), t.optString("name", "PC"), t.optString("mode") != "light", colors(it), null) }
        }
        var theme = pcTheme ?: cat.theme(sp.getString(THEME, null)) ?: cat.theme(style?.themeDark) ?: cat.theme(DEFAULT_THEME)
        if (pcTheme == null && sp.getBoolean(FOLLOW_SYSTEM, true) && theme != null && style != null) {
            // Тема — одна из пары стиля: светлая/тёмная идёт за системой.
            val pair = listOfNotNull(style.themeDark, style.themeLight)
            if (theme.id in pair) {
                val want = if (systemDark(context)) style.themeDark else style.themeLight
                cat.theme(want)?.let { theme = it }
            }
        }

        // «Стили: применять поверхности» выключено — материал и форма остаются от прежнего стиля.
        val lookStyle = sp.getString(LookEdit.LOOK_FROM, null)?.let { cat.style(it) } ?: style
        val look = JSONObject(lookStyle?.look?.toString() ?: "{}")
        pc?.optJSONObject("look")?.let { o -> o.keys().forEach { look.put(it, o.get(it)) } }
        val mono = (values["palette"] as? String) == "mono"
        val palette = palette(theme, mono, values)

        val typeface = look.optString("typeface", "profile")
        val fontProfile = when {
            typeface == "hand" -> "paper-ui"
            typeface == "shell" -> "stock"
            else -> sp.getString(FONT_PROFILE, null) ?: pc?.optString("fonts")?.ifEmpty { null } ?: style?.fonts ?: "stock"
        }
        return Resolved(
            theme = theme,
            style = style,
            palette = palette,
            material = look.optString("material", "solid"),
            elevation = look.optInt("elevation", 0),
            cornerScale = look.optDouble("cornerScale", 1.0).toFloat().coerceIn(0f, 2f),
            motion = look.optDouble("motion", 1.0).toFloat().coerceIn(0f, 3f),
            typeface = typeface,
            texture = look.optString("texture").ifEmpty { null },
            fontProfile = fontProfile,
            values = values,
            night = systemDark(context),
        )
    }

    private fun putAll(into: MutableMap<String, Any>, o: JSONObject) {
        o.keys().forEach { k ->
            when (val v = o.opt(k)) {
                is Boolean, is String -> into[k] = v
                is Number -> into[k] = v.toDouble()
            }
        }
    }

    // --- Палитра ------------------------------------------------------------------

    /** Монохром апстрима (NIHIL): черный, серые, красный. */
    val MONO = Palette(
        bg = 0xFF000000.toInt(), surface = 0xFF0E0E0E.toInt(), raised = 0xFF1A1A1A.toInt(), line = 0xFF2A2A2A.toInt(),
        off = 0xFF333333.toInt(), muted = 0xFF7A7A7A.toInt(), dim = 0xFFA3A3A3.toInt(), fg = 0xFFF2F2F2.toInt(),
        signal = 0xFFD71921.toInt(), danger = 0xFFD71921.toInt(), accent = 0xFFD71921.toInt(), onSignal = 0xFFF2F2F2.toInt(),
        dark = true,
    )

    private val FALLBACK = Resolved(null, null, MONO, "solid", 0, 1f, 1f, "profile", null, "stock", emptyMap())

    fun palette(theme: Theme?, mono: Boolean, values: Map<String, Any>): Palette {
        fun override(key: String): Int? = (values[key] as? String)?.takeIf { it != "default" }?.let { name ->
            parseColor(name) ?: theme?.colors?.get(name)
        }
        if (mono || theme == null) {
            val m = MONO
            return Palette(
                bg = override("dotmatrixBg") ?: m.bg, surface = override("dotmatrixSurface") ?: m.surface, raised = m.raised,
                line = override("dotmatrixLine") ?: m.line, off = m.off, muted = override("dotmatrixMuted") ?: m.muted,
                dim = override("dotmatrixDim") ?: m.dim, fg = override("dotmatrixFg") ?: m.fg,
                signal = override("dotmatrixSignal") ?: m.signal, danger = override("dotmatrixDanger") ?: m.danger,
                accent = m.accent, onSignal = m.onSignal, dark = true,
            )
        }
        val c = theme.colors
        val bg = override("dotmatrixBg") ?: c["background"] ?: MONO.bg
        val fg = override("dotmatrixFg") ?: c["foreground"] ?: MONO.fg
        val accent = c["accent"] ?: c["blue"] ?: fg
        val surface = override("dotmatrixSurface") ?: mix(bg, fg, 0.05f)
        return Palette(
            bg = bg,
            surface = surface,
            raised = mix(surface, fg, 0.06f),
            line = override("dotmatrixLine") ?: mix(bg, fg, 0.16f),
            off = mix(bg, fg, 0.22f),
            muted = override("dotmatrixMuted") ?: c["muted"] ?: mix(bg, fg, 0.48f),
            dim = override("dotmatrixDim") ?: mix(bg, fg, 0.66f),
            fg = fg,
            signal = override("dotmatrixSignal") ?: accent,
            danger = override("dotmatrixDanger") ?: c["urgent"] ?: c["red"] ?: MONO.danger,
            accent = accent,
            onSignal = if (luminance(accent) > 0.55f) mix(bg, 0xFF000000.toInt(), 0.6f) else bg.takeIf { luminance(it) < 0.3f } ?: 0xFFFFFFFF.toInt(),
            dark = theme.dark,
        )
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k + 0.5f).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun luminance(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
