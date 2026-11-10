package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Правки Look, как их делает Customizer: значение настройки, тема, стиль (с переключателями
 * «Применять из стиля» — STYLE_TWEAKS), свои пресеты (снимок всего, с PRESET_TWEAKS).
 * После каждой правки — [Look.invalidate].
 */
object LookEdit {
    const val PRESETS = "look.presets"
    const val FAVORITE_THEMES = "look.favThemes"
    const val LOOK_FROM = "look.lookFrom"
    const val DRAWER_SKIN = "drawerSkin"

    private fun sp(context: Context): SharedPreferences = Look.prefs(context)

    // --- Значения ---------------------------------------------------------------

    fun set(context: Context, key: String, value: Any) {
        sp(context).edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Number -> putFloat(key, value.toFloat())
                else -> putString(key, value.toString())
            }
        }.apply()
        Look.invalidate(context)
    }

    /** Своё значение убирается: снова как у стиля. */
    fun reset(context: Context, key: String) {
        sp(context).edit().remove(key).apply()
        Look.invalidate(context)
    }

    fun isOverridden(context: Context, key: String): Boolean = sp(context).contains(key)

    fun setTheme(context: Context, id: String?) {
        sp(context).edit().apply { if (id == null) remove(Look.THEME) else putString(Look.THEME, id) }.apply()
        Look.invalidate(context)
    }

    fun setFontProfile(context: Context, profile: String?) {
        sp(context).edit().apply { if (profile == null) remove(Look.FONT_PROFILE) else putString(Look.FONT_PROFILE, profile) }.apply()
        Look.invalidate(context)
    }

    fun favoriteThemes(context: Context): Set<String> = sp(context).getStringSet(FAVORITE_THEMES, null)?.toSet() ?: emptySet()

    fun toggleFavoriteTheme(context: Context, id: String) {
        val now = favoriteThemes(context)
        sp(context).edit().putStringSet(FAVORITE_THEMES, if (id in now) now - id else now + id).apply()
    }

    // --- Ключи разделов ------------------------------------------------------------

    fun tableKeys(context: Context, table: String): List<String> {
        val tables = Look.catalog(context).settings.optJSONArray("tables") ?: return emptyList()
        for (i in 0 until tables.length()) {
            val t = tables.getJSONObject(i)
            if (t.optString("id") != table) continue
            val items = t.getJSONArray("items")
            return (0 until items.length()).mapNotNull { items.getJSONObject(it).optString("key").ifEmpty { null } }
        }
        return emptyList()
    }

    /** Что из стиля применяется: переключатель STYLE_TWEAKS (по умолчанию да). */
    private fun applies(context: Context, key: String): Boolean = Look.current(context).bool(key, true)

    // --- Стиль ---------------------------------------------------------------------

    /**
     * Стиль, как в Customizer → Пресеты → Стиль: разделы с включённым «применять» берут значения
     * стиля (свои правки сбрасываются), остальные остаются как были (текущие значения
     * записываются своими). Тема — по styleTheme: current (той же яркости, что сейчас), dark,
     * light или off.
     */
    fun applyStyle(context: Context, styleId: String) {
        val cat = Look.catalog(context)
        val style = cat.style(styleId) ?: return
        val now = Look.current(context)
        val edit = sp(context).edit()

        fun section(apply: Boolean, keys: Collection<String>) {
            for (key in keys) {
                if (apply) {
                    edit.remove(key)
                } else {
                    when (val v = now.values[key]) {
                        is Boolean -> edit.putBoolean(key, v)
                        is Number -> edit.putFloat(key, v.toFloat())
                        is String -> edit.putString(key, v)
                    }
                }
            }
        }
        section(applies(context, "styleGlyph"), tableKeys(context, "GLYPH_TWEAKS") + style.glyph.keys().asSequence().toList())
        section(applies(context, "styleSounds"), tableKeys(context, "SOUND_TWEAKS") + style.sounds.keys().asSequence().toList())
        section(applies(context, "styleWidgets"), tableKeys(context, "WIDGET_TWEAKS") + style.widgets.keys().asSequence().toList())
        section(applies(context, "styleLauncher"), tableKeys(context, "LAUNCHER_TWEAKS") + DRAWER_SKIN)

        if (applies(context, "styleFonts")) edit.remove(Look.FONT_PROFILE)
        else edit.putString(Look.FONT_PROFILE, now.fontProfile)

        // Материал, тени, движение и скругления — от этого стиля или прежнего.
        if (applies(context, "styleLook")) edit.remove(LOOK_FROM)
        else edit.putString(LOOK_FROM, sp(context).getString(LOOK_FROM, null) ?: now.style?.id ?: styleId)

        when (now.str("styleTheme", "current")) {
            "off" -> Unit
            "dark" -> style.themeDark?.let { edit.putString(Look.THEME, it) }
            "light" -> (style.themeLight ?: style.themeDark)?.let { edit.putString(Look.THEME, it) }
            else -> (if (now.palette.dark) style.themeDark else style.themeLight ?: style.themeDark)?.let { edit.putString(Look.THEME, it) }
        }
        edit.putString(Look.STYLE, styleId)
        edit.apply()
        Look.invalidate(context)
    }

    // --- Свои пресеты -----------------------------------------------------------------

    class Preset(val name: String, val json: JSONObject)

    fun presets(context: Context): List<Preset> = runCatching {
        val a = JSONArray(sp(context).getString(PRESETS, "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Preset(o.getString("name"), o) } }
    }.getOrDefault(emptyList())

    /** Снимок: стиль, тема, профиль шрифтов и все свои значения (кроме служебных look.*). */
    fun savePreset(context: Context, name: String) {
        val all = sp(context).all
        val values = JSONObject()
        all.forEach { (k, v) -> if (!k.startsWith("look.") && v != null) values.put(k, v) }
        val o = JSONObject()
            .put("name", name)
            .put("style", all[Look.STYLE] ?: Look.DEFAULT_STYLE)
            .put("theme", all[Look.THEME] ?: JSONObject.NULL)
            .put("fonts", all[Look.FONT_PROFILE] ?: JSONObject.NULL)
            .put("values", values)
        val list = presets(context).filter { it.name != name }.map { it.json } + o
        sp(context).edit().putString(PRESETS, JSONArray(list).toString()).apply()
    }

    fun deletePreset(context: Context, name: String) {
        val list = presets(context).filter { it.name != name }.map { it.json }
        sp(context).edit().putString(PRESETS, JSONArray(list).toString()).apply()
    }

    /** Пресет целиком, кроме разделов, у которых «Пресеты: применять …» выключено. */
    fun applyPreset(context: Context, preset: Preset) {
        val now = Look.current(context)
        val o = preset.json
        val edit = sp(context).edit()
        val values = o.optJSONObject("values") ?: JSONObject()
        val glyph = tableKeys(context, "GLYPH_TWEAKS").toSet()
        val sounds = tableKeys(context, "SOUND_TWEAKS").toSet()
        val widgets = tableKeys(context, "WIDGET_TWEAKS").toSet()
        val launcher = tableKeys(context, "LAUNCHER_TWEAKS").toSet() + DRAWER_SKIN
        // Переключатели «применять из стиля/пресета» сами пресетом не меняются.
        val switches = tableKeys(context, "STYLE_TWEAKS").toSet() + tableKeys(context, "PRESET_TWEAKS")
        fun allowed(key: String): Boolean = when (key) {
            in switches -> false
            in glyph -> now.bool("presetGlyph", true)
            in sounds -> now.bool("presetSounds", true)
            in widgets -> now.bool("presetWidgets", true)
            in launcher -> now.bool("presetLauncher", true)
            else -> true
        }
        // Свои значения тех разделов, что применяются, уходят; их место — значения пресета.
        sp(context).all.keys.filter { !it.startsWith("look.") && allowed(it) }.forEach { edit.remove(it) }
        values.keys().forEach { k ->
            if (!allowed(k)) return@forEach
            when (val v = values.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Number -> edit.putFloat(k, v.toFloat())
                is String -> edit.putString(k, v)
            }
        }
        edit.putString(Look.STYLE, o.optString("style", Look.DEFAULT_STYLE))
        if (now.bool("presetTheme", true)) {
            o.optString("theme").takeIf { it.isNotEmpty() && it != "null" }?.let { edit.putString(Look.THEME, it) }
        }
        if (now.bool("presetFonts", true)) {
            val fonts = o.optString("fonts").takeIf { it.isNotEmpty() && it != "null" }
            if (fonts == null) edit.remove(Look.FONT_PROFILE) else edit.putString(Look.FONT_PROFILE, fonts)
        }
        if (now.bool("presetLook", true)) edit.remove(LOOK_FROM)
        edit.apply()
        Look.invalidate(context)
    }
}
