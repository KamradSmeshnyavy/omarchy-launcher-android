package io.github.kamradsmeshnyavy.launcher.core

import kotlin.math.exp

/**
 * Recolor — картинка в цветах темы, как Customizer → Рабочий стол → Recolor (lutgen):
 * каждый пиксель становится смесью цветов палитры с гауссовыми весами по расстоянию
 * (в RGB с весами восприятия), так что переходы остаются плавными. [shape] — ширина
 * гауссианы: меньше — ближе к «ближайшему цвету», больше — мягче. Чистый Kotlin.
 *
 * Для скорости ответ кэшируется по 5-битному цвету (32³ ячеек).
 */
object Recolor {

    fun palette(theme: Look.Theme): IntArray {
        val keys = listOf(
            "background", "foreground", "accent", "muted",
            "red", "green", "yellow", "blue", "magenta", "cyan",
            "bright_red", "bright_green", "bright_yellow", "bright_blue", "bright_magenta", "bright_cyan",
            "black", "white", "bright_black", "bright_white",
        )
        return keys.mapNotNull { theme.colors[it] }.distinct().toIntArray()
    }

    fun recolor(pixels: IntArray, palette: IntArray, shape: Float = 28f): IntArray {
        require(palette.isNotEmpty())
        val pr = IntArray(palette.size) { (palette[it] shr 16) and 0xFF }
        val pg = IntArray(palette.size) { (palette[it] shr 8) and 0xFF }
        val pb = IntArray(palette.size) { palette[it] and 0xFF }
        val cache = IntArray(32 * 32 * 32)
        val known = BooleanArray(cache.size)
        val k = 1f / (2f * shape * shape)
        val weights = FloatArray(palette.size)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val slot = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            if (!known[slot]) {
                // Центр 5-битной ячейки.
                val cr = (r and 0xF8) + 4
                val cg = (g and 0xF8) + 4
                val cb = (b and 0xF8) + 4
                var sum = 0f
                var best = 0
                var bestD = Float.MAX_VALUE
                for (j in palette.indices) {
                    val dr = (cr - pr[j]).toFloat()
                    val dg = (cg - pg[j]).toFloat()
                    val db = (cb - pb[j]).toFloat()
                    // Веса восприятия (redmean упрощённо): зелёный важнее.
                    val d = 0.3f * dr * dr + 0.59f * dg * dg + 0.11f * db * db
                    if (d < bestD) {
                        bestD = d
                        best = j
                    }
                    val w = exp(-d * k)
                    weights[j] = w
                    sum += w
                }
                cache[slot] = if (sum < 1e-6f) {
                    palette[best] and 0xFFFFFF
                } else {
                    var or = 0f
                    var og = 0f
                    var ob = 0f
                    for (j in palette.indices) {
                        val w = weights[j] / sum
                        or += w * pr[j]
                        og += w * pg[j]
                        ob += w * pb[j]
                    }
                    (or.toInt().coerceIn(0, 255) shl 16) or (og.toInt().coerceIn(0, 255) shl 8) or ob.toInt().coerceIn(0, 255)
                }
                known[slot] = true
            }
            out[i] = (a shl 24) or cache[slot]
        }
        return out
    }
}
