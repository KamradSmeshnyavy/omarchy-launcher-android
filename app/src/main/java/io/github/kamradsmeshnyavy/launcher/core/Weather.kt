package io.github.kamradsmeshnyavy.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.kamradsmeshnyavy.launcher.deck.DeckClient
import io.github.kamradsmeshnyavy.launcher.deck.DeckStore
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/**
 * Погода в часах, как виджет погоды Omarchy: данные Open-Meteo (без ключа и аккаунта),
 * место — то же, что у компьютера (omadeck: /v1/weather, из omarchy-weather-location),
 * или свой город (геокодер Open-Meteo). Наружу уходят только координаты.
 *
 * Настройки (SharedPreferences "look"):
 *   weather         bool   показывать в часах
 *   weatherSource   enum   computer | city
 *   weatherCity     text   город для source=city
 *   weatherUnits    enum   auto | c | f
 *   weatherApp      text   пакет, который открывается по нажатию (пусто — сам найдёт)
 */
object Weather {
    const val SHOW = "weather"
    const val SOURCE = "weatherSource"
    const val CITY = "weatherCity"
    const val UNITS = "weatherUnits"
    const val APP = "weatherApp"
    private const val CACHE = "look.weatherCache"
    private const val FRESH = 30 * 60 * 1000L

    enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    class Now(val temperature: Double, val code: Int, val day: Boolean, val place: String, val at: Long, val country: String = "") {
        val kind: Kind get() = kindOf(code)
    }

    /** WMO-код погоды → вид значка. */
    fun kindOf(code: Int): Kind = when (code) {
        0 -> Kind.CLEAR
        1, 2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUDY
    }

    private val IMPERIAL = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

    /**
     * Фаренгейт — как в погоде Omarchy: по стране места (США и немногие ещё), а не по языку
     * телефона; страна неизвестна — Цельсий. Или как задано.
     */
    fun fahrenheit(units: String, country: String): Boolean = when (units) {
        "f" -> true
        "c" -> false
        else -> country.uppercase(Locale.ROOT) in IMPERIAL
    }

    fun format(now: Now, units: String): String {
        val f = fahrenheit(units, now.country)
        val r = (if (f) now.temperature * 9 / 5 + 32 else now.temperature).roundToInt()
        return (if (r < 0) "−" + (-r) else r.toString()) + if (f) "°F" else "°"
    }

    private val pool = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun cached(context: Context): Now? = runCatching {
        val o = JSONObject(Look.prefs(context).getString(CACHE, null) ?: return null)
        Now(o.getDouble("t"), o.getInt("code"), o.optBoolean("day", true), o.optString("place"), o.getLong("at"), o.optString("country"))
    }.getOrNull()

    /** В фоне, если кэш старше получаса (или [force]); [done] — на главном потоке. */
    fun refresh(context: Context, force: Boolean = false, done: (Now?) -> Unit) {
        val app = context.applicationContext
        val sp = Look.prefs(app)
        if (!sp.getBoolean(SHOW, true)) return
        val have = cached(app)
        if (!force && have != null && System.currentTimeMillis() - have.at < FRESH) {
            done(have)
            return
        }
        pool.execute {
            val fresh = runCatching { fetch(app) }.getOrNull()
            if (fresh != null) {
                sp.edit().putString(
                    CACHE,
                    JSONObject().put("t", fresh.temperature).put("code", fresh.code).put("day", fresh.day)
                        .put("place", fresh.place).put("at", fresh.at).put("country", fresh.country).toString(),
                ).apply()
            }
            main.post { done(fresh ?: have) }
        }
    }

    private class Place(val latitude: Double, val longitude: Double, val name: String, val country: String)

    private fun location(context: Context): Place? {
        val sp = Look.prefs(context)
        if (sp.getString(SOURCE, "computer") == "city") {
            val city = sp.getString(CITY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val lang = Locale.getDefault().language
            val o = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=$lang&name=" + URLEncoder.encode(city, "UTF-8"))
            val r = o.optJSONArray("results")?.optJSONObject(0) ?: return null
            return Place(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", city), r.optString("country_code"))
        }
        val store = DeckStore.get(context)
        val computer = store.computer ?: return null
        val token = store.token() ?: return null
        val o = DeckClient(computer, token).call("GET", "/v1/weather", null, context)?.takeIf { it.status == 200 }?.json ?: return null
        return Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("name"), o.optString("country"))
    }

    private fun fetch(context: Context): Now? {
        val place = location(context) ?: return null
        val o = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
                "&current=temperature_2m,weather_code,is_day&timezone=auto",
        )
        val c = o.getJSONObject("current")
        return Now(c.getDouble("temperature_2m"), c.getInt("weather_code"), c.optInt("is_day", 1) == 1, place.name, System.currentTimeMillis(), place.country)
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "DOT-MATRIX Launcher")
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            connection.disconnect()
        }
    }

    // Приложения погоды, которые ищутся сами, если weatherApp не задан: Pixel Weather,
    // Breezy Weather, Geometric, OnePlus/Samsung/Xiaomi погода.
    private val KNOWN = listOf(
        "com.google.android.apps.weather", "org.breezyweather", "com.amazing.weatherapp", "com.oneplus.weather",
        "com.sec.android.daemonapp", "com.miui.weather2", "com.huawei.android.totemweather", "net.oneplus.weather",
    )

    /** Что открыть по нажатию на погоду: выбранное приложение, известное, или поиск «погода». */
    fun intent(context: Context): Intent {
        val pm = context.packageManager
        val chosen = Look.prefs(context).getString(APP, null)?.takeIf { it.isNotEmpty() }
        for (pkg in listOfNotNull(chosen) + KNOWN) {
            pm.getLaunchIntentForPackage(pkg)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        // Погода внутри приложения Google (на Pixel без отдельной «Погоды»).
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("dynact://velour/weather/ProxyActivity"))
            .setComponent(ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.velour.DynamicActivityTrampoline"))
        if (pm.resolveActivity(google, 0) != null) return google.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val place = cached(context)?.place.orEmpty()
        val word = if (Locale.getDefault().language == "ru") "погода" else "weather"
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("$word $place".trim())))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
