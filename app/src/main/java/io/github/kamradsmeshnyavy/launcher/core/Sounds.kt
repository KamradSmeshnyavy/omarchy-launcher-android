package io.github.kamradsmeshnyavy.launcher.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Звуки интерфейса из пакетов Customizer (assets/sounds/<пакет>/<событие>.ogg): sounds — вкл.,
 * soundPack — пакет (theme — пакет стиля), soundVolume — громкость. Чего в пакете нет —
 * то молчит. Звучит как системные клики (sonification): тихий режим телефона их глушит.
 */
object Sounds {
    enum class Event(val file: String) {
        OPEN("open"), CLOSE("close"), MENU("menu"), SWITCH("switch"),
        NOTIFY("notify"), CRITICAL("critical"), CHARGE("charge"), LOCK("lock"),
    }

    private var pool: SoundPool? = null
    private var loadedPack: String? = null
    private val ids = HashMap<Event, Int>()

    private fun pack(look: Look.Resolved): String {
        val chosen = look.str("soundPack", "dotmatrix")
        if (chosen != "theme") return chosen
        return look.style?.sounds?.optString("soundPack")?.takeIf { it.isNotEmpty() && it != "theme" } ?: "dotmatrix"
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
            runCatching {
                context.assets.openFd("sounds/$pack/${event.file}.ogg").use { fd -> ids[event] = next.load(fd, 1) }
            }
        }
        pool = next
        loadedPack = pack
    }
}
