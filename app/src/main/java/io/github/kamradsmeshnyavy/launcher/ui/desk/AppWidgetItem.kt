package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.util.SizeF
import android.view.View
import io.github.kamradsmeshnyavy.launcher.core.AppWidgets
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.WidgetTint
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.lp
import io.github.kamradsmeshnyavy.launcher.ui.rounded

/**
 * Виджет приложения на столе. Всегда — в карточке стиля (опция card) и с цветами
 * Material You из темы; опция tint: none — как нарисовало приложение, theme — двуцвет
 * фон↔текст темы, mono — монохром DOT-MATRIX (чёрное, белое).
 */
class AppWidgetItem(context: Context, item: Desk.Item, host: DeskHost) : DeskWidget(context, item, host) {
    private val view: AppWidgetHostView?

    init {
        val info = AppWidgets.info(context, item.appWidgetId)
        view = info?.let { runCatching { AppWidgets.host(context).createView(context, item.appWidgetId, it) }.getOrNull() }
        val card = item.options.optBoolean("card", true)
        if (card) {
            background = rounded(Nihil.VOID, context.dp(22f), context.dp(1), Nihil.LINE)
            clipToOutline = true
            val pad = context.dp(6)
            setPadding(pad, pad, pad, pad)
        }
        view?.let { v ->
            v.setColorResources(AppWidgets.colorResources(Look.now.palette))
            tint(v)
            addView(v, lp(MATCH, MATCH))
        }
    }

    private fun tint(v: View) {
        val p = Look.now.palette
        val matrix = when (item.options.optString("tint", "none")) {
            "theme" -> WidgetTint.duotone(if (p.dark) p.bg else p.fg, if (p.dark) p.fg else p.bg)
            "mono" -> WidgetTint.duotone(0xFF000000.toInt(), 0xFFF2F2F2.toInt())
            else -> null
        }
        if (matrix == null) {
            v.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val v = view ?: return
        val d = resources.displayMetrics.density
        val size = SizeF((w - paddingLeft - paddingRight) / d, (h - paddingTop - paddingBottom) / d)
        runCatching { v.updateAppWidgetSize(Bundle(), listOf(size)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // В стопке: высота по пропорции клеток (виджеты приложений сами её не знают).
        if (!fixedHeight && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w.toFloat() / item.w * item.h * 1.15f).toInt().coerceIn(context.dp(80), context.dp(480))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val MATCH = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
    }
}
