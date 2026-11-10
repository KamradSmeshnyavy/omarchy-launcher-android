package io.github.kamradsmeshnyavy.launcher.ui.desk

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import io.github.kamradsmeshnyavy.launcher.core.Desk
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.IconStyle
import io.github.kamradsmeshnyavy.launcher.core.Weather

/** То, что столу нужно от домашнего экрана. */
interface DeskHost {
    fun launch(entry: Entry, from: View)
    fun menu(entry: Entry, from: View)
    fun openShade()
    fun orderFavorites(keys: List<String>)
    fun closeMenu()
    fun openTasks()
    /** Долгое нажатие на элемент стола: меню правки (размер, порядок, настройки, удалить). */
    fun editItem(item: Desk.Item, from: View)
    /** Разрешение для виджета: спросить и, если дали, [then]. */
    fun ask(permission: String, then: () -> Unit)
    /** Выбрать фото (системный выбор с сохранением доступа). */
    fun pickImages(then: (List<android.net.Uri>) -> Unit)
    /** Настройки этого виджета (Customizer → страница виджета). */
    fun editOptions(item: Desk.Item)

    /** Поиск лаунчера (ящик с клавиатурой) — виджет «Поиск». */
    fun openSearch() {}
}

/**
 * Элемент рабочего стола. Домашний экран раздаёт всем одни и те же события (время,
 * батарея, будильник, непрочитанное, погода, избранное); каждый берёт нужное.
 * В сетке получает точный размер клеток ([fixedHeight] = true), в стопке — свою высоту.
 */
abstract class DeskWidget(context: Context, val item: Desk.Item, protected val host: DeskHost) : FrameLayout(context) {
    var fixedHeight = false
        set(value) {
            field = value
            onFixed(value)
        }

    /** Сетка (true): карточка заполняет клетки целиком; стопка — своя высота. */
    protected open fun onFixed(fixed: Boolean) {}

    /** Карточку [card] — во всю высоту клеток в сетке, иначе по содержимому с [gravity]. */
    protected fun fill(card: View, fixed: Boolean, gravity: Int = android.view.Gravity.TOP) {
        card.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, if (fixed) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, gravity)
    }

    init {
        isLongClickable = true
        setOnLongClickListener {
            host.editItem(item, this)
            true
        }
    }

    open fun onClock(animate: Boolean) {}
    open fun onBattery(level: Int, charging: Boolean) {}
    open fun onAlarm(text: String?) {}
    open fun onUnread(packages: List<String>, style: IconStyle) {}
    open fun onWeather(now: Weather.Now?) {}
    open fun onFavorites(entries: List<Entry>, icons: Boolean, style: IconStyle) {}
    /** Вернулись на домашний экран: обновить то, что могло устареть. */
    open fun refresh() {}

    /** Вертикальный жест из (rawX, rawY) прокручивает этот виджет, а не открывает ящик. */
    open fun scrollableAt(rawX: Float, rawY: Float, dy: Float): Boolean = false

    protected fun inside(view: View, rawX: Float, rawY: Float): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return rawX >= at[0] && rawX < at[0] + view.width && rawY >= at[1] && rawY < at[1] + view.height
    }
}
