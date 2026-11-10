package io.github.kamradsmeshnyavy.launcher.core

import android.app.WallpaperManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import io.github.kamradsmeshnyavy.launcher.deck.DeckClient
import io.github.kamradsmeshnyavy.launcher.deck.DeckStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * «Следовать компьютеру»: тема, стиль Customizer, Glyph, звуки, виджеты, шрифты и обои —
 * как на сопряжённом Omarchy. Телефон спрашивает штамп в /v1/state и, если он другой,
 * берёт /v1/look (снимок кладётся в Look.PC_SNAPSHOT и перекрывает свой выбор), а если
 * сменились обои — /v1/wallpaper под размер экрана.
 *
 * Когда: при каждом возврате на домашний экран (не чаще [MIN_GAP]) и фоновой задачей
 * раз в 15 минут, пока включено. Только по сети до своего компьютера, с пином.
 */
object PcLook {
    private const val STAMP = "look.pcStamp"
    private const val WALL = "look.pcWall"
    private const val MIN_GAP = 30_000L
    private const val JOB = 0x0DEC

    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var last = 0L

    fun enabled(context: Context): Boolean = Look.prefs(context).getBoolean(Look.FOLLOW_PC, false)

    /** В фоне; [force] — без проверки штампа и паузы. */
    fun syncSoon(context: Context, force: Boolean = false) {
        if (!enabled(context)) return
        val now = System.currentTimeMillis()
        if (!force && now - last < MIN_GAP) return
        last = now
        val app = context.applicationContext
        pool.execute { runCatching { sync(app, force) } }
    }

    /** Блокирующий проход; true, если что-то поменялось. */
    fun sync(context: Context, force: Boolean = false): Boolean {
        if (!enabled(context)) return false
        val store = DeckStore.get(context)
        val computer = store.computer ?: return false
        val token = store.token() ?: return false
        val client = DeckClient(computer, token)
        val sp = Look.prefs(context)

        val state = client.call("GET", "/v1/state", null, context)?.takeIf { it.status == 200 }?.json ?: return false
        store.save(computer)
        val stamp = state.optString("look")
        if (!force && stamp.isNotEmpty() && stamp == sp.getString(STAMP, null)) return false

        val look = client.call("GET", "/v1/look", null, context)?.takeIf { it.status == 200 }?.json ?: return false
        val changedLook = look.toString() != sp.getString(Look.PC_SNAPSHOT, null)
        sp.edit().putString(Look.PC_SNAPSHOT, look.toString()).putString(STAMP, look.optString("stamp", stamp)).apply()

        // Обои — только когда они другие: картинка тяжёлая.
        val wall = look.optString("wallpaper")
        var changedWall = false
        if (wall.isNotEmpty() && (force || wall != sp.getString(WALL, null))) {
            val metrics = context.resources.displayMetrics
            val w = minOf(metrics.widthPixels, metrics.heightPixels).coerceIn(64, 4096)
            val h = maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(64, 4096)
            val bytes = client.call("GET", "/v1/wallpaper?w=$w&h=$h")?.takeIf { it.status == 200 }?.bytes
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                changedWall = runCatching {
                    WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                }.isSuccess
                if (changedWall) sp.edit().putString(WALL, wall).apply()
            }
        }
        if (changedLook) main.post { Look.external(context) }
        return changedLook || changedWall
    }

    /** Включить/выключить: снимок уходит вместе с выключением, остаётся свой выбор. */
    fun setEnabled(context: Context, on: Boolean) {
        val sp = Look.prefs(context)
        val edit = sp.edit().putBoolean(Look.FOLLOW_PC, on)
        if (!on) edit.remove(Look.PC_SNAPSHOT).remove(STAMP).remove(WALL)
        edit.apply()
        schedule(context, on)
        if (on) syncSoon(context, force = true) else Look.invalidate(context)
    }

    fun schedule(context: Context, on: Boolean = enabled(context)) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!on) {
            jobs.cancel(JOB)
            return
        }
        if (jobs.getPendingJob(JOB) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB, ComponentName(context, Job::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setPersisted(false)
                .build(),
        )
    }

    /** Фоновая проверка раз в 15 минут (только в Wi-Fi/безлимитной сети). */
    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            val app = applicationContext
            pool.execute {
                runCatching { sync(app) }
                jobFinished(params, false)
            }
            return true
        }

        override fun onStopJob(params: JobParameters): Boolean = true
    }
}
