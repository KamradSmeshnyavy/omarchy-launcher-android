package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.withAlpha
import kotlin.math.roundToInt

/**
 * Один рабочий стол. [mode] — раскладка из Customizer (deskLayout):
 *   grid  — сетка [Desk.Grid.COLUMNS] колонок, клетка почти квадратная; элементы ровно по клеткам;
 *   stack — карточки сверху вниз (широкие по одной, узкие по две), высота — своя;
 *           «избранное», если оно последнее, прижато к низу, как на домашнем экране NIHIL.
 * Больше, чем помещается, — прокрутка (с ящиком делится через [scrollableAt]).
 */
class DeskPage(context: Context, val space: Desk.Space, val mode: String) : ScrollView(context) {
    val board = Board(context)

    init {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun add(widget: DeskWidget) {
        widget.fixedHeight = mode == "grid"
        board.addView(widget)
    }

    val widgets: List<DeskWidget> get() = List(board.childCount) { board.getChildAt(it) as DeskWidget }

    /** Сетка: сколько строк влезло на экран (после измерения). */
    var onRows: ((Int) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Высота окна — доске: в сетке строки делят её ровно, без прокрутки.
        board.viewport = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun canScrollVertically(direction: Int): Boolean = mode != "grid" && super.canScrollVertically(direction)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = moving != null || (mode != "grid" && super.onInterceptTouchEvent(ev))

    fun scrollableAt(rawX: Float, rawY: Float, dy: Float): Boolean {
        widgets.forEach { if (it.scrollableAt(rawX, rawY, dy)) return true }
        val at = IntArray(2)
        getLocationOnScreen(at)
        val inside = rawX >= at[0] && rawX < at[0] + width && rawY >= at[1] && rawY < at[1] + height
        return inside && canScrollVertically(if (dy < 0) 1 else -1)
    }

    // --- Перетаскивание в сетке ---------------------------------------------------------------

    /** Элемент, который сейчас переносят по сетке (после «Переместить» в его меню). */
    var moving: Desk.Item? = null
        set(value) {
            field = value
            board.invalidate()
        }

    /** Куда встанет [moving], и что делать по отпусканию: (x, y) клетки. */
    var onDrop: ((Int, Int) -> Unit)? = null

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val item = moving ?: return mode != "grid" && super.onTouchEvent(ev)
        val (cx, cy) = board.cellAt(ev.x - board.left + scrollX, ev.y - board.top + scrollY, item)
        board.target = cx to cy
        board.invalidate()
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            onDrop?.invoke(cx, cy)
            board.target = null
        }
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL) board.target = null
        return true
    }

    inner class Board(context: Context) : ViewGroup(context) {
        private val gap = context.dp(10)
        var target: Pair<Int, Int>? = null
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            setWillNotDraw(false)
            clipChildren = false
        }

        /** Высота окна стола (от DeskPage): в сетке строки заполняют её. */
        var viewport = 0
        val cell: Int get() = ((measuredWidth - paddingLeft - paddingRight) / Desk.Grid.COLUMNS).coerceAtLeast(1)
        var cellH = 1
            private set
        var rows = 0
            private set

        private fun item(child: View): Desk.Item = (child as DeskWidget).item

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val minH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(heightMeasureSpec)
            val innerW = w - paddingLeft - paddingRight
            var content = 0
            if (mode == "grid") {
                val c = innerW / Desk.Grid.COLUMNS
                // Строк — сколько влезает клеток с пропорцией CELL_RATIO; остаток делится между ними.
                val innerH = viewport - paddingTop - paddingBottom
                val ideal = (c * CELL_RATIO).coerceAtLeast(1f)
                val fit = if (innerH > 0) (innerH / ideal).toInt().coerceAtLeast(MIN_ROWS) else 0
                val ch = if (fit > 0) innerH / fit else ideal.roundToInt()
                cellH = ch.coerceAtLeast(1)
                if (fit > 0 && fit != rows) {
                    rows = fit
                    post { onRows?.invoke(fit) }
                }
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    val it = item(child)
                    child.measure(exact(it.w * c), exact(it.h * ch))
                    content = maxOf(content, (it.y + it.h) * ch)
                }
            } else {
                rows().forEach { row ->
                    var rowH = 0
                    val each = if (row.size == 1 && item(row[0]).wide) innerW else (innerW - gap) / 2
                    row.forEach { child ->
                        child.measure(exact(each), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                        rowH = maxOf(rowH, child.measuredHeight)
                    }
                    content += rowH + gap
                }
                content = (content - gap).coerceAtLeast(0)
            }
            setMeasuredDimension(w, maxOf(minH, content + paddingTop + paddingBottom))
        }

        private fun rows(): List<List<View>> {
            val byItem = (0 until childCount).associateBy({ item(getChildAt(it)) }, { getChildAt(it) })
            return Desk.stackRows(byItem.keys.toList()).map { r -> r.mapNotNull { byItem[it] } }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            if (mode == "grid") {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    val it = item(child)
                    val x = paddingLeft + it.x * cell
                    val y = paddingTop + it.y * cellH
                    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                }
                return
            }
            val rows = rows()
            var y = paddingTop
            val total = rows.sumOf { row -> row.maxOf { it.measuredHeight } + gap } - gap
            val free = (b - t - paddingTop - paddingBottom) - total
            rows.forEachIndexed { index, row ->
                // Избранное последним — к низу, у большого пальца.
                if (index == rows.size - 1 && free > 0 && row.size == 1 && item(row[0]).kind == "favourites") y += free
                var x = paddingLeft
                row.forEach { child ->
                    child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                    x += child.measuredWidth + gap
                }
                y += row.maxOf { it.measuredHeight } + gap
            }
        }

        /** Клетка под пальцем для элемента [item] (его левый верхний угол — под пальцем по центру). */
        fun cellAt(x: Float, y: Float, item: Desk.Item): Pair<Int, Int> {
            val cx = ((x - paddingLeft) / cell - item.w / 2f).roundToInt().coerceIn(0, Desk.Grid.COLUMNS - item.w)
            val cy = ((y - paddingTop) / cellH - item.h / 2f).roundToInt().coerceAtLeast(0)
            return cx to cy
        }

        override fun onDraw(canvas: Canvas) {
            val item = moving ?: return
            // Сетка клеток и место, куда встанет элемент.
            hint.style = Paint.Style.FILL
            hint.color = withAlpha(Nihil.INK, 0.18f)
            val last = if (rows > 0) rows - 1 else (height - paddingTop - paddingBottom) / cellH
            for (y in 0..last) for (x in 0 until Desk.Grid.COLUMNS) {
                canvas.drawCircle(paddingLeft + (x + 0.5f) * cell, paddingTop + (y + 0.5f) * cellH, context.dp(2f), hint)
            }
            val (tx, ty) = target ?: (item.x to item.y)
            rect.set(
                (paddingLeft + tx * cell).toFloat(), (paddingTop + ty * cellH).toFloat(),
                (paddingLeft + (tx + item.w) * cell).toFloat(), (paddingTop + (ty + item.h) * cellH).toFloat(),
            )
            hint.style = Paint.Style.STROKE
            hint.strokeWidth = context.dp(2f)
            hint.color = Nihil.SIGNAL
            val r = Nihil.corner(context.dp(16f))
            canvas.drawRoundRect(rect, r, r, hint)
        }

        private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
    }

    private companion object {
        /** Клетка чуть выше ширины, как в лаунчерах на вытянутых экранах. */
        const val CELL_RATIO = 1.15f
        const val MIN_ROWS = 4
    }
}
