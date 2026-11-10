package io.github.kamradsmeshnyavy.launcher.core

import kotlin.math.max
import kotlin.math.min

/**
 * Как перекрасить чужой виджет под тему (чистая математика, тесты на JVM):
 *
 * — тональные ряды Material You из палитры темы: Android 12+ даёт лаунчеру подменить
 *   системные dynamic colors для виджета (AppWidgetHostView.setColorResources); виджеты,
 *   которые на них построены (Google и многие другие), становятся в цвета темы;
 * — двуцвет: матрица 4×5 для ColorMatrix, переводящая любой пиксель в смесь фона и текста
 *   темы по яркости — так тонируется любой виджет, даже со своими цветами.
 */
object WidgetTint {
    /** Тона ряда Material: 0 — светлейший, 1000 — чёрный. */
    val TONES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    /** Ряд из 13 цветов вокруг [base]: оттенок и насыщенность — его, светлота — по тону. */
    fun ramp(base: Int, saturation: Float? = null): IntArray {
        val (h, s, _) = hsl(base)
        val sat = saturation ?: s
        return IntArray(TONES.size) { i ->
            val l = when (TONES[i]) {
                0 -> 1f
                10 -> 0.99f
                50 -> 0.95f
                1000 -> 0f
                else -> 1f - TONES[i] / 1000f
            }
            fromHsl(h, if (TONES[i] == 0 || TONES[i] == 1000) 0f else sat, l)
        }
    }

    /**
     * Матрица двуцвета (ColorMatrix, строки R G B A по 5 чисел): яркость пикселя (0…1) —
     * доля пути от [dark] к [light], альфа — как была.
     */
    fun duotone(dark: Int, light: Int): FloatArray {
        val m = FloatArray(20)
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        for (row in 0 until 3) {
            val shift = 16 - row * 8
            val d = ((dark shr shift) and 0xFF).toFloat()
            val l = ((light shr shift) and 0xFF).toFloat()
            val k = (l - d) / 255f
            for (col in 0 until 3) m[row * 5 + col] = lum[col] * k
            m[row * 5 + 4] = d
        }
        m[18] = 1f
        return m
    }

    /** Применить матрицу к цвету (для тестов и предпросмотра). */
    fun apply(matrix: FloatArray, color: Int): Int {
        val r = ((color shr 16) and 0xFF).toFloat()
        val g = ((color shr 8) and 0xFF).toFloat()
        val b = (color and 0xFF).toFloat()
        val a = ((color ushr 24) and 0xFF).toFloat()
        fun ch(row: Int): Int = (matrix[row * 5] * r + matrix[row * 5 + 1] * g + matrix[row * 5 + 2] * b + matrix[row * 5 + 3] * a + matrix[row * 5 + 4])
            .toInt().coerceIn(0, 255)
        return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }

    fun hsl(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
            g -> ((b - r) / d + 2) / 6
            else -> ((r - g) / d + 4) / 6
        }
        return Triple(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        fun hue(p: Float, q: Float, t0: Float): Float {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        val (r, g, b) = if (s == 0f) Triple(l, l, l) else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            Triple(hue(p, q, h + 1f / 3), hue(p, q, h), hue(p, q, h - 1f / 3))
        }
        return (0xFF shl 24) or ((r * 255 + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255 + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b * 255 + 0.5f).toInt().coerceIn(0, 255)
    }
}
