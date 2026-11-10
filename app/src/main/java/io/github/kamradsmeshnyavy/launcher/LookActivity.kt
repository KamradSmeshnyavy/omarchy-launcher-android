package io.github.kamradsmeshnyavy.launcher

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowInsetsController
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.ui.Nihil

/** Считает Look до первой активити, чтобы ни один экран не нарисовался монохромом апстрима. */
class LauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Look.current(this)
        io.github.kamradsmeshnyavy.launcher.core.PcLook.schedule(this)
    }
}

/**
 * Активити, которая следует теме и стилю: красит окно и системные панели под палитру
 * и пересоздаётся, если Look поменялся, пока она была в фоне (смена темы в настройках,
 * снимок с ПК, светлая/тёмная тема системы).
 */
abstract class LookActivity : Activity() {
    private var seen = -1

    /** false у прозрачных окон (домашний экран показывает обои, share-окна — то, что под ними). */
    protected open val paintsWindow: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Look.current(this)
        seen = Look.version
        super.onCreate(savedInstanceState)
        if (paintsWindow) window.setBackgroundDrawable(ColorDrawable(Nihil.VOID))
        window.decorView.post { lightBars() }
    }

    private val external: () -> Unit = { restart() }

    override fun onResume() {
        super.onResume()
        Look.current(this)
        if (seen != Look.version) restart()
        Look.listen(external)
    }

    override fun onPause() {
        Look.unlisten(external)
        super.onPause()
    }

    /** Значки статус-бара и жестов тёмные на светлой теме. */
    protected fun lightBars() {
        val light = !Nihil.DARK
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    }

    /** Перерисовать себя сейчас, после правки Look прямо на этом экране. */
    fun applyLook() {
        Look.invalidate(this)
        seen = Look.version
        restart()
    }

    /**
     * Экран заново под новый Look. Не recreate(): после него окно не раздаёт системные
     * отступы, и у рамки домашнего экрана и страниц настроек пропадают поля.
     */
    protected fun restart() {
        val again = intent
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        startActivity(again)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
