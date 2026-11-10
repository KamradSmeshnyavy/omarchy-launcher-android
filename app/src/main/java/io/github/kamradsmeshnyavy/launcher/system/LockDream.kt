package io.github.kamradsmeshnyavy.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.service.dreams.DreamService
import android.view.View
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.LockArt
import io.github.kamradsmeshnyavy.launcher.core.Weather

/**
 * Заставка Android (Настройки → Экран → Заставка) в дизайне экрана блокировки Omarchy:
 * часы, дата, батарея, погода и то, что играет. Раз в полминуты перерисовывается и чуть
 * сдвигается — против выгорания экрана.
 */
class LockDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        Look.current(this)
        setContentView(Scene(this))
    }

    private class Scene(context: Context) : View(context) {
        private var base: Bitmap? = null
        private var tick = 0
        private val next = object : Runnable {
            override fun run() {
                tick++
                invalidate()
                postDelayed(this, 30_000)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(next)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(next)
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            base = LockArt.base(context, w, h)
        }

        private fun live(): LockArt.Live {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
            val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
            val media = runCatching {
                context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(ComponentName(context, GlyphListener::class.java))
                    .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            }.getOrNull().orEmpty()
            val weather = Weather.cached(context)?.let { Weather.format(it, Look.prefs(context).getString(Weather.UNITS, "auto") ?: "auto") }.orEmpty()
            return LockArt.Live(level, charging, media, weather)
        }

        override fun onDraw(canvas: Canvas) {
            // Сдвиг по кругу из 4 положений: −1…1 доли нескольких dp.
            val shift = listOf(0f, 0.6f, 0f, -0.6f)[tick % 4]
            LockArt.draw(canvas, context, LockArt.current(context), width, height, base, live(), shift)
        }
    }
}
