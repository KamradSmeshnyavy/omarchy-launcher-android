package io.github.kamradsmeshnyavy.launcher.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import io.github.kamradsmeshnyavy.launcher.core.Look

/**
 * Токены интерфейса. В апстриме (NIHIL) это были константы монохрома; здесь они идут
 * от текущей темы Omarchy и стиля Customizer (core/Look), а имена остались прежними,
 * чтобы код апстрима работал как есть. Иерархия — оттенками между фоном и текстом,
 * акцент темы — только чтобы прервать.
 */
object Nihil {
    private val p get() = Look.now.palette

    val VOID get() = p.bg
    val SURFACE get() = p.surface
    val RAISED get() = p.raised
    val LINE get() = p.line
    val OFF get() = p.off
    val MUTED get() = p.muted
    val DIM get() = p.dim
    val INK get() = p.fg
    val SIGNAL get() = p.signal
    val DANGER get() = p.danger
    val ON_SIGNAL get() = p.onSignal
    val DARK get() = p.dark

    // Движение стиля: motion 0 — без анимаций (Ретро), 1.25 — плавнее (Стекло).
    private fun scaled(ms: Long): Long = (ms * Look.now.motion).toLong()
    val FAST get() = scaled(140L)
    val NORMAL get() = scaled(240L)
    val DRAWER get() = scaled(320L)
    val SLOW get() = scaled(480L)

    /** Скругления стиля: 0 у Брутализма и Ретро, 1.3 у Неоморфизма. */
    fun corner(radius: Float): Float = radius * Look.now.cornerScale

    /** Content appears at the end of an opening, like LEDs switching on. */
    fun reveal(progress: Float): Float = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)

    @Volatile
    private var loaded: Fonts? = null

    fun fonts(context: Context): Fonts {
        val profile = Look.now.fontProfile
        loaded?.takeIf { it.profile == profile }?.let { return it }
        return synchronized(this) {
            loaded?.takeIf { it.profile == profile } ?: Fonts(context.applicationContext, profile).also { loaded = it }
        }
    }

    /**
     * Голоса интерфейса по профилю шрифтов Customizer: dot — точечный для дисплея,
     * sans — текст, mono — метаданные.
     */
    class Fonts(context: Context, val profile: String) {
        val dot: Typeface
        val sans: Typeface
        val sansMedium: Typeface
        val mono: Typeface
        val monoBold: Typeface

        init {
            val doto = load(context, "Doto-Bold.ttf")
            val dmDot = load(context, "DotMatrixDot.ttf", doto)
            val grotesk = load(context, "SpaceGrotesk-Regular.ttf")
            val groteskMedium = load(context, "SpaceGrotesk-Medium.ttf")
            val spaceMono = load(context, "SpaceMono-Regular.ttf")
            val spaceMonoBold = load(context, "SpaceMono-Bold.ttf")
            val geist = load(context, "Geist.ttf", grotesk)
            val geistMedium = Typeface.create(geist, 500, false)
            fun dm(name: String, fallback: Typeface) = load(context, name, fallback)
            when (profile) {
                "nothing", "dot" -> {
                    dot = dmDot; sans = geist; sansMedium = geistMedium
                    mono = dm("DotMatrixMono-Regular.ttf", spaceMono); monoBold = dm("DotMatrixMono-Bold.ttf", spaceMonoBold)
                }
                "pixel", "dot-accents" -> {
                    dot = dmDot; sans = geist; sansMedium = geistMedium
                    mono = dm("DotMatrixPixel-Regular.ttf", spaceMono); monoBold = Typeface.create(mono, Typeface.BOLD)
                }
                "lumen" -> {
                    dot = dmDot; sans = geist; sansMedium = geistMedium
                    mono = dm("DotMatrixLumenMono-Regular.ttf", spaceMono); monoBold = Typeface.create(mono, Typeface.BOLD)
                }
                "paper-ui" -> {
                    val hand = dm("Pangolin-Regular.ttf", grotesk)
                    dot = dm("Caveat.ttf", doto); sans = hand; sansMedium = hand
                    mono = spaceMono; monoBold = spaceMonoBold
                }
                "retro" -> {
                    val pixel = dm("PixelifySans.ttf", grotesk)
                    dot = pixel; sans = pixel; sansMedium = Typeface.create(pixel, 500, false)
                    mono = dm("DotMatrixPixel-Regular.ttf", spaceMono); monoBold = Typeface.create(mono, Typeface.BOLD)
                }
                else -> if (profile.startsWith("dots-all")) {
                    val lumenSans = dm("DotMatrixLumenSans-Regular.ttf", geist)
                    dot = dmDot; sans = lumenSans; sansMedium = Typeface.create(lumenSans, Typeface.BOLD)
                    mono = dm("DotMatrixLumenMono-Regular.ttf", spaceMono); monoBold = Typeface.create(mono, Typeface.BOLD)
                } else {
                    // stock: как в NIHIL — Doto, Space Grotesk, Space Mono.
                    dot = doto; sans = grotesk; sansMedium = groteskMedium; mono = spaceMono; monoBold = spaceMonoBold
                }
            }
        }

        private fun load(context: Context, name: String, fallback: Typeface = Typeface.DEFAULT): Typeface =
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$name") }.getOrDefault(fallback)
    }
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dp(value: Int): Int = (dp(value.toFloat()) + 0.5f).toInt()
