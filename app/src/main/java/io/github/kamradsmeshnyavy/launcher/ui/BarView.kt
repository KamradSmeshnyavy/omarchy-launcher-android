package io.github.kamradsmeshnyavy.launcher.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import io.github.kamradsmeshnyavy.launcher.core.BarSkin
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Бар лаунчера в скине бара Omarchy: подложка по shape (full, floating, pill, islands,
 * notch, underline, hug, frame), фон/контур/свечение/жёсткая тень/бевел по токенам, цвета —
 * роли темы. Слева — рабочие столы (подписи по wsLabelStyle, нажатие — переход), справа —
 * погода, время и батарея. Середина пустая: там у телефонов камера.
 *
 * [inset] — сколько сверху занимает вырез/камера (контент бара — ниже, подложка — от края).
 */
class BarView(context: Context, private val preview: BarSkin.Skin? = null, private val onSpace: (Int) -> Unit) : View(context) {
    private var skin: BarSkin.Skin = preview ?: BarSkin.current(context)
    private val look get() = Look.now
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    var inset = 0
        set(value) {
            field = value
            requestLayout()
        }

    var spaces = 1
        set(value) {
            field = value
            invalidate()
        }
    var current = 0
        set(value) {
            field = value
            invalidate()
        }
    private var clock = ""
    private var battery = ""
    private var charging = false
    private var weather = ""

    /** Где подписи столов: для нажатий. */
    private val spaceHits = ArrayList<RectF>()

    init {
        isClickable = preview == null
        contentDescription = "bar"
        if (preview != null) {
            spaces = 3
            current = 0
            clock = "12:45"
            battery = "87%"
            weather = "4°"
        }
    }

    fun reload() {
        skin = BarSkin.current(context)
        requestLayout()
        invalidate()
    }

    /** Высота самой полосы бара (без [top]), в px: height скина (px десктопа) ≈ dp. */
    val band: Int get() {
        val h = skin.num("height")
        return context.dp((if (h > 0) h * 0.85 else 32.0).toFloat().coerceIn(26f, 46f)).toInt()
    }

    private val gap: Float get() = when (skin.shape) {
        "floating", "pill", "islands", "notch" -> context.dp(maxOf(4.0, skin.num("gap")).toFloat().coerceAtMost(10f))
        else -> 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), inset + band + gap.toInt() + edge)

    /** Запас снизу под контур и жёсткую тень, чтобы их не срезало. */
    private val edge: Int get() = (maxOf(skin.num("borderWidth"), skin.num("hardShadow")) * context.resources.displayMetrics.density).toInt() + 1

    fun tick() {
        val is24 = (look.values["clock24"] as? Boolean) ?: android.text.format.DateFormat.is24HourFormat(context)
        clock = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.ROOT).format(Date())
        invalidate()
    }

    fun setBattery(level: Int, charging: Boolean) {
        battery = "$level%"
        this.charging = charging
        invalidate()
    }

    fun setWeather(now: Weather.Now?) {
        val sp = Look.prefs(context)
        weather = if (now == null) "" else Weather.format(now, sp.getString(Weather.UNITS, "auto") ?: "auto")
        invalidate()
    }

    private fun role(name: String, fallback: Int): Int = when (name) {
        "", "bar" -> fallback
        else -> look.roleColor(name) ?: fallback
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val p = look.palette
        val d = context.resources.displayMetrics.density
        val bg0 = role(skin.str("background"), p.bg)
        val alpha = skin.num("backgroundAlpha").let { if (it < 0) 1.0 else it }.toFloat()
        val transparent = skin.bool("transparent")
        val bg = withAlpha(bg0, if (transparent) 0f else alpha)
        val border = withAlpha(role(skin.str("border"), p.signal), skin.num("borderAlpha").toFloat().coerceIn(0f, 1f))
        val borderW = (skin.num("borderWidth") * d * 0.9).toFloat()
        val ink = role(skin.str("text"), p.fg)
        val active = role(skin.str("active"), p.signal).let { if (skin.str("active") == "bar") p.signal else it }
        val radius = Nihil.corner((skin.num("radius") * d * 0.9).toFloat())
        val g = gap
        val bandTop = inset.toFloat() + if (skin.shape == "floating" || skin.shape == "pill" || skin.shape == "islands") g else 0f
        val bandH = band.toFloat()

        // Модули: слева столы, справа погода · время · батарея.
        val fonts = Nihil.fonts(context)
        text.typeface = if (skin.str("font").contains("Dot", true)) fonts.dot else fonts.mono
        text.textSize = d * 13f
        val pad = d * 14f
        val cy = bandTop + bandH / 2 + text.textSize * 0.35f
        val right = listOf(weather, clock, battery + if (charging) " ↯" else "").filter { it.isNotEmpty() }.joinToString("   ")
        val rightW = text.measureText(right)
        var labelStyle = Look.prefs(context).getString(BarSkin.LABELS, null)?.takeIf { it != "skin" } ?: skin.str("wsLabelStyle")
        // Много столов — подписи не влезают до часов: тогда точками (как leds).
        if (labelStyle != "none" && labelStyle != "leds" && spacesWidth(labelStyle, d) > w - rightW - pad * 4) labelStyle = "leds"
        val leftW = spacesWidth(labelStyle, d)

        // Подложка.
        val shape = skin.shape
        val fraction = skin.num("width").toFloat().coerceIn(0.3f, 1f)
        paint.style = Paint.Style.FILL
        paint.maskFilter = null
        fun plate(r: RectF, rr: Float, roundTop: Boolean = true) {
            path.reset()
            val radii = if (roundTop) floatArrayOf(rr, rr, rr, rr, rr, rr, rr, rr) else floatArrayOf(0f, 0f, 0f, 0f, rr, rr, rr, rr)
            path.addRoundRect(r, radii, Path.Direction.CW)
            // Жёсткая тень (брутализм) и свечение (неон) — под подложкой.
            val hard = skin.num("hardShadow").toFloat() * d * 0.8f
            if (hard > 0) {
                paint.color = border
                canvas.save()
                canvas.translate(hard, hard)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            if (skin.bool("glow") && borderW > 0) {
                paint.color = withAlpha(border, 0.55f)
                paint.maskFilter = BlurMaskFilter(d * 8, BlurMaskFilter.Blur.OUTER)
                canvas.drawPath(path, paint)
                paint.maskFilter = null
            } else if (skin.bool("shadow")) {
                paint.color = withAlpha(0xFF000000.toInt(), 0.35f)
                paint.maskFilter = BlurMaskFilter(d * 6, BlurMaskFilter.Blur.OUTER)
                canvas.drawPath(path, paint)
                paint.maskFilter = null
            }
            paint.color = bg
            canvas.drawPath(path, paint)
            if (borderW > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = borderW
                paint.color = border
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            if (skin.bool("bevel")) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d * 1.5f
                paint.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f)
                canvas.drawLine(r.left, r.top + d, r.right, r.top + d, paint)
                paint.color = withAlpha(0xFF000000.toInt(), 0.45f)
                canvas.drawLine(r.left, r.bottom - d, r.right, r.bottom - d, paint)
                paint.style = Paint.Style.FILL
            }
        }
        when (shape) {
            "floating" -> {
                rect.set(g, bandTop, w - g, bandTop + bandH)
                plate(rect, radius)
            }
            "pill" -> {
                val pw = maxOf(w * fraction, leftW + rightW + pad * 4 + d * 80)
                rect.set((w - pw) / 2, bandTop, (w + pw) / 2, bandTop + bandH)
                plate(rect, bandH / 2)
            }
            "islands" -> {
                val ig = skin.num("islandGap").toFloat() * d
                rect.set(g, bandTop, g + leftW + pad * 2 + ig, bandTop + bandH)
                plate(rect, minOf(bandH / 2, radius.coerceAtLeast(d * 10)))
                rect.set(w - g - rightW - pad * 2 - ig, bandTop, w - g, bandTop + bandH)
                plate(rect, minOf(bandH / 2, radius.coerceAtLeast(d * 10)))
            }
            "notch" -> {
                val nw = maxOf(w * fraction.coerceAtMost(0.96f), leftW + rightW + pad * 4 + d * 90)
                rect.set((w - nw) / 2, 0f, (w + nw) / 2, inset + bandH)
                plate(rect, maxOf(radius, d * 14), roundTop = false)
            }
            "underline" -> {
                paint.color = border
                canvas.drawRect(0f, inset + bandH - maxOf(borderW, d * 1.5f), w, inset + bandH, paint)
            }
            "hug" -> {
                rect.set(0f, 0f, w, inset + bandH)
                plate(rect, maxOf(radius, d * 18), roundTop = false)
            }
            else -> {
                // full и frame: полоса от края экрана, frame продолжает рамку карточки.
                rect.set(0f, 0f, w, inset + bandH)
                plate(rect, 0f)
            }
        }

        // Столы.
        var x = pad + if (shape == "floating" || shape == "islands") g else 0f
        if (shape == "pill" || shape == "notch") x = maxOf(x, rect.left + pad)
        spaceHits.clear()
        val moduleStyle = skin.str("moduleStyle")
        val moduleBg = withAlpha(role(skin.str("moduleBackground"), p.fg), skin.num("moduleAlpha").toFloat())
        if (labelStyle != "none") {
            for (i in 0 until spaces) {
                val on = i == current
                val cell: Float
                if (labelStyle == "leds") {
                    cell = d * 14
                    paint.color = if (on) active else withAlpha(ink, 0.25f)
                    canvas.drawCircle(x + cell / 2, bandTop + bandH / 2, d * (if (on) 3.6f else 2.6f), paint)
                } else {
                    val label = BarSkin.label(labelStyle, i, on)
                    cell = maxOf(text.measureText(label) + d * 10, d * 20)
                    if (on && moduleStyle == "pills") {
                        paint.color = moduleBg
                        rect.set(x, bandTop + d * 5, x + cell, bandTop + bandH - d * 5)
                        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
                    }
                    text.color = if (on) active else withAlpha(ink, 0.55f)
                    text.typeface = if (on) Typeface.create(text.typeface, Typeface.BOLD) else text.typeface
                    canvas.drawText(label, x + (cell - text.measureText(label)) / 2, cy, text)
                    text.typeface = if (skin.str("font").contains("Dot", true)) fonts.dot else fonts.mono
                }
                spaceHits += RectF(x, bandTop, x + cell, bandTop + bandH)
                x += cell
            }
        }
        // Справа.
        text.color = ink
        var rx = w - pad - rightW - if (shape == "floating" || shape == "islands") g else 0f
        if (shape == "pill" || shape == "notch") rx = minOf(rx, rect.right - pad - rightW)
        if (moduleStyle == "pills" && right.isNotEmpty()) {
            paint.color = moduleBg
            rect.set(rx - d * 8, bandTop + d * 5, rx + rightW + d * 8, bandTop + bandH - d * 5)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        }
        canvas.drawText(right, rx, cy, text)
    }

    private fun spacesWidth(style: String, d: Float): Float {
        if (style == "none") return 0f
        if (style == "leds") return spaces * d * 14
        return (0 until spaces).sumOf { i -> maxOf(text.measureText(BarSkin.label(style, i, i == current)) + d * 10, d * 20).toDouble() }.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (preview != null) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            spaceHits.forEachIndexed { i, r ->
                if (event.x >= r.left - 8 && event.x <= r.right + 8) {
                    onSpace(i)
                    return true
                }
            }
        }
        return event.actionMasked == MotionEvent.ACTION_DOWN && spaceHits.any { event.x >= it.left - 8 && event.x <= it.right + 8 }
    }
}
