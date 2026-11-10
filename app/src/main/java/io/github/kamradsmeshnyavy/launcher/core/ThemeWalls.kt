package io.github.kamradsmeshnyavy.launcher.core

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.kamradsmeshnyavy.launcher.deck.DeckClient
import io.github.kamradsmeshnyavy.launcher.deck.DeckStore
import java.util.concurrent.Executors

/**
 * Обои тем Omarchy на телефоне. В APK их нет (чужие картинки, сотни мегабайт) — они
 * приходят с сопряжённого компьютера (omadeck: /v1/backgrounds), обрезанные под экран.
 *
 * Настройки (SharedPreferences "look"):
 *   themeWallpaper   bool    обои вместе с темой: выбрал тему — поставились её первые обои
 *   wallpaperTarget  enum    home | lock | both — куда ставить
 *   wallpaperFx      enum    plain | dotify | recolor — как есть, LED-матрицей или в цвета темы
 */
object ThemeWalls {
    const val FOLLOW = "themeWallpaper"
    const val TARGET = "wallpaperTarget"
    const val FX = "wallpaperFx"

    private val pool = Executors.newFixedThreadPool(3)

    private fun client(context: Context): DeckClient? {
        val store = DeckStore.get(context)
        val computer = store.computer ?: return null
        val token = store.token() ?: return null
        return DeckClient(computer, token)
    }

    fun paired(context: Context): Boolean = DeckStore.get(context).computer != null

    /** Имена обоев темы [theme] на компьютере; null — компьютер не ответил. */
    fun list(context: Context, theme: String): List<String>? {
        val reply = client(context)?.call("GET", "/v1/backgrounds?theme=" + Uri.encode(theme), null, context) ?: return null
        val items = reply.takeIf { it.status == 200 }?.json?.optJSONArray("items") ?: return null
        return List(items.length()) { items.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
    }

    fun image(context: Context, theme: String, name: String, width: Int, height: Int): Bitmap? {
        val path = "/v1/backgrounds/image?theme=" + Uri.encode(theme) + "&name=" + Uri.encode(name) + "&w=$width&h=$height"
        val bytes = client(context)?.call("GET", path)?.takeIf { it.status == 200 }?.bytes ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun screen(context: Context): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return minOf(m.widthPixels, m.heightPixels).coerceIn(64, 4096) to maxOf(m.widthPixels, m.heightPixels).coerceIn(64, 4096)
    }

    /** Блокирующе: обои [name] темы [theme] — на экран(ы) из wallpaperTarget, с wallpaperFx. */
    fun apply(context: Context, theme: String, name: String): Boolean {
        val (w, h) = screen(context)
        val photo = image(context, theme, name, w, h) ?: return false
        val look = Look.current(context)
        val result = when (look.str(FX, "plain")) {
            "dotify" -> {
                val pitch = (context.resources.displayMetrics.density * look.num("dotPitch", 9.0) * 0.75).toInt().coerceAtLeast(3)
                DotRender.render(
                    DotRender.grid(photo, pitch, look.num("dotGamma", 1.25).toFloat(), look.num("dotOff", 0.07).toFloat()),
                    w, h, look.color("dotTint", "foreground"), look.color("dotBackground", "background"),
                )
            }
            "recolor" -> look.theme?.let { DotRender.recolor(photo, Recolor.palette(it)) } ?: photo
            else -> photo
        }
        val which = when (look.str(TARGET, "both")) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        return runCatching { WallpaperManager.getInstance(context).setBitmap(result, null, true, which) }.isSuccess
    }

    /** Тема сменилась: с «обоями вместе с темой» — её первые обои, в фоне. */
    fun themeChanged(context: Context, theme: String?) {
        if (theme.isNullOrEmpty() || !paired(context)) return
        if (!Look.prefs(context).getBoolean(FOLLOW, true)) return
        val app = context.applicationContext
        pool.execute {
            runCatching {
                val first = list(app, theme)?.firstOrNull() ?: return@runCatching
                apply(app, theme, first)
            }
        }
    }

    fun execute(task: () -> Unit) = pool.execute(task)
}
