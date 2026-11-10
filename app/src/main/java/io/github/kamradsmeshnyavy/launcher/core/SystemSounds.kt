package io.github.kamradsmeshnyavy.launcher.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Звуки во всей системе: звонок, уведомления и будильник по умолчанию — мелодии пакета
 * (ringtone/notification/alarm.ogg, собраны из его звуков). Файлы кладутся в медиатеку
 * (Ringtones|Notifications|Alarms/DOT-MATRIX) и ставятся через RingtoneManager — для этого
 * Android просит спецразрешение «Изменение системных настроек» (WRITE_SETTINGS).
 *
 * Прежние системные звуки запоминаются перед первой заменой — [restore] возвращает их и
 * убирает свои файлы. Приложения со своим звуком в канале уведомлений его сохранят.
 */
object SystemSounds {
    enum class Kind(val key: String, val file: String, val type: Int, val folder: String, val flag: String) {
        RINGTONE("sysRingtone", "ringtone", RingtoneManager.TYPE_RINGTONE, Environment.DIRECTORY_RINGTONES, MediaStore.Audio.AudioColumns.IS_RINGTONE),
        NOTIFICATION("sysNotification", "notification", RingtoneManager.TYPE_NOTIFICATION, Environment.DIRECTORY_NOTIFICATIONS, MediaStore.Audio.AudioColumns.IS_NOTIFICATION),
        ALARM("sysAlarm", "alarm", RingtoneManager.TYPE_ALARM, Environment.DIRECTORY_ALARMS, MediaStore.Audio.AudioColumns.IS_ALARM),
    }

    private const val FOLDER = "DOT-MATRIX"
    private const val BACKUP = "look.sysBackup"
    private const val APPLIED = "look.sysApplied"

    private val pool = Executors.newSingleThreadExecutor()

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /** Экран системы, где разрешают «Изменение системных настроек» именно этому приложению. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))

    fun enabled(context: Context, kind: Kind): Boolean = Look.prefs(context).getBoolean(kind.key, false)

    fun anyEnabled(context: Context): Boolean = Kind.entries.any { enabled(context, it) }

    /** Что стоит сейчас (название звука в системе) — для подписи. */
    fun current(context: Context, kind: Kind): String = runCatching {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, kind.type) ?: return ""
        RingtoneManager.getRingtone(context, uri)?.getTitle(context).orEmpty()
    }.getOrDefault("")

    /** Пакет, который сейчас стоит в системе (или null). */
    fun applied(context: Context): String? = Look.prefs(context).getString(APPLIED, null)

    /**
     * Поставить мелодии пакета [pack] для включённых видов. В фоне; [done] — на главном потоке
     * с числом заменённых (−1 — нет разрешения).
     */
    fun apply(context: Context, pack: String, done: (Int) -> Unit = {}) {
        val app = context.applicationContext
        pool.execute {
            val n = runCatching { applyNow(app, pack) }.getOrDefault(0)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(n) }
        }
    }

    private fun applyNow(context: Context, pack: String): Int {
        if (!canWrite(context)) return -1
        val sp = Look.prefs(context)
        val backup = JSONObject(sp.getString(BACKUP, null) ?: "{}")
        var n = 0
        for (kind in Kind.entries) {
            if (!enabled(context, kind)) continue
            // Запомнить чужой (системный) звук один раз — до первой замены.
            if (!backup.has(kind.file)) {
                val was = RingtoneManager.getActualDefaultRingtoneUri(context, kind.type)
                if (was == null || !isOurs(context, was)) backup.put(kind.file, was?.toString() ?: "")
            }
            val uri = install(context, pack, kind) ?: continue
            RingtoneManager.setActualDefaultRingtoneUri(context, kind.type, uri)
            n++
        }
        sp.edit().putString(BACKUP, backup.toString()).putString(APPLIED, pack).apply()
        return n
    }

    /** Вернуть прежние звуки системы (для всех трёх) и убрать свои файлы из медиатеки. */
    fun restore(context: Context, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        pool.execute {
            val ok = runCatching {
                if (!canWrite(app)) return@runCatching false
                val sp = Look.prefs(app)
                val backup = JSONObject(sp.getString(BACKUP, null) ?: "{}")
                for (kind in Kind.entries) {
                    if (!backup.has(kind.file)) continue
                    val was = backup.optString(kind.file).takeIf { it.isNotEmpty() }?.let(Uri::parse)
                    RingtoneManager.setActualDefaultRingtoneUri(app, kind.type, was)
                }
                Kind.entries.forEach { removeOurs(app, it) }
                sp.edit().remove(BACKUP).remove(APPLIED).apply {
                    Kind.entries.forEach { remove(it.key) }
                }.apply()
                true
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }
    }

    /**
     * Пакет сменился (вручную, стилем или с компьютера): если звуки системы включены и
     * разрешение есть — переставить. Вызывается при каждом пересчёте Look.
     */
    fun follow(context: Context, pack: String) {
        // Только после явного «Поставить» (и не после «Вернуть»): сам лаунчер звуки не трогает.
        val was = applied(context) ?: return
        if (!anyEnabled(context) || !canWrite(context)) return
        if (was == pack && pack != Sounds.PC) return
        apply(context, pack)
    }

    // --- Медиатека ------------------------------------------------------------------------

    private fun relative(kind: Kind) = kind.folder + "/" + FOLDER + "/"

    private fun isOurs(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            c.moveToFirst() && c.getString(0)?.endsWith("/$FOLDER/") == true
        } ?: false
    }.getOrDefault(false)

    /** Свои прошлые файлы этого вида — убрать (приложение видит и может удалять только свои). */
    private fun removeOurs(context: Context, kind: Kind) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf(relative(kind), context.packageName),
            )
        }
    }

    private fun install(context: Context, pack: String, kind: Kind): Uri? {
        val source = Sounds.open(context, pack, kind.file) ?: return null
        removeOurs(context, kind)
        val title = "DOT-MATRIX · " + pack.replaceFirstChar { it.titlecase() }
        val values = ContentValues().apply {
            // Название в системе берётся из имени файла: «DOT-MATRIX Brutal ringtone».
            put(MediaStore.MediaColumns.DISPLAY_NAME, "DOT-MATRIX ${pack.replaceFirstChar { it.titlecase() }} ${kind.file}.ogg")
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/ogg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative(kind))
            put(kind.flag, 1)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            source.use { input -> resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) } ?: error("no stream") }
        }.isSuccess
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
