package io.github.kamradsmeshnyavy.launcher.ui

import android.graphics.Bitmap
import android.widget.ImageView
import io.github.kamradsmeshnyavy.launcher.core.AppEntry
import io.github.kamradsmeshnyavy.launcher.core.Apps
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.IconStyle
import io.github.kamradsmeshnyavy.launcher.core.WebEntry
import io.github.kamradsmeshnyavy.launcher.core.Webs

/**
 * [entry]'s icon here: at once when it is cached, or once painted, if this still shows it.
 * [dots] — значок светодиодами (скин ящика dotmatrix) цвета [ink].
 */
fun ImageView.bindIcon(entry: Entry, size: Int, style: IconStyle, dots: Boolean = false, ink: Int = 0) {
    tag = entry.key
    setImageDrawable(null)
    val show: (Bitmap) -> Bitmap = { if (dots) DotIcons.of(entry.key, it, ink) else it }
    val ready: (Bitmap) -> Unit = { bitmap -> if (tag == entry.key) setImageBitmap(show(bitmap)) }
    val cached = when (entry) {
        is AppEntry -> Apps.icon(entry, size, style, ready)
        is WebEntry -> Webs.icon(context, entry, size, style, ready)
    }
    if (cached != null) setImageBitmap(show(cached))
}

/**
 * Значок как на светодиодной матрице: сетка [CELLS]×[CELLS], точка горит, где у значка есть
 * рисунок (непрозрачный и не фон), остальные — едва видны. Кэш по ключу и цвету.
 */
object DotIcons {
    private const val CELLS = 13
    private val cache = android.util.LruCache<String, Bitmap>(256)

    fun of(key: String, icon: Bitmap, ink: Int): Bitmap {
        val id = "$key:$ink:${icon.width}"
        cache.get(id)?.let { return it }
        val n = icon.width.coerceAtLeast(1)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val small = Bitmap.createScaledBitmap(icon, CELLS, CELLS, true)
        // Фон значка (угол) — не рисунок: так у значков с подложкой видна сама фигура.
        val corner = small.getPixel(CELLS / 2, 0)
        val step = n / CELLS.toFloat()
        for (y in 0 until CELLS) for (x in 0 until CELLS) {
            val px = small.getPixel(x, y)
            val alpha = (px ushr 24) / 255f
            val diff = if ((corner ushr 24) > 200) colorDistance(px, corner) else 1f
            val on = alpha > 0.45f && diff > 0.18f
            paint.color = if (on) ink else (ink and 0x00FFFFFF) or 0x24000000
            canvas.drawCircle((x + 0.5f) * step, (y + 0.5f) * step, step * (if (on) 0.4f else 0.22f), paint)
        }
        if (small !== icon) small.recycle()
        cache.put(id, out)
        return out
    }

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441f
    }
}
