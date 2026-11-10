package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.lp
import io.github.kamradsmeshnyavy.launcher.ui.rounded
import kotlin.math.min

/**
 * Точечный рисунок DOT-MATRIX для виджетов: цифры и знаки 5 × 7 светодиодами
 * (горящие — цвет текста, незажжённые — едва видны), как часы Nothing и бар DOT-MATRIX.
 */
object Dots {
    const val ROWS = 7

    private val FACE: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to arrayOf(".", "#", "#", ".", "#", "#", "."),
        '.' to arrayOf(".", ".", ".", ".", ".", ".", "#"),
        '%' to arrayOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '-' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '−' to arrayOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        '°' to arrayOf(".##.", "#..#", ".##.", "....", "....", "....", "...."),
        '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        ' ' to arrayOf("..", "..", "..", "..", "..", "..", ".."),
    )

    fun columns(text: String): Int = text.sumOf { (FACE[it]?.get(0)?.length ?: 5) + 1 } - 1

    /**
     * [text] точками: левый верх в ([x], [y]), шаг [pitch]. Незажжённые точки — только в
     * клетках букв (как у светодиодной матрицы), если [unlit] ≠ 0.
     */
    fun draw(canvas: Canvas, text: String, x: Float, y: Float, pitch: Float, lit: Int, unlit: Int, paint: Paint) {
        var cx = x
        for (ch in text) {
            val glyph = FACE[ch] ?: FACE[' ']!!
            val w = glyph[0].length
            for (row in 0 until ROWS) for (col in 0 until w) {
                val on = glyph[row][col] == '#'
                if (!on && unlit == 0) continue
                paint.color = if (on) lit else unlit
                canvas.drawCircle(cx + (col + 0.5f) * pitch, y + (row + 0.5f) * pitch, pitch * (if (on) 0.4f else 0.16f), paint)
            }
            cx += (w + 1) * pitch
        }
    }

    /** Самый крупный шаг, при котором [text] влезает в [w] × [h]. */
    fun fit(text: String, w: Float, h: Float): Float = min(w / columns(text).coerceAtLeast(1), h / ROWS)

    /** LED-шкала: [count] точек, горят [lit] из них. */
    fun bar(canvas: Canvas, x: Float, y: Float, w: Float, count: Int, value: Float, on: Int, off: Int, paint: Paint) {
        val step = w / count
        val n = (value.coerceIn(0f, 1f) * count + 0.5f).toInt()
        for (i in 0 until count) {
            paint.color = if (i < n) on else off
            canvas.drawCircle(x + (i + 0.5f) * step, y, step * 0.32f, paint)
        }
    }
}

/**
 * Виджет в карточке стиля: фон, контур и скругления — как у остального интерфейса.
 * [dots] — рисовать голосом DOT-MATRIX (опция виджета voice: auto/dots/style; auto — по
 * widgetStyle Customizer'а и стилю). Содержимое — [CardCanvas] или свои View в [body].
 */
abstract class CardWidget(context: Context, item: Desk.Item, host: DeskHost, card: Boolean = true) : DeskWidget(context, item, host) {
    protected val dots: Boolean = when (item.options.optString("voice", "auto")) {
        "dots" -> true
        "style" -> false
        else -> when (Look.now.str("widgetStyle", "style")) {
            "dotmatrix" -> true
            "default" -> false
            else -> Look.now.style?.id == "dotmatrix" || Look.now.fontProfile != "stock"
        }
    }
    protected val body = FrameLayout(context)
    protected val fonts = Nihil.fonts(context)

    init {
        if (card) {
            body.background = rounded(Nihil.VOID, context.dp(22f), context.dp(1), Nihil.LINE)
            body.clipToOutline = true
        }
        val pad = context.dp(14)
        body.setPadding(pad, pad, pad, pad)
        addView(body, lp(MATCH, if (card) MATCH else WRAP, Gravity.TOP))
        body.isLongClickable = true
        body.setOnLongClickListener { performLongClick() }
    }

    /** Нажатие на карточку. */
    protected fun onTap(action: () -> Unit) {
        body.setOnClickListener { runCatching(action) }
        body.background = body.background?.let { io.github.kamradsmeshnyavy.launcher.ui.ripple(context.dp(22f), it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // В стопке у карточек своя высота: по ширине (min — [stackHeight]).
        if (!fixedHeight && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(stackHeight(w), MeasureSpec.EXACTLY))
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Высота в стопке при ширине [w]: пропорция клеток виджета. */
    protected open fun stackHeight(w: Int): Int = (w.toFloat() / item.w * item.h * 1.0f).toInt().coerceIn(context.dp(72), context.dp(420))

    companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}

/** Холст внутри карточки: рисует [draw] по размеру. */
class CardCanvas(context: Context, private val paint: (Canvas, Int, Int) -> Unit) : View(context) {
    override fun onDraw(canvas: Canvas) = paint(canvas, width, height)
}
