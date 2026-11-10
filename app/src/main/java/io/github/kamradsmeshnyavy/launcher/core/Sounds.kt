package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.InputStream

/**
 * Звуки интерфейса из пакетов Customizer (assets/sounds/<пакет>/<событие>.ogg): sounds — вкл.,
 * soundPack — пакет (theme — пакет стиля), soundVolume — громкость. Пакет [PC] — звуки темы
 * Omarchy с компьютера (filesDir/sounds/pc, их скачивает PcLook). Чего в пакете нет —
 * берётся ближайшее ([FALLBACK]), иначе молчит. Звучит как системные клики (sonification):
 * тихий режим телефона их глушит.
 */
object Sounds {
    const val PC = "pc"

    enum class Event(val file: String) {
        OPEN("open"), CLOSE("close"), MENU("menu"), SWITCH("switch"),
        NOTIFY("notify"), CRITICAL("critical"), CHARGE("charge"), LOCK("lock"),
        VOLUME("volume"), SPECIAL("special"), POWER("power"),
    }

    /** Чем заменить отсутствующий звук — по порядку (как omarchy-sound на компьютере). */
    private val FALLBACK = mapOf(
        "charge" to listOf("charge", "power", "special", "open"),
        "critical" to listOf("critical", "power", "special", "notify"),
        "notify" to listOf("notify", "special", "open", "menu"),
        "volume" to listOf("volume", "switch", "menu"),
        "special" to listOf("special", "notify", "open"),
        "power" to listOf("power", "lock", "open"),
        "lock" to listOf("lock", "power", "close"),
    )

    private var pool: SoundPool? = null
    private var loadedPack: String? = null
    private val ids = HashMap<Event, Int>()

    /** Пакет, который звучит сейчас: свой выбор, пакет стиля (theme) или звуки компьютера. */
    fun pack(look: Look.Resolved): String {
        val chosen = look.str("soundPack", "dotmatrix")
        if (chosen != "theme") return chosen
        return look.style?.sounds?.optString("soundPack")?.takeIf { it.isNotEmpty() && it != "theme" } ?: "dotmatrix"
    }

    fun pcDir(context: Context): File = File(context.applicationContext.filesDir, "sounds/$PC")

    /** Есть ли в пакете [pack] звук [name] (без замен). */
    fun has(context: Context, pack: String, name: String): Boolean =
        if (pack == PC) {
            File(pcDir(context), "$name.ogg").isFile
        } else {
            runCatching { context.assets.openFd("sounds/$pack/$name.ogg").close() }.isSuccess
        }

    /** Звук [name] пакета [pack] с заменами; null — нечем. */
    fun open(context: Context, pack: String, name: String): InputStream? {
        for (alt in FALLBACK[name] ?: listOf(name)) {
            if (!has(context, pack, alt)) continue
            return if (pack == PC) File(pcDir(context), "$alt.ogg").inputStream() else context.assets.open("sounds/$pack/$alt.ogg")
        }
        return null
    }

    fun play(context: Context, event: Event) {
        val look = Look.now
        if (!look.bool("sounds", false)) return
        val app = context.applicationContext
        val pack = pack(look)
        if (pack != loadedPack) load(app, pack)
        val id = ids[event] ?: return
        val volume = look.num("soundVolume", 0.6).toFloat().coerceIn(0f, 1f)
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    /** Звуки компьютера обновились (те же имена, новые файлы): перечитать при следующем звуке. */
    fun reload() {
        loadedPack = null
    }

    @Synchronized
    private fun load(context: Context, pack: String) {
        pool?.release()
        ids.clear()
        val next = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        for (event in Event.entries) {
            val name = (FALLBACK[event.file] ?: listOf(event.file)).firstOrNull { has(context, pack, it) } ?: continue
            runCatching {
                if (pack == PC) {
                    ids[event] = next.load(File(pcDir(context), "$name.ogg").path, 1)
                } else {
                    context.assets.openFd("sounds/$pack/$name.ogg").use { fd -> ids[event] = next.load(fd, 1) }
                }
            }
        }
        pool = next
        loadedPack = pack
    }
}
