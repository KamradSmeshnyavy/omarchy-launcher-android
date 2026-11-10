package io.github.kamradsmeshnyavy.launcher.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import kotlin.math.roundToInt

/** Android side of [DotMatrix]: decode a photo to the screen and draw the LEDs. */
object DotRender {

    /** The photo at [uri], scaled and centre-cropped to exactly [width] x [height]. */
    fun decodeCover(resolver: ContentResolver, uri: Uri, width: Int, height: Int): Bitmap =
        decodeCover(ImageDecoder.createSource(resolver, uri), width, height)

    /** The same, from any source (the launcher's own wallpaper, in its assets). */
    fun decodeCover(source: ImageDecoder.Source, width: Int, height: Int): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (scale, _, _) = DotMatrix.cover(info.size.width, info.size.height, width, height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        val (scale, dx, dy) = DotMatrix.cover(decoded.width, decoded.height, width, height)
        val w = (decoded.width * scale).roundToInt().coerceAtLeast(width)
        val h = (decoded.height * scale).roundToInt().coerceAtLeast(height)
        val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
        return Bitmap.createBitmap(scaled, dx, dy, width, height)
    }

    fun grid(photo: Bitmap, pitch: Int, gamma: Float = 1.25f, offLevel: Float = 0.07f): DotMatrix.Grid {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        return DotMatrix.grid(pixels, photo.width, photo.height, DotMatrix.Params(pitch, gamma = gamma, offLevel = offLevel))
    }

    /**
     * Светодиоды на [background]; горящий на полную — цвета [tint], как dotify.py Customizer'а
     * (маска яркости между фоном и тинтом). По умолчанию — белое на чёрном, как в NIHIL.
     */
    fun render(grid: DotMatrix.Grid, width: Int, height: Int, tint: Int = Color.WHITE, background: Int = Color.BLACK): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background or 0xFF000000.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val offColor = Look.mix(background, tint, grid.offGrey / 255f)
        val offRadius = grid.offRadius
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val (x, y) = grid.center(col, row)
                if (grid.offGrey > 0) {
                    paint.color = offColor
                    canvas.drawCircle(x, y, offRadius, paint)
                }
                val led = grid.lit(col, row) ?: continue
                paint.color = Look.mix(background, tint, led.grey / 255f)
                canvas.drawCircle(led.x, led.y, led.radius, paint)
            }
        }
        return bitmap
    }

    /** Recolor целого кадра в палитру темы. */
    fun recolor(photo: Bitmap, palette: IntArray): Bitmap {
        val pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val out = Recolor.recolor(pixels, palette)
        return Bitmap.createBitmap(out, photo.width, photo.height, Bitmap.Config.ARGB_8888)
    }
}
