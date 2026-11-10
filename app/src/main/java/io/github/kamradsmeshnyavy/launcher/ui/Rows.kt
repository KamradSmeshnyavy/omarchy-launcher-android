package io.github.kamradsmeshnyavy.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Строительные блоки страниц настроек (как Preferences десктопа и карточки Customizer):
 * заголовок раздела, строка «название слева, контрол справа», слайдер, образцы цвета.
 */

/** Заголовок раздела: имя и зачем он. */
fun Context.heading(title: String, sub: String?): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val fonts = Nihil.fonts(context)
    addView(text(title, 22f, Nihil.INK, fonts.sans), llp(MATCH, WRAP).apply { topMargin = dp(36) })
    if (!sub.isNullOrEmpty()) {
        addView(text(sub, 13f, Nihil.DIM, fonts.sans).apply { setLineSpacing(dp(2f), 1f) }, llp(MATCH, WRAP).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })
    }
}

fun Context.body(value: String): TextView = text(value, 12f, Nihil.MUTED, Nihil.fonts(this).sans).apply {
    setLineSpacing(dp(3f), 1f)
}

fun Context.chevron(): View = android.widget.ImageView(this).apply {
    setImageDrawable(LineIcon(LineIcon.Shape.CHEVRON, Nihil.DIM))
    layoutParams = llp(dp(20), dp(20))
}

/**
 * Строка настройки: название (и пояснение) слева, [trailing] справа, линия под ней;
 * вся строка — цель нажатия. [marked] — точка сигнала у названия: значение своё, не стиля.
 */
fun Context.settingRow(
    title: String,
    sub: CharSequence?,
    trailing: View?,
    rule: Boolean = true,
    marked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)?,
): LinearLayout {
    val fonts = Nihil.fonts(this)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val name = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 15f, Nihil.INK, fonts.sans).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, llp(0, WRAP, 1f).apply { width = WRAP; weight = 0f })
            if (marked) {
                addView(View(context).apply { background = rounded(Nihil.SIGNAL, dp(3f)) }, llp(dp(6), dp(6)).apply { marginStart = dp(8) })
            }
        }
        addView(name)
        if (!sub.isNullOrEmpty()) addView(body(sub.toString()), llp(MATCH, WRAP).apply { topMargin = dp(5) })
    }
    val line = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(14), 0, dp(14))
        addView(texts, llp(0, WRAP, 1f))
        if (trailing != null) {
            val params = trailing.layoutParams as? LinearLayout.LayoutParams ?: llp(WRAP, WRAP)
            params.marginStart = dp(16)
            addView(trailing, params)
        }
        if (onClick != null || onLongClick != null) {
            background = ripple(dp(10f))
            setOnClickListener { onClick?.invoke() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
    }
    if (!rule) return line
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(line, llp(MATCH, WRAP))
        addView(rule(), llp(MATCH, dp(1)))
    }
}

/**
 * Слайдер DOT-MATRIX: ряд светодиодов, горящих до значения, и само значение моноширинным.
 * Шаг и границы — из схемы Customizer (min/max/step).
 */
class LedSlider(
    context: Context,
    private val min: Double,
    private val max: Double,
    private val step: Double,
    value: Double,
    private val onChange: (Double) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Nihil.fonts(context).mono
        textSize = context.dp(12f)
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()
    private val leds = 24
    var value = snap(value)
        private set

    init {
        isClickable = true
        isFocusable = true
        contentDescription = format(this.value)
    }

    private fun snap(v: Double): Double {
        val s = if (step > 0) ((v - min) / step).roundToInt() * step + min else v
        return s.coerceIn(min, max)
    }

    private fun format(v: Double): String =
        if (step >= 1 && v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.', ',')

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(40))

    private val trackEnd: Float get() = width - context.dp(56f)

    override fun onDraw(canvas: Canvas) {
        val t = if (max > min) ((value - min) / (max - min)).toFloat() else 0f
        val lit = (t * leds).roundToInt()
        val gap = context.dp(3f)
        val cell = (trackEnd - gap * (leds - 1)) / leds
        val h = context.dp(10f)
        val top = (height - h) / 2f
        for (i in 0 until leds) {
            val x = i * (cell + gap)
            rect.set(x, top, x + cell, top + h)
            paint.color = if (i < lit) Nihil.INK else Nihil.OFF
            val r = Nihil.corner(context.dp(2f))
            canvas.drawRoundRect(rect, r, r, paint)
        }
        label.color = Nihil.DIM
        canvas.drawText(format(value), width.toFloat(), height / 2f + label.textSize / 3, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val t = (event.x / trackEnd).coerceIn(0f, 1f)
                val next = snap(min + (max - min) * t)
                if (next != value) {
                    value = next
                    contentDescription = format(next)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                onChange(value)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> onChange(value)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Образец цвета: кружок с кромкой; выбранный — в кольце цвета текста. */
class Swatch(context: Context, private val color: Int, chosen: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var chosen = chosen
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(34), context.dp(34))

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        if (chosen) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2f)
            paint.color = Nihil.INK
            canvas.drawCircle(c, c, c - context.dp(1f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(c, c, c - context.dp(6f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        paint.color = Nihil.LINE
        canvas.drawCircle(c, c, c - context.dp(6f), paint)
    }
}
