package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import org.json.JSONObject

/**
 * Скины бара Omarchy (kamradsmeshnyavy.bar/skins → assets/looks/bars.json) для бара лаунчера.
 *
 * Какой скин: barSkin в настройках — "style" (скин стиля Customizer: barSkin или
 * встроенный barSkinData), "pc" (как на сопряжённом компьютере, bar-skin.json из /v1/look)
 * или id скина. Токены — те же, что у BarSkin.qml (дефолты [DEFAULTS]).
 */
object BarSkin {
    const val ENABLED = "phoneBar"
    const val SKIN = "barSkin"
    const val LABELS = "barWsLabels"

    class Skin(val id: String, val tokens: JSONObject) {
        val note: String get() = tokens.optString("note")
        val noteRu: String get() = tokens.optString("note_ru", note)

        fun str(key: String): String = tokens.optString(key, DEFAULTS.optString(key))
        fun num(key: String): Double = tokens.optDouble(key, DEFAULTS.optDouble(key, 0.0)).takeIf { !it.isNaN() } ?: 0.0
        fun bool(key: String): Boolean = tokens.optBoolean(key, DEFAULTS.optBoolean(key, false))

        /** full floating pill islands notch underline hug frame */
        val shape: String get() = str("shape").takeIf { it in SHAPES } ?: "full"
    }

    val SHAPES = setOf("full", "floating", "pill", "islands", "notch", "underline", "hug", "frame")

    val LABEL_STYLES = listOf(
        "numbers", "leds", "dots", "squares", "diamonds", "circled", "circled-filled", "roman", "roman-small",
        "kanji", "greek", "cyrillic", "superscript", "fullwidth", "parens", "stars", "triangles", "hearts", "hexagons", "bars", "braille", "none",
    )

    val DEFAULTS: JSONObject = JSONObject()
        .put("shape", "full").put("height", 0).put("gap", 0).put("margin", 0).put("width", 1.0).put("radius", 0)
        .put("frameWidth", 8).put("padding", -1).put("islandGap", 0).put("background", "bar").put("backgroundAlpha", -1)
        .put("gradient", "").put("border", "accent").put("borderWidth", 0).put("borderAlpha", 1.0).put("text", "bar")
        .put("active", "bar").put("shadow", false).put("glow", false).put("moduleStyle", "plain")
        .put("moduleBackground", "foreground").put("moduleAlpha", 0.08).put("spacing", 0).put("hardShadow", 0)
        .put("bevel", false).put("wsLabelStyle", "numbers").put("wsShowEmpty", true).put("transparent", false)

    @Volatile
    private var skins: List<Skin>? = null

    fun all(context: Context): List<Skin> = skins ?: synchronized(this) {
        skins ?: runCatching {
            val a = JSONObject(context.assets.open("looks/bars.json").bufferedReader().use { it.readText() }).getJSONArray("skins")
            List(a.length()) { a.getJSONObject(it).let { o -> Skin(o.getString("id"), o) } }
        }.getOrDefault(emptyList()).also { skins = it }
    }

    fun enabled(context: Context): Boolean = Look.prefs(context).getBoolean(ENABLED, true)

    /** Скин сейчас: свой выбор, стиля или компьютера. */
    fun current(context: Context): Skin {
        val sp = Look.prefs(context)
        val choice = sp.getString(SKIN, "style") ?: "style"
        val look = Look.current(context)
        if (choice == "pc") {
            sp.getString(Look.PC_SNAPSHOT, null)?.let { runCatching { JSONObject(it).optJSONObject("bar") }.getOrNull() }
                ?.let { return Skin("pc", it) }
        }
        if (choice != "style" && choice != "pc") all(context).firstOrNull { it.id == choice }?.let { return it }
        val style = look.style
        style?.json?.optJSONObject("barSkinData")?.let { return Skin("style", it) }
        val id = style?.json?.optString("barSkin")?.ifEmpty { null } ?: "stock"
        return all(context).firstOrNull { it.id == id } ?: Skin("stock", JSONObject())
    }

    /** Подпись рабочего стола [i] (с нуля) в стиле [style]; для leds/dots/… — символ «горит/не горит». */
    fun label(style: String, i: Int, active: Boolean): String {
        val n = i + 1
        fun pick(list: String, sep: Char = ' '): String = list.split(sep).getOrNull(i) ?: n.toString()
        return when (style) {
            "dots" -> if (active) "●" else "○"
            "squares" -> if (active) "■" else "□"
            "diamonds" -> if (active) "◆" else "◇"
            "stars" -> if (active) "★" else "☆"
            "triangles" -> if (active) "▲" else "△"
            "hearts" -> if (active) "♥" else "♡"
            "hexagons" -> if (active) "⬢" else "⬡"
            "bars" -> if (active) "▮" else "▯"
            "circled" -> pick("① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩")
            "circled-filled" -> pick("❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿")
            "roman" -> pick("I II III IV V VI VII VIII IX X")
            "roman-small" -> pick("i ii iii iv v vi vii viii ix x")
            "kanji" -> pick("一 二 三 四 五 六 七 八 九 十")
            "greek" -> pick("α β γ δ ε ζ η θ ι κ")
            "cyrillic" -> pick("а б в г д е ж з и к")
            "superscript" -> pick("¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ¹⁰")
            "fullwidth" -> pick("１ ２ ３ ４ ５ ６ ７ ８ ９ １０")
            "parens" -> pick("⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽")
            "braille" -> pick("⠁ ⠃ ⠉ ⠙ ⠑ ⠋ ⠛ ⠓ ⠊ ⠚")
            else -> n.toString()
        }
    }

}
