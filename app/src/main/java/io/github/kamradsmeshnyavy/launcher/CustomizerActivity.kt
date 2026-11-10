package io.github.kamradsmeshnyavy.launcher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.LookEdit
import io.github.kamradsmeshnyavy.launcher.system.Glyph
import io.github.kamradsmeshnyavy.launcher.ui.Choice
import io.github.kamradsmeshnyavy.launcher.ui.KeyButton
import io.github.kamradsmeshnyavy.launcher.ui.LedSlider
import io.github.kamradsmeshnyavy.launcher.ui.MATCH
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.StylePreview
import io.github.kamradsmeshnyavy.launcher.ui.Swatch
import io.github.kamradsmeshnyavy.launcher.ui.ThemeSwatchView
import io.github.kamradsmeshnyavy.launcher.ui.Toggle
import io.github.kamradsmeshnyavy.launcher.ui.WRAP
import io.github.kamradsmeshnyavy.launcher.ui.body
import io.github.kamradsmeshnyavy.launcher.ui.chevron
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.fontSample
import io.github.kamradsmeshnyavy.launcher.ui.heading
import io.github.kamradsmeshnyavy.launcher.ui.llp
import io.github.kamradsmeshnyavy.launcher.ui.mono
import io.github.kamradsmeshnyavy.launcher.ui.rounded
import io.github.kamradsmeshnyavy.launcher.ui.rule
import io.github.kamradsmeshnyavy.launcher.ui.settingRow
import io.github.kamradsmeshnyavy.launcher.ui.text
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Customizer на телефоне: те же группы и настройки, что в оверлее Omarchy (Super+Shift+T).
 * Страницы — одна активити с [PAGE]: главная с поиском по всем настройкам, галереи стилей,
 * тем и пресетов, профили шрифтов и страницы таблиц Customizer, построенные по его схеме
 * (assets/looks/settings.json): bool — переключатель, enum — выбор, num — слайдер,
 * color — образцы ролей темы. Долгое нажатие на строку возвращает значение стиля.
 */
class CustomizerActivity : LookActivity() {

    private lateinit var column: LinearLayout
    private lateinit var catalog: Look.Catalog
    private val page by lazy { intent.getStringExtra(PAGE) ?: HOME }
    private val ru by lazy { Locale.getDefault().language == "ru" }
    private val pool = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = Look.catalog(this)
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Nihil.VOID)
            addView(column, llp(MATCH, WRAP))
        }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(24), bars.top + dp(28), bars.right + dp(24), bars.bottom + dp(40))
            insets
        }
        build()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun tr(en: String): String = if (ru) catalog.ru[en] ?: en else en

    private fun open(page: String) = startActivity(Intent(this, CustomizerActivity::class.java).putExtra(PAGE, page))

    private fun build() {
        when {
            page == HOME -> home()
            page == STYLES -> styles()
            page == THEMES -> themes()
            page == PRESETS -> presets()
            page == FONTS -> fonts()
            page == WALLPAPERS -> wallpapers()
            page == WEATHER -> weather()
            page == BAR -> barPage()
            page == LOCK -> lockPage()
            page == SPACES -> spacesPage()
            page == SOUNDS -> soundsPage()
            page.startsWith(TABLE) -> table(page.removePrefix(TABLE))
            page.startsWith(ADD) -> addWidget(page.removePrefix(ADD).toIntOrNull() ?: 0)
            page.startsWith(APP_WIDGETS) -> appWidgets(page.removePrefix(APP_WIDGETS).toIntOrNull() ?: 0)
            page.startsWith(ITEM) -> itemOptions(page.removePrefix(ITEM))
        }
    }

    // --- Главная ---------------------------------------------------------------------

    private fun home() {
        val look = Look.current(this)
        column.addView(text("Customizer", 30f, Nihil.INK, Nihil.fonts(this).dot), llp(WRAP, WRAP))
        val summary = listOfNotNull(
            look.style?.let { if (ru) it.nameRu else it.name },
            look.theme?.name,
            look.fontProfile,
        ).joinToString(" · ")
        column.addView(mono(summary, 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(6) })

        // Поиск по всем настройкам: под полем — найденные строки, вместо групп.
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val groups = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(this).apply {
            hint = getString(R.string.cz_search)
            setHintTextColor(Nihil.MUTED)
            setTextColor(Nihil.INK)
            typeface = Nihil.fonts(context).sans
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    groups.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                    results.removeAllViews()
                    if (q.isNotEmpty()) searchInto(results, q)
                }
            })
        }
        column.addView(search, llp(MATCH, WRAP).apply { topMargin = dp(20) })
        column.addView(results, llp(MATCH, WRAP))
        column.addView(groups, llp(MATCH, WRAP))

        fun group(title: Int, sub: Int, rows: List<Triple<Int, Int, String>>) {
            groups.addView(heading(getString(title), getString(sub)))
            rows.forEach { (t, s, target) ->
                groups.addView(settingRow(getString(t), getString(s), chevron()) { open(target) })
            }
        }
        group(
            R.string.cz_presets, R.string.cz_presets_sub,
            listOf(
                Triple(R.string.cz_styles, R.string.cz_styles_sub, STYLES),
                Triple(R.string.cz_my_presets, R.string.cz_my_presets_sub, PRESETS),
                Triple(R.string.cz_style_apply, R.string.cz_style_apply_sub, TABLE + "STYLE_TWEAKS"),
            ),
        )
        group(
            R.string.cz_desktop, R.string.cz_desktop_sub,
            listOf(
                Triple(R.string.cz_themes, R.string.cz_themes_sub, THEMES),
                Triple(R.string.cz_walls, R.string.cz_walls_sub, WALLPAPERS),
                Triple(R.string.cz_lock, R.string.cz_lock_sub, LOCK),
                Triple(R.string.cz_weather, R.string.cz_weather_sub, WEATHER),
                Triple(R.string.cz_widgets, R.string.cz_widgets_sub, TABLE + "WIDGET_TWEAKS"),
                Triple(R.string.cz_dotify, R.string.cz_dotify_sub, TABLE + "DOTIFY_TWEAKS"),
                Triple(R.string.cz_dotmatrix, R.string.cz_dotmatrix_sub, TABLE + "DOTMATRIX_TWEAKS"),
            ),
        )
        group(
            R.string.cz_light_sound, R.string.cz_light_sound_sub,
            listOf(
                Triple(R.string.cz_glyph, R.string.cz_glyph_sub, TABLE + "GLYPH_TWEAKS"),
                Triple(R.string.cz_sounds, R.string.cz_sounds_sub, SOUNDS),
            ),
        )
        group(
            R.string.cz_type_launcher, R.string.cz_type_launcher_sub,
            listOf(
                Triple(R.string.cz_fonts, R.string.cz_fonts_sub, FONTS),
                Triple(R.string.cz_bar, R.string.cz_bar_sub, BAR),
                Triple(R.string.desk_spaces, R.string.desk_spaces_sub, SPACES),
                Triple(R.string.cz_launcher, R.string.cz_launcher_sub, TABLE + "LAUNCHER_TWEAKS"),
            ),
        )
        groups.addView(heading(getString(R.string.cz_pc), getString(R.string.cz_pc_sub)))
        val sp = Look.prefs(this)
        val paired = io.github.kamradsmeshnyavy.launcher.deck.DeckStore.get(this).computer
        groups.addView(switchRow(
            getString(R.string.cz_follow_pc),
            getString(R.string.cz_follow_pc_sub) + if (paired == null) "\n" + getString(R.string.cz_follow_pc_unpaired) else "\n" + paired.name,
            sp.getBoolean(Look.FOLLOW_PC, false),
        ) { on ->
            io.github.kamradsmeshnyavy.launcher.core.PcLook.setEnabled(this, on)
            if (!on) applyLook()
        })
        groups.addView(switchRow(getString(R.string.cz_follow_system), getString(R.string.cz_follow_system_sub), sp.getBoolean(Look.FOLLOW_SYSTEM, true)) { on ->
            sp.edit().putBoolean(Look.FOLLOW_SYSTEM, on).apply()
            applyLook()
        })
    }

    private fun searchInto(into: LinearLayout, query: String) {
        val q = query.lowercase()
        var found = 0
        // Страницы.
        val pages = listOf(
            R.string.cz_styles to STYLES, R.string.cz_themes to THEMES, R.string.cz_my_presets to PRESETS, R.string.cz_fonts to FONTS,
        )
        pages.forEach { (title, target) ->
            if (getString(title).lowercase().contains(q)) {
                into.addView(settingRow(getString(title), null, chevron()) { open(target) })
                found++
            }
        }
        // Строки всех таблиц.
        forEachTable { _, items ->
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val key = item.optString("key")
                if (key.isEmpty()) continue
                val label = item.optString("label")
                if (!tr(label).lowercase().contains(q) && !label.lowercase().contains(q) && !key.lowercase().contains(q)) continue
                into.addView(control(item))
                found++
                if (found > 40) return@forEachTable
            }
        }
        if (found == 0) into.addView(mono(getString(R.string.cz_nothing_found), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(16) })
    }

    private inline fun forEachTable(block: (String, org.json.JSONArray) -> Unit) {
        val tables = catalog.settings.optJSONArray("tables") ?: return
        for (t in 0 until tables.length()) {
            val table = tables.getJSONObject(t)
            block(table.getString("id"), table.getJSONArray("items"))
        }
    }

    // --- Стили -----------------------------------------------------------------------------

    private fun styles() {
        column.addView(heading(getString(R.string.cz_styles), getString(R.string.cz_styles_page_sub)))
        val current = Look.current(this).style?.id
        val grid = grid(2)
        catalog.styles.forEach { style ->
            val theme = catalog.theme(if (Nihil.DARK) style.themeDark else style.themeLight ?: style.themeDark)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(StylePreview(context, style, theme), llp(MATCH, WRAP))
                val name = if (ru) style.nameRu else style.name
                addView(text(name + if (style.id == current) "  ●" else "", 14f, if (style.id == current) Nihil.SIGNAL else Nihil.INK, Nihil.fonts(context).sans), llp(MATCH, WRAP).apply { topMargin = dp(8) })
                addView(mono(style.look.optString("material") + " · ×" + style.look.optDouble("cornerScale", 1.0), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(2) })
                setPadding(0, 0, 0, dp(18))
                setOnClickListener {
                    LookEdit.applyStyle(context, style.id)
                    toast(getString(R.string.cz_style_applied, name))
                    applyLook()
                }
                setOnLongClickListener {
                    Toast.makeText(context, if (ru) style.noteRu else style.note, Toast.LENGTH_LONG).show()
                    true
                }
            }
            grid(cell)
        }
        column.addView(body(getString(R.string.cz_styles_hint)), llp(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    // --- Темы ---------------------------------------------------------------------------------

    private fun themes() {
        column.addView(heading(getString(R.string.cz_themes), getString(R.string.cz_themes_page_sub)))
        val current = Look.current(this).theme?.id
        val favorites = LookEdit.favoriteThemes(this)
        val list = catalog.themes.sortedWith(compareBy({ it.id !in favorites }, { it.name.lowercase() }))
        val filter = EditText(this).apply {
            hint = getString(R.string.cz_search)
            setHintTextColor(Nihil.MUTED)
            setTextColor(Nihil.INK)
            typeface = Nihil.fonts(context).sans
            textSize = 15f
            setSingleLine()
            background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(filter, llp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(holder, llp(MATCH, WRAP))
        val cells = ArrayList<Pair<Look.Theme, View>>()
        fun fill(q: String) {
            holder.removeAllViews()
            val g = grid(2, holder)
            cells.filter { q.isEmpty() || it.first.name.lowercase().contains(q) || it.first.id.contains(q) }.forEach { g(it.second) }
        }
        list.forEach { theme ->
            val swatch = ThemeSwatchView(this, theme)
            theme.thumb?.let { path ->
                pool.execute {
                    val bmp = runCatching {
                        assets.open("looks/$path").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                    }.getOrNull()
                    if (bmp != null) swatch.post { swatch.bitmap = bmp }
                }
            }
            val star = if (theme.id in favorites) "★ " else ""
            val on = theme.id == current
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(swatch, llp(MATCH, WRAP))
                addView(text(star + theme.name, 13f, if (on) Nihil.SIGNAL else Nihil.INK, Nihil.fonts(context).sans).apply { maxLines = 1 }, llp(MATCH, WRAP).apply { topMargin = dp(6) })
                addView(mono(if (theme.dark) getString(R.string.cz_dark) else getString(R.string.cz_light), 10f, Nihil.MUTED))
                setPadding(0, 0, 0, dp(16))
                setOnClickListener {
                    LookEdit.setTheme(context, theme.id)
                    applyLook()
                }
                setOnLongClickListener {
                    LookEdit.toggleFavoriteTheme(context, theme.id)
                    toast(if (theme.id in favorites) getString(R.string.cz_unstarred) else getString(R.string.cz_starred))
                    restart()
                    true
                }
            }
            cells += theme to cell
        }
        fill("")
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = fill(s?.toString()?.trim()?.lowercase().orEmpty())
        })
    }

    // --- Свои пресеты -----------------------------------------------------------------------

    private fun presets() {
        column.addView(heading(getString(R.string.cz_my_presets), getString(R.string.cz_presets_page_sub)))
        val name = EditText(this).apply {
            hint = getString(R.string.cz_preset_name)
            setHintTextColor(Nihil.MUTED)
            setTextColor(Nihil.INK)
            typeface = Nihil.fonts(context).sans
            setSingleLine()
            background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val save = KeyButton(this, getString(R.string.cz_preset_save), accent = true).apply {
            setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setOnClickListener
                LookEdit.savePreset(context, n)
                restart()
            }
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, llp(0, WRAP, 1f))
            addView(save, llp(WRAP, WRAP).apply { marginStart = dp(12) })
        }, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        val list = LookEdit.presets(this)
        if (list.isEmpty()) column.addView(mono(getString(R.string.cz_no_presets), 11f, Nihil.MUTED), llp(WRAP, WRAP).apply { topMargin = dp(8) })
        list.forEach { preset ->
            val o = preset.json
            val styleName = catalog.style(o.optString("style"))?.let { if (ru) it.nameRu else it.name } ?: o.optString("style")
            val themeName = catalog.theme(o.optString("theme"))?.name ?: "—"
            val delete = KeyButton(this, getString(R.string.cz_delete), danger = true).apply {
                setOnClickListener {
                    LookEdit.deletePreset(context, preset.name)
                    restart()
                }
            }
            column.addView(settingRow("★ " + preset.name, "$styleName · $themeName", delete) {
                LookEdit.applyPreset(this, preset)
                applyLook()
            })
        }
        column.addView(heading(getString(R.string.cz_preset_apply), null))
        table("PRESET_TWEAKS", withHeading = false)
    }

    // --- Обои тем ------------------------------------------------------------------------------

    private fun wallpapers() {
        val look = Look.current(this)
        val theme = look.theme
        column.addView(heading(getString(R.string.cz_walls), getString(R.string.cz_walls_page_sub)))
        if (!io.github.kamradsmeshnyavy.launcher.core.ThemeWalls.paired(this)) {
            column.addView(body(getString(R.string.cz_walls_unpaired)), llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
            column.addView(KeyButton(this, getString(R.string.deck_pair), accent = true).apply {
                setOnClickListener { startActivity(Intent(context, DeckActivity::class.java)) }
            }, llp(WRAP, WRAP))
            return
        }
        val sp = Look.prefs(this)
        val walls = io.github.kamradsmeshnyavy.launcher.core.ThemeWalls
        column.addView(switchRow(getString(R.string.cz_walls_follow), getString(R.string.cz_walls_follow_sub), sp.getBoolean(walls.FOLLOW, true)) {
            sp.edit().putBoolean(walls.FOLLOW, it).apply()
        })
        fun choice(title: Int, key: String, values: List<String>, labels: List<Int>) {
            column.addView(settingRow(getString(title), null, null, rule = false, onClick = null))
            column.addView(Choice(this, labels.map { getString(it) }, values.indexOf(sp.getString(key, values.first())).coerceAtLeast(0)) {
                sp.edit().putString(key, values[it]).apply()
            }, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
            column.addView(rule(), llp(MATCH, dp(1)))
        }
        choice(R.string.cz_walls_target, walls.TARGET, listOf("both", "home", "lock"), listOf(R.string.apply_both, R.string.apply_home, R.string.apply_lock))
        choice(R.string.cz_walls_fx, walls.FX, listOf("plain", "dotify", "recolor"), listOf(R.string.studio_mode_plain, R.string.studio_mode_dotify, R.string.studio_mode_recolor))

        column.addView(heading(theme?.name ?: "—", getString(R.string.cz_walls_tap)))
        val status = mono(getString(R.string.cz_walls_loading), 11f, Nihil.MUTED)
        column.addView(status, llp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(holder, llp(MATCH, WRAP))
        val id = theme?.id ?: return
        val tileW = (resources.displayMetrics.widthPixels - dp(62)) / 2
        val tileH = tileW * 16 / 9
        pool.execute {
            val names = walls.list(this, id)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    names == null -> status.text = getString(R.string.cz_walls_offline)
                    names.isEmpty() -> status.text = getString(R.string.cz_walls_none)
                    else -> {
                        status.text = resources.getQuantityString(R.plurals.cz_walls_count, names.size, names.size)
                        val add = grid(2, holder)
                        names.forEach { name ->
                            val image = android.widget.ImageView(this).apply {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
                                clipToOutline = true
                                outlineProvider = object : android.view.ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                                        outline.setRoundRect(0, 0, view.width, view.height, Nihil.corner(dp(12f)))
                                }
                            }
                            val cell = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(image, llp(MATCH, tileH))
                                addView(mono(name.substringBeforeLast('.'), 10f, Nihil.MUTED).apply { maxLines = 1 }, llp(MATCH, WRAP).apply { topMargin = dp(4) })
                                setPadding(0, 0, 0, dp(14))
                                setOnClickListener {
                                    toast(getString(R.string.cz_walls_setting))
                                    walls.execute {
                                        val ok = walls.apply(context, id, name)
                                        runOnUiThread { toast(getString(if (ok) R.string.studio_done else R.string.cz_walls_offline)) }
                                    }
                                }
                            }
                            add(cell)
                            pool.execute {
                                val bmp = walls.image(this, id, name, tileW / 2, tileH / 2)
                                if (bmp != null) image.post { image.setImageBitmap(bmp) }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Виджеты: выбор и настройки ----------------------------------------------------------------

    /** Хост для превью: ничего не делает. */
    private val previewHost = object : io.github.kamradsmeshnyavy.launcher.ui.desk.DeskHost {
        override fun launch(entry: io.github.kamradsmeshnyavy.launcher.core.Entry, from: View) = Unit
        override fun menu(entry: io.github.kamradsmeshnyavy.launcher.core.Entry, from: View) = Unit
        override fun openShade() = Unit
        override fun orderFavorites(keys: List<String>) = Unit
        override fun closeMenu() = Unit
        override fun openTasks() = Unit
        override fun editItem(item: io.github.kamradsmeshnyavy.launcher.core.Desk.Item, from: View) = Unit
        override fun ask(permission: String, then: () -> Unit) = Unit
        override fun pickImages(then: (List<android.net.Uri>) -> Unit) = Unit
        override fun editOptions(item: io.github.kamradsmeshnyavy.launcher.core.Desk.Item) = Unit
    }

    private fun addWidget(space: Int) {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val catalog = io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog
        column.addView(heading(getString(R.string.desk_add_widget), getString(R.string.desk_add_widget_sub)))
        val width = resources.displayMetrics.widthPixels - dp(48)
        fun add(item: io.github.kamradsmeshnyavy.launcher.core.Desk.Item) {
            val at = desk.add(this, space, item)
            desk.state(this).current = at
            desk.save(this)
            toast(getString(if (at == space) R.string.desk_added else R.string.desk_added_elsewhere, desk.state(this).spaces[at].name))
            finish()
        }
        catalog.GROUPS.forEach { group ->
            column.addView(mono(getString(group).uppercase(), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(26); bottomMargin = dp(8) })
            catalog.all.filter { it.group == group }.forEach { spec ->
                val (w, h) = spec.sizes.first()
                val probe = io.github.kamradsmeshnyavy.launcher.core.Desk.Item("preview-" + spec.type, "widget", spec.type, w = w, h = h)
                val previewH = (width.toFloat() / 4 * h * 1.15f * (4f / w).coerceAtMost(1.6f)).toInt().coerceIn(dp(72), dp(260))
                val frame = android.widget.FrameLayout(this)
                runCatching { spec.make(this, probe, previewHost) }.getOrNull()?.let { widget ->
                    widget.fixedHeight = true
                    frame.addView(widget, io.github.kamradsmeshnyavy.launcher.ui.lp(if (w >= 4) MATCH else (width * w / 4f * 1.4f).toInt().coerceAtMost(width), previewH))
                    widget.onClock(false)
                    widget.onBattery(87, false)
                    widget.onWeather(io.github.kamradsmeshnyavy.launcher.core.Weather.cached(this))
                }
                // Поверх — прозрачная «кнопка»: превью не нажимается само.
                frame.addView(View(this).apply {
                    background = io.github.kamradsmeshnyavy.launcher.ui.ripple(dp(22f))
                    setOnClickListener { add(io.github.kamradsmeshnyavy.launcher.core.Desk.Item(desk.newId("w"), "widget", spec.type, w = w, h = h)) }
                }, io.github.kamradsmeshnyavy.launcher.ui.lp(MATCH, previewH))
                column.addView(frame, llp(MATCH, previewH))
                column.addView(text(getString(spec.title), 14f, Nihil.INK, Nihil.fonts(this).sans), llp(MATCH, WRAP).apply { topMargin = dp(6) })
                column.addView(mono(spec.sizes.joinToString("  ") { "${it.first}×${it.second}" }, 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { bottomMargin = dp(16) })
            }
        }
        column.addView(mono(getString(R.string.wg_apps).uppercase(), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(26); bottomMargin = dp(8) })
        column.addView(settingRow(getString(R.string.w_favourites), null, chevron()) { add(io.github.kamradsmeshnyavy.launcher.core.Desk.Item(desk.newId("f"), "favourites", w = 4, h = 3)) })
        column.addView(settingRow(getString(R.string.w_app_widgets), getString(R.string.w_app_widgets_sub), chevron()) {
            startActivity(intent(this, APP_WIDGETS + space))
            finish()
        })
    }

    /** Виджеты приложений: по приложениям, с их превью; выбор — привязка в лаунчере. */
    private fun appWidgets(space: Int) {
        column.addView(heading(getString(R.string.w_app_widgets), getString(R.string.w_app_widgets_page_sub)))
        val pm = packageManager
        val density = resources.displayMetrics.densityDpi
        val providers = io.github.kamradsmeshnyavy.launcher.core.AppWidgets.providers(this)
            .sortedWith(compareBy({ it.loadLabel(pm).lowercase() }))
            .groupBy { it.provider.packageName }
            .toSortedMap(compareBy { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().lowercase() }.getOrDefault(it) })
        if (providers.isEmpty()) column.addView(body(getString(R.string.w_app_widgets_none)))
        providers.forEach { (pkg, list) ->
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            column.addView(mono(app.uppercase(), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(22); bottomMargin = dp(6) })
            list.forEach { info ->
                val preview = android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    adjustViewBounds = true
                    maxHeight = dp(180)
                }
                pool.execute {
                    val d = runCatching { info.loadPreviewImage(this, density) ?: info.loadIcon(this, density) }.getOrNull()
                    preview.post { preview.setImageDrawable(d) }
                }
                val (w, h) = io.github.kamradsmeshnyavy.launcher.core.AppWidgets.cells(this, info)
                column.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(12))
                    background = io.github.kamradsmeshnyavy.launcher.ui.ripple(dp(12f))
                    addView(preview, llp(WRAP, WRAP))
                    addView(text(info.loadLabel(pm), 14f, Nihil.INK, Nihil.fonts(context).sans), llp(MATCH, WRAP).apply { topMargin = dp(6) })
                    addView(mono("$w×$h", 10f, Nihil.MUTED))
                    setOnClickListener {
                        startActivity(Intent(context, LauncherActivity::class.java).setAction(ACTION_PICK_APP_WIDGET)
                            .putExtra("space", space).putExtra("provider", info.provider.flattenToString())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                }, llp(MATCH, WRAP))
                column.addView(rule(), llp(MATCH, dp(1)))
            }
        }
    }

    // --- Звуки -------------------------------------------------------------------------

    /** Вернулись с экрана разрешения — перерисовать страницу звуков (статус и кнопки). */
    private var redrawOnResume = false

    override fun onResume() {
        super.onResume()
        if (redrawOnResume && page == SOUNDS) {
            redrawOnResume = false
            soundsPage()
        }
    }

    private var preview: android.media.MediaPlayer? = null

    /** Прослушать мелодию [kind] пакета [pack] (через временный файл: MediaPlayer читает путь). */
    private fun listen(pack: String, kind: String) {
        preview?.release()
        preview = null
        val input = io.github.kamradsmeshnyavy.launcher.core.Sounds.open(this, pack, kind) ?: return
        val file = java.io.File(cacheDir, "preview-$kind.ogg")
        runCatching {
            input.use { i -> file.outputStream().use { i.copyTo(it) } }
            preview = android.media.MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { it.release(); if (preview === it) preview = null }
                prepare()
                start()
            }
        }
    }

    override fun onPause() {
        preview?.release()
        preview = null
        super.onPause()
    }

    /**
     * Звуки: пакет (таблица Customizer), «Во всей системе» — звонок, уведомления, будильник
     * мелодиями пакета, и «События системы» — звуки пакета на зарядку, батарею, Bluetooth….
     */
    private fun soundsPage() {
        val sys = io.github.kamradsmeshnyavy.launcher.core.SystemSounds
        val events = io.github.kamradsmeshnyavy.launcher.system.SoundEvents
        val sounds = io.github.kamradsmeshnyavy.launcher.core.Sounds
        column.removeAllViews()
        table("SOUND_TWEAKS")
        val pack = sounds.pack(Look.current(this))
        val sp = Look.prefs(this)

        column.addView(heading(getString(R.string.snd_system), getString(R.string.snd_system_sub)))
        val allowed = sys.canWrite(this)
        if (!allowed) {
            column.addView(settingRow(getString(R.string.snd_permission), getString(R.string.snd_permission_sub), chevron()) {
                redrawOnResume = true
                runCatching { startActivity(sys.permissionIntent(this)) }
            })
        }
        val names = mapOf(
            io.github.kamradsmeshnyavy.launcher.core.SystemSounds.Kind.RINGTONE to R.string.snd_ringtone,
            io.github.kamradsmeshnyavy.launcher.core.SystemSounds.Kind.NOTIFICATION to R.string.snd_notification,
            io.github.kamradsmeshnyavy.launcher.core.SystemSounds.Kind.ALARM to R.string.snd_alarm,
        )
        io.github.kamradsmeshnyavy.launcher.core.SystemSounds.Kind.entries.forEach { kind ->
            val now = sys.current(this, kind)
            column.addView(switchRow(getString(names.getValue(kind)), getString(R.string.snd_now, now.ifEmpty { "—" }), sys.enabled(this, kind)) { on ->
                sp.edit().putBoolean(kind.key, on).apply()
            })
            column.addView(settingRow(getString(R.string.snd_listen), getString(R.string.snd_listen_sub, pack), null) { listen(pack, kind.file) })
        }
        column.addView(settingRow(getString(R.string.snd_apply), getString(R.string.snd_apply_sub, pack), chevron()) {
            if (!sys.canWrite(this)) {
                redrawOnResume = true
                runCatching { startActivity(sys.permissionIntent(this)) }
                return@settingRow
            }
            if (!sys.anyEnabled(this)) {
                toast(getString(R.string.snd_choose))
                return@settingRow
            }
            sys.apply(this, pack) { n ->
                toast(if (n > 0) getString(R.string.snd_applied, n) else getString(R.string.snd_failed))
                soundsPage()
            }
        })
        if (sys.applied(this) != null) {
            column.addView(settingRow(getString(R.string.snd_restore), getString(R.string.snd_restore_sub), chevron()) {
                sys.restore(this) { ok ->
                    toast(getString(if (ok) R.string.snd_restored else R.string.snd_failed))
                    soundsPage()
                }
            })
        }

        column.addView(heading(getString(R.string.snd_events), getString(R.string.snd_events_sub)))
        val eventNames = mapOf(
            io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.CHARGE to R.string.snd_ev_charge,
            io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.BATTERY to R.string.snd_ev_battery,
            io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.UNLOCK to R.string.snd_ev_unlock,
            io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.VOLUME to R.string.snd_ev_volume,
            io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.BLUETOOTH to R.string.snd_ev_bluetooth,
        )
        io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.entries.forEach { kind ->
            column.addView(switchRow(getString(eventNames.getValue(kind)), null, events.enabled(this, kind)) { on ->
                sp.edit().putBoolean(kind.key, on).apply()
                // Bluetooth-события приходят только с разрешением «Устройства поблизости».
                if (on && kind == io.github.kamradsmeshnyavy.launcher.system.SoundEvents.Kind.BLUETOOTH &&
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
                }
            })
        }
        if (!Look.current(this).bool("sounds", false)) {
            column.addView(mono(getString(R.string.snd_off_hint), 11f, Nihil.SIGNAL), llp(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT) io.github.kamradsmeshnyavy.launcher.system.SoundEvents.restart(this)
    }

    // --- Рабочие столы -----------------------------------------------------------------

    private val menu by lazy { io.github.kamradsmeshnyavy.launcher.ui.AppMenu(this) }

    /** Столы списком: нажатие — переименовать, выше/ниже, удалить (с элементами — спросить). */
    private fun spacesPage() {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        column.removeAllViews()
        column.addView(heading(getString(R.string.desk_spaces), getString(R.string.desk_spaces_sub)))
        val spaces = desk.state(this).spaces
        spaces.forEachIndexed { index, space ->
            val count = resources.getQuantityString(R.plurals.desk_items, space.items.size, space.items.size)
            lateinit var row: View
            row = settingRow(space.name, count, chevron()) {
                val items = ArrayList<io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item>()
                items += io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item(io.github.kamradsmeshnyavy.launcher.ui.LineIcon.Shape.GEAR, getString(R.string.desk_rename)) { renameSpace(index) }
                if (index > 0) items += io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item(io.github.kamradsmeshnyavy.launcher.ui.LineIcon.Shape.UP, getString(R.string.menu_move_up)) {
                    desk.moveSpace(this, index, index - 1)
                    spacesPage()
                }
                if (index < spaces.size - 1) items += io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item(io.github.kamradsmeshnyavy.launcher.ui.LineIcon.Shape.DOWN, getString(R.string.menu_move_down)) {
                    desk.moveSpace(this, index, index + 1)
                    spacesPage()
                }
                if (spaces.size > 1) items += io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item(io.github.kamradsmeshnyavy.launcher.ui.LineIcon.Shape.TRASH, getString(R.string.desk_remove_space), danger = true) {
                    val go = {
                        desk.removeSpace(this, index).forEach { if (it.kind == "appwidget") io.github.kamradsmeshnyavy.launcher.core.AppWidgets.delete(this, it.appWidgetId) }
                        toast(getString(R.string.desk_removed_space))
                        spacesPage()
                    }
                    if (space.items.isEmpty()) {
                        go()
                    } else {
                        row.post {
                            menu.show(row, getString(R.string.desk_remove_space_confirm, space.name, space.items.size), listOf(
                                io.github.kamradsmeshnyavy.launcher.ui.AppMenu.Item(io.github.kamradsmeshnyavy.launcher.ui.LineIcon.Shape.TRASH, getString(R.string.desk_remove_space_yes), danger = true) { go() },
                            ))
                        }
                    }
                }
                menu.show(row, space.name, items)
            }
            column.addView(row)
        }
        column.addView(settingRow(getString(R.string.desk_new_space), null, chevron()) {
            desk.addSpace(this, (spaces.size + 1).toString())
            spacesPage()
        })
    }

    private fun renameSpace(index: Int) {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val space = desk.state(this).spaces.getOrNull(index) ?: return
        val field = EditText(this).apply {
            setText(space.name)
            setSelection(space.name.length)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, llp(MATCH, WRAP))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.desk_rename)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim().take(16)
                if (name.isNotEmpty()) {
                    space.name = name
                    desk.save(this)
                    spacesPage()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun itemOptions(id: String) {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val catalog = io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog
        val (_, item) = desk.find(this, id) ?: run {
            finish()
            return
        }
        val spec = catalog.spec(item.type)
        column.addView(heading(spec?.title?.let { getString(it) } ?: getString(R.string.desk_options), getString(R.string.desk_options_sub)))
        val opts = if (item.kind == "appwidget") listOf(
            io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog.Opt("tint", "enum", R.string.wo_tint, listOf("none", "theme", "mono"), "none"),
            io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog.Opt("card", "bool", R.string.wo_card, default = true),
        ) else listOf(catalog.VOICE) + (spec?.options ?: emptyList())
        fun save() = desk.save(this)
        opts.forEach { opt ->
            val label = getString(opt.title)
            when (opt.type) {
                "enum" -> {
                    val current = item.options.optString(opt.key, opt.default.toString())
                    column.addView(settingRow(label, null, null, rule = false, onClick = null))
                    column.addView(Choice(this, opt.values.map { tr(getString(resources.getIdentifier("wv_$it", "string", packageName).takeIf { r -> r != 0 } ?: R.string.wv_auto)) }, opt.values.indexOf(current).coerceAtLeast(0)) {
                        item.options.put(opt.key, opt.values[it])
                        save()
                    }, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
                    column.addView(rule(), llp(MATCH, dp(1)))
                }
                "bool" -> {
                    val toggle = Toggle(this)
                    toggle.set(item.options.optBoolean(opt.key, opt.default as? Boolean ?: false))
                    toggle.onChange = {
                        item.options.put(opt.key, it)
                        save()
                    }
                    column.addView(settingRow(label, null, toggle) { toggle.performClick() })
                }
                "num" -> {
                    val value = item.options.optDouble(opt.key, (opt.default as? Double) ?: opt.min)
                    column.addView(settingRow(label, null, null, rule = false, onClick = null))
                    column.addView(LedSlider(this, opt.min, opt.max, if (opt.max - opt.min > 200) 500.0 else 1.0, value) {
                        item.options.put(opt.key, it)
                        save()
                    }, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
                    column.addView(rule(), llp(MATCH, dp(1)))
                }
                else -> {
                    // text / date: поле ввода; дата — ГГГГ-ММ-ДД.
                    column.addView(settingRow(label, if (opt.type == "date") "2026-12-31" else null, null, rule = false, onClick = null))
                    column.addView(EditText(this).apply {
                        setText(item.options.optString(opt.key, opt.default.toString()))
                        setTextColor(Nihil.INK)
                        setHintTextColor(Nihil.MUTED)
                        typeface = Nihil.fonts(context).mono
                        setSingleLine()
                        background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        if (opt.type == "date") inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                item.options.put(opt.key, s?.toString()?.trim().orEmpty())
                                save()
                            }
                        })
                    }, llp(MATCH, WRAP).apply { bottomMargin = dp(14) })
                }
            }
        }
    }

    // --- Блокировка ---------------------------------------------------------------------------------

    private fun lockPage() {
        val art = io.github.kamradsmeshnyavy.launcher.core.LockArt
        val sp = Look.prefs(this)
        column.addView(heading(getString(R.string.cz_lock), getString(R.string.cz_lock_page_sub)))
        val current = art.current(this)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val status = mono("", 11f, Nihil.DIM)

        // Подложка.
        val sources = listOf("theme", "photo", "plain")
        column.addView(settingRow(getString(R.string.cz_lock_source), getString(R.string.cz_lock_source_sub), null, rule = false, onClick = null))
        column.addView(Choice(this, listOf(getString(R.string.cz_lock_from_theme), getString(R.string.cz_lock_from_photo), getString(R.string.cz_lock_plain)),
            sources.indexOf(sp.getString(art.SOURCE, "theme")).coerceAtLeast(0)) { i ->
            sp.edit().putString(art.SOURCE, sources[i]).apply()
            when (sources[i]) {
                "photo" -> {
                    @Suppress("DEPRECATION")
                    runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQUEST_LOCK_PHOTO) }
                }
                "theme" -> fetchThemeBase(status)
                else -> restart()
            }
        }, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        column.addView(rule(), llp(MATCH, dp(1)))
        // Размытие и затемнение (LOCK_TWEAKS).
        listOf(Triple("wallpaperBlur", R.string.cz_lock_blur, 0.0 to 1.0), Triple("wallpaperDim", R.string.cz_lock_dim, 0.0 to 0.9)).forEach { (key, title, range) ->
            column.addView(settingRow(getString(title), null, null, rule = false, onClick = null))
            column.addView(LedSlider(this, range.first, range.second, 0.05, Look.current(this).num(key, if (key == "wallpaperDim") 0.25 else 0.4)) {
                io.github.kamradsmeshnyavy.launcher.core.LookEdit.set(this, key, it)
                restart()
            }, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
            column.addView(rule(), llp(MATCH, dp(1)))
        }
        column.addView(KeyButton(this, getString(R.string.cz_lock_apply), accent = true).apply {
            setOnClickListener {
                status.text = getString(R.string.studio_rendering)
                pool.execute {
                    val ok = runCatching {
                        val bmp = art.wallpaper(context, art.current(context), screenW, screenH)
                        android.app.WallpaperManager.getInstance(context).setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_LOCK)
                    }.isSuccess
                    runOnUiThread { status.text = getString(if (ok) R.string.studio_done else R.string.studio_failed) }
                }
            }
        }, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        column.addView(KeyButton(this, getString(R.string.cz_lock_dream)).apply {
            setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) } }
        }, llp(WRAP, WRAP).apply { topMargin = dp(10) })
        column.addView(status, llp(MATCH, WRAP).apply { topMargin = dp(10) })
        column.addView(body(getString(R.string.cz_lock_dream_sub)), llp(MATCH, WRAP).apply { topMargin = dp(6) })

        // Галерея дизайнов: превью — как заставка (с часами), в цветах темы.
        column.addView(heading(getString(R.string.cz_lock_designs), getString(R.string.cz_lock_designs_sub)))
        val tileW = (screenW - dp(62)) / 2
        val tileH = (tileW * screenH.toFloat() / screenW).toInt()
        val add = grid(2)
        val base = art.base(this, tileW, tileH)
        val sample = io.github.kamradsmeshnyavy.launcher.core.LockArt.Live(87, false, "", "4°")
        val choices = listOfNotNull(
            if (sp.getString(Look.PC_SNAPSHOT, null)?.contains("\"lock\"") == true) "pc" else null,
        ) + art.all(this).map { it.id }
        choices.forEach { id ->
            val design = if (id == "pc") art.current(this) else id
            val on = (sp.getString(art.DESIGN, null) ?: "dotmatrix") == id
            val image = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(14f))
                }
            }
            pool.execute {
                val bmp = runCatching {
                    android.graphics.Bitmap.createBitmap(tileW, tileH, android.graphics.Bitmap.Config.ARGB_8888).also {
                        art.draw(android.graphics.Canvas(it), this, design, tileW, tileH, base, sample)
                    }
                }.getOrNull()
                runOnUiThread { image.setImageBitmap(bmp) }
            }
            val info = art.all(this).firstOrNull { it.id == id }
            add(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(16))
                addView(image, llp(MATCH, tileH))
                addView(text((if (id == "pc") getString(R.string.cz_bar_from_pc) else info?.name ?: id) + if (on) "  ●" else "", 13f, if (on) Nihil.SIGNAL else Nihil.INK, Nihil.fonts(context).sans), llp(MATCH, WRAP).apply { topMargin = dp(6) })
                setOnClickListener {
                    sp.edit().putString(art.DESIGN, id).apply()
                    restart()
                }
                setOnLongClickListener {
                    toast((if (ru) info?.descriptionRu else info?.description).orEmpty())
                    true
                }
            })
        }
        if (current.isEmpty()) Unit
    }

    /** Подложка «обои темы»: первые обои текущей темы с компьютера → filesDir/lock-base.jpg. */
    private fun fetchThemeBase(status: android.widget.TextView) {
        val walls = io.github.kamradsmeshnyavy.launcher.core.ThemeWalls
        val theme = Look.current(this).theme?.id ?: return
        if (!walls.paired(this)) {
            status.text = getString(R.string.cz_walls_unpaired)
            return
        }
        status.text = getString(R.string.cz_walls_loading)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        pool.execute {
            val ok = runCatching {
                val name = walls.list(this, theme)?.firstOrNull() ?: error("none")
                val bmp = walls.image(this, theme, name, w, h) ?: error("none")
                io.github.kamradsmeshnyavy.launcher.core.LockArt.baseFile(this).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }.isSuccess
            runOnUiThread { if (ok) restart() else status.text = getString(R.string.cz_walls_offline) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOCK_PHOTO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pool.execute {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        io.github.kamradsmeshnyavy.launcher.core.LockArt.baseFile(this).outputStream().use { input.copyTo(it) }
                    }
                }
                runOnUiThread { restart() }
            }
        }
    }

    // --- Бар ----------------------------------------------------------------------------------------

    private fun barPage() {
        val sp = Look.prefs(this)
        val bars = io.github.kamradsmeshnyavy.launcher.core.BarSkin
        column.addView(heading(getString(R.string.cz_bar), getString(R.string.cz_bar_page_sub)))
        column.addView(switchRow(getString(R.string.cz_bar_on), getString(R.string.cz_bar_on_sub), sp.getBoolean(bars.ENABLED, true)) {
            sp.edit().putBoolean(bars.ENABLED, it).apply()
            applyLook()
        })
        // Подписи столов: как у скина или своя.
        val styles = listOf("skin") + bars.LABEL_STYLES
        val now = sp.getString(bars.LABELS, "skin") ?: "skin"
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, 0, dp(8))
        }
        styles.forEach { st ->
            val on = st == now
            val sample = if (st == "skin") getString(R.string.cz_bar_labels_skin) else (0 until 3).joinToString(" ") { bars.label(st, it, it == 0) }
            labels.addView(text((if (on) "● " else "   ") + sample + if (st != "skin") "   ($st)" else "", 14f, if (on) Nihil.INK else Nihil.DIM, Nihil.fonts(this).mono).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    sp.edit().putString(bars.LABELS, st).apply()
                    applyLook()
                }
            }, llp(MATCH, WRAP))
        }
        column.addView(settingRow(getString(R.string.cz_bar_labels), null, mono(if (now == "skin") getString(R.string.cz_bar_labels_skin) else now, 11f, Nihil.DIM), rule = false) {
            labels.visibility = if (labels.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        column.addView(labels, llp(MATCH, WRAP))
        column.addView(rule(), llp(MATCH, dp(1)))

        column.addView(heading(getString(R.string.cz_bar_skins), getString(R.string.cz_bar_skins_sub)))
        val chosen = sp.getString(bars.SKIN, "style") ?: "style"
        fun tile(id: String, title: String, note: String, skin: io.github.kamradsmeshnyavy.launcher.core.BarSkin.Skin) {
            val on = id == chosen
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(14))
                background = io.github.kamradsmeshnyavy.launcher.ui.ripple(dp(12f))
                addView(io.github.kamradsmeshnyavy.launcher.ui.BarView(context, skin) {}.apply { inset = 0 }, llp(MATCH, WRAP))
                addView(text(title + if (on) "  ●" else "", 14f, if (on) Nihil.SIGNAL else Nihil.INK, Nihil.fonts(context).sans), llp(MATCH, WRAP).apply { topMargin = dp(6) })
                if (note.isNotEmpty()) addView(body(note), llp(MATCH, WRAP).apply { topMargin = dp(2) })
                setOnClickListener {
                    sp.edit().putString(bars.SKIN, id).apply()
                    applyLook()
                }
            }, llp(MATCH, WRAP))
            column.addView(rule(), llp(MATCH, dp(1)))
        }
        val styleSkin = run {
            val prev = sp.getString(bars.SKIN, "style")
            sp.edit().putString(bars.SKIN, "style").commit()
            val s = bars.current(this)
            sp.edit().putString(bars.SKIN, prev).commit()
            s
        }
        tile("style", getString(R.string.cz_bar_from_style), Look.current(this).style?.let { if (ru) it.nameRu else it.name }.orEmpty(), styleSkin)
        if (Look.prefs(this).getString(Look.PC_SNAPSHOT, null)?.contains("\"bar\"") == true) {
            val pc = org.json.JSONObject(Look.prefs(this).getString(Look.PC_SNAPSHOT, "{}")!!).optJSONObject("bar")
            if (pc != null) tile("pc", getString(R.string.cz_bar_from_pc), "", io.github.kamradsmeshnyavy.launcher.core.BarSkin.Skin("pc", pc))
        }
        bars.all(this).forEach { skin -> tile(skin.id, skin.id, if (ru) skin.noteRu else skin.note, skin) }
    }

    // --- Погода ---------------------------------------------------------------------------------

    private fun weather() {
        val w = io.github.kamradsmeshnyavy.launcher.core.Weather
        val sp = Look.prefs(this)
        column.addView(heading(getString(R.string.cz_weather), getString(R.string.cz_weather_page_sub)))
        column.addView(switchRow(getString(R.string.cz_weather_show), null, sp.getBoolean(w.SHOW, true)) { sp.edit().putBoolean(w.SHOW, it).apply() })

        val city = EditText(this).apply {
            hint = getString(R.string.cz_weather_city_hint)
            setHintTextColor(Nihil.MUTED)
            setTextColor(Nihil.INK)
            typeface = Nihil.fonts(context).sans
            setSingleLine()
            setText(sp.getString(w.CITY, ""))
            background = rounded(Nihil.SURFACE, dp(12f), dp(1), Nihil.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    sp.edit().putString(w.CITY, s?.toString()?.trim().orEmpty()).apply()
                }
            })
        }
        val sources = listOf("computer", "city")
        val cityBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(city, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
            visibility = if (sp.getString(w.SOURCE, "computer") == "city") View.VISIBLE else View.GONE
        }
        column.addView(settingRow(getString(R.string.cz_weather_source), getString(R.string.cz_weather_source_sub), null, rule = false, onClick = null))
        column.addView(Choice(this, listOf(getString(R.string.cz_weather_from_pc), getString(R.string.cz_weather_from_city)),
            sources.indexOf(sp.getString(w.SOURCE, "computer")).coerceAtLeast(0)) {
            sp.edit().putString(w.SOURCE, sources[it]).apply()
            cityBox.visibility = if (it == 1) View.VISIBLE else View.GONE
        }, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        column.addView(cityBox, llp(MATCH, WRAP))
        column.addView(rule(), llp(MATCH, dp(1)))

        val units = listOf("auto", "c", "f")
        column.addView(settingRow(getString(R.string.cz_weather_units), null, null, rule = false, onClick = null))
        column.addView(Choice(this, listOf(getString(R.string.cz_weather_auto), "°C", "°F"), units.indexOf(sp.getString(w.UNITS, "auto")).coerceAtLeast(0)) {
            sp.edit().putString(w.UNITS, units[it]).apply()
        }, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
        column.addView(rule(), llp(MATCH, dp(1)))

        // Приложение по нажатию на погоду: автоматически (Pixel Weather, Breezy…) или своё.
        io.github.kamradsmeshnyavy.launcher.core.Apps.init(this)
        val chosen = sp.getString(w.APP, "").orEmpty()
        val apps = io.github.kamradsmeshnyavy.launcher.core.Apps.visible(emptySet()).distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        val current = apps.firstOrNull { it.packageName == chosen }?.label ?: getString(R.string.cz_weather_app_auto)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, 0, dp(8))
        }
        fun option(label: String, pkg: String) {
            val on = pkg == chosen
            list.addView(text((if (on) "● " else "   ") + label, 14f, if (on) Nihil.INK else Nihil.DIM, Nihil.fonts(this).sans).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    sp.edit().putString(w.APP, pkg).apply()
                    restart()
                }
            }, llp(MATCH, WRAP))
        }
        option(getString(R.string.cz_weather_app_auto), "")
        apps.forEach { option(it.label, it.packageName) }
        column.addView(settingRow(getString(R.string.cz_weather_app), getString(R.string.cz_weather_app_sub), mono(current, 11f, Nihil.DIM), rule = false) {
            list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        column.addView(list, llp(MATCH, WRAP))
        column.addView(rule(), llp(MATCH, dp(1)))

        val result = mono("", 11f, Nihil.DIM)
        column.addView(KeyButton(this, getString(R.string.cz_weather_check)).apply {
            setOnClickListener {
                result.text = getString(R.string.cz_walls_loading)
                w.refresh(context, force = true) { now ->
                    result.text = if (now == null) getString(R.string.cz_weather_failed)
                    else w.format(now, sp.getString(w.UNITS, "auto") ?: "auto") + " · " + now.place
                }
            }
        }, llp(WRAP, WRAP).apply { topMargin = dp(18) })
        column.addView(result, llp(MATCH, WRAP).apply { topMargin = dp(10) })
        column.addView(body(getString(R.string.cz_weather_privacy)), llp(MATCH, WRAP).apply { topMargin = dp(16) })
    }

    // --- Шрифты --------------------------------------------------------------------------------

    private fun fonts() {
        column.addView(heading(getString(R.string.cz_fonts), getString(R.string.cz_fonts_page_sub)))
        val look = Look.current(this)
        val own = Look.prefs(this).getString(Look.FONT_PROFILE, null)
        val profiles = listOf(
            "stock" to R.string.font_stock,
            "nothing" to R.string.font_nothing,
            "pixel" to R.string.font_pixel,
            "lumen" to R.string.font_lumen,
            "dots-all" to R.string.font_dots_all,
            "paper-ui" to R.string.font_paper,
            "retro" to R.string.font_retro,
        )
        if (look.typeface != "profile") column.addView(body(getString(R.string.cz_fonts_locked)), llp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        column.addView(settingRow(getString(R.string.cz_font_from_style), look.style?.fonts, null, marked = own == null) {
            LookEdit.setFontProfile(this, null)
            applyLook()
        })
        profiles.forEach { (id, label) ->
            val on = look.fontProfile == id
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(mono(getString(label) + if (on) "  ●" else "", 11f, if (on) Nihil.SIGNAL else Nihil.MUTED))
                addView(fontSample(id), llp(MATCH, WRAP).apply { topMargin = dp(6) })
                background = io.github.kamradsmeshnyavy.launcher.ui.ripple(dp(10f))
                setOnClickListener {
                    LookEdit.setFontProfile(context, id)
                    applyLook()
                }
            })
            column.addView(rule(), llp(MATCH, dp(1)))
        }
    }

    // --- Таблица Customizer по схеме ------------------------------------------------------------

    private fun table(id: String, withHeading: Boolean = true) {
        forEachTable { table, items ->
            if (table != id) return@forEachTable
            if (withHeading) column.addView(heading(tableTitle(id), getString(R.string.cz_table_sub)))
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.has("section")) {
                    column.addView(mono(tr(item.getString("section")).uppercase(), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply {
                        topMargin = dp(24)
                        bottomMargin = dp(4)
                    })
                    continue
                }
                column.addView(control(item))
            }
            if (id == "GLYPH_TWEAKS") {
                column.addView(KeyButton(this, getString(R.string.glyph_try)).apply {
                    setOnClickListener {
                        finishAffinity()
                        startActivity(Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        android.os.Handler(mainLooper).postDelayed({ Glyph.pulse(Glyph.Pulse.NOTIFY) }, 700)
                    }
                }, llp(WRAP, WRAP).apply { topMargin = dp(16) })
            }
        }
    }

    private fun tableTitle(id: String): String = getString(
        when (id) {
            "GLYPH_TWEAKS" -> R.string.cz_glyph
            "SOUND_TWEAKS" -> R.string.cz_sounds
            "DOTIFY_TWEAKS" -> R.string.cz_dotify
            "DOTMATRIX_TWEAKS" -> R.string.cz_dotmatrix
            "LAUNCHER_TWEAKS" -> R.string.cz_launcher
            "STYLE_TWEAKS" -> R.string.cz_style_apply
            "PRESET_TWEAKS" -> R.string.cz_preset_apply
            "WIDGET_TWEAKS" -> R.string.cz_widgets
            else -> R.string.cz_settings
        },
    )

    /** Одна строка схемы как контрол; значение — текущее (стиль, свои правки, ПК). */
    private fun control(item: JSONObject): View {
        val key = item.getString("key")
        val label = tr(item.optString("label", key))
        val look = Look.current(this)
        val own = LookEdit.isOverridden(this, key)
        val reset = {
            LookEdit.reset(this, key)
            toast(getString(R.string.cz_reset_done))
            applyLook()
        }
        return when (item.optString("type")) {
            "bool" -> {
                val toggle = Toggle(this)
                toggle.set(look.bool(key, item.optBoolean("default", false)))
                toggle.onChange = { LookEdit.set(this, key, it) }
                settingRow(label, null, toggle, marked = own, onLongClick = reset) { toggle.performClick() }
            }
            "num" -> {
                val min = item.optDouble("min", 0.0)
                val max = item.optDouble("max", 1.0)
                val value = look.num(key, item.optDouble("default", min))
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(settingRow(label, null, null, rule = false, marked = own, onLongClick = reset, onClick = null))
                    addView(LedSlider(context, min, max, item.optDouble("step", 0.0), value) { LookEdit.set(context, key, it) }, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
                    addView(rule(), llp(MATCH, dp(1)))
                }
            }
            "enum" -> enumControl(key, label, item, own, reset)
            "color" -> colorControl(key, label, item.optString("default", "foreground"), own, reset)
            else -> settingRow(label, item.optString("type"), null, onClick = null)
        }
    }

    private fun enumControl(key: String, label: String, item: JSONObject, own: Boolean, reset: () -> Unit): View {
        val values = item.getJSONArray("values").let { a -> List(a.length()) { a.getString(it) } }
        val current = Look.current(this).str(key, values.first())
        val short = values.size <= 3 && values.sumOf { tr(it).length } <= 22
        if (short) {
            val choice = Choice(this, values.map { tr(it) }, values.indexOf(current).coerceAtLeast(0)) { LookEdit.set(this, key, values[it]) }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(settingRow(label, null, null, rule = false, marked = own, onLongClick = reset, onClick = null))
                addView(choice, llp(WRAP, WRAP).apply { bottomMargin = dp(8) })
                addView(rule(), llp(MATCH, dp(1)))
            }
        }
        // Длинный список: значение справа, нажатие раскрывает варианты под строкой.
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, 0, dp(8))
        }
        values.forEach { v ->
            val on = v == current
            options.addView(text((if (on) "● " else "   ") + tr(v), 14f, if (on) Nihil.INK else Nihil.DIM, Nihil.fonts(this).sans).apply {
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    LookEdit.set(context, key, v)
                    applyLook()
                }
            }, llp(MATCH, WRAP))
        }
        val value = mono(tr(current), 11f, Nihil.DIM)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingRow(label, null, value, rule = false, marked = own, onLongClick = reset) {
                options.visibility = if (options.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            addView(options, llp(MATCH, WRAP))
            addView(rule(), llp(MATCH, dp(1)))
        }
    }

    private fun colorControl(key: String, label: String, default: String, own: Boolean, reset: () -> Unit): View {
        val look = Look.current(this)
        val current = look.str(key, default)
        val roles = (listOf(default, "foreground", "accent", "urgent", "muted", "dim", "red", "green", "yellow", "blue", "magenta", "cyan")).distinct()
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<Swatch>()
        roles.forEach { role ->
            val color = look.roleColor(role) ?: look.roleColor(default.takeIf { it != "default" } ?: "foreground") ?: Nihil.INK
            val swatch = Swatch(this, color, role == current)
            swatch.contentDescription = role
            swatch.setOnClickListener {
                LookEdit.set(this, key, role)
                swatches.forEach { it.chosen = it === swatch }
            }
            swatches += swatch
            strip.addView(swatch, llp(WRAP, WRAP).apply { marginEnd = dp(4) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(settingRow(label, tr(current), null, rule = false, marked = own, onLongClick = reset, onClick = null))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }, llp(MATCH, WRAP).apply { bottomMargin = dp(12) })
            addView(rule(), llp(MATCH, dp(1)))
        }
    }

    // --- Мелочи -----------------------------------------------------------------------------

    private fun switchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle(this)
        toggle.set(value)
        toggle.onChange = onChange
        return settingRow(title, sub, toggle) { toggle.performClick() }
    }

    /** Сетка из [columns] колонок: возвращает функцию «добавить ячейку». */
    private fun grid(columns: Int, into: LinearLayout = column): (View) -> Unit {
        var row: LinearLayout? = null
        var count = 0
        return { cell ->
            if (count % columns == 0) {
                // weightSum: неполная последняя строка не растягивает плитки.
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = columns.toFloat()
                }
                into.addView(row, llp(MATCH, WRAP))
            }
            // Фильтр в галерее тем кладёт те же плитки в новые строки: со старой их снимаем.
            (cell.parent as? android.view.ViewGroup)?.removeView(cell)
            row!!.addView(cell, llp(0, WRAP, 1f).apply {
                if (count % columns != 0) marginStart = dp(14)
            })
            count++
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val PAGE = "page"
        const val HOME = "home"
        const val STYLES = "styles"
        const val THEMES = "themes"
        const val PRESETS = "presets"
        const val FONTS = "fonts"
        const val WALLPAPERS = "wallpapers"
        const val WEATHER = "weather"
        const val BAR = "bar"
        const val LOCK = "lock"
        const val SPACES = "spaces"
        const val SOUNDS = "sounds"
        const val ADD = "add:"
        const val APP_WIDGETS = "appwidgets:"
        const val ITEM = "item:"
        const val TABLE = "table:"
        const val REQUEST_LOCK_PHOTO = 51
        const val REQUEST_BT = 52
        const val ACTION_PICK_APP_WIDGET = "io.github.kamradsmeshnyavy.launcher.PICK_APP_WIDGET"

        fun intent(context: Context, page: String = HOME): Intent =
            Intent(context, CustomizerActivity::class.java).putExtra(PAGE, page)
    }
}
