package io.github.kamradsmeshnyavy.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.AlarmManager
import android.app.SearchManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.ImageDecoder
import android.service.notification.NotificationListenerService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.kamradsmeshnyavy.launcher.core.Action
import io.github.kamradsmeshnyavy.launcher.core.ActionHost
import io.github.kamradsmeshnyavy.launcher.core.Actions
import io.github.kamradsmeshnyavy.launcher.core.AppEntry
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.DotRender
import io.github.kamradsmeshnyavy.launcher.core.Apps
import io.github.kamradsmeshnyavy.launcher.core.Prefs
import io.github.kamradsmeshnyavy.launcher.core.Torch
import io.github.kamradsmeshnyavy.launcher.core.WebEntry
import io.github.kamradsmeshnyavy.launcher.core.Webs
import io.github.kamradsmeshnyavy.launcher.system.Access
import io.github.kamradsmeshnyavy.launcher.system.Glyph
import io.github.kamradsmeshnyavy.launcher.system.Glyph.sound
import io.github.kamradsmeshnyavy.launcher.system.LockService
import io.github.kamradsmeshnyavy.launcher.ui.BarView
import io.github.kamradsmeshnyavy.launcher.ui.AppMenu
import io.github.kamradsmeshnyavy.launcher.ui.DrawerView
import io.github.kamradsmeshnyavy.launcher.ui.EASE
import io.github.kamradsmeshnyavy.launcher.ui.FrameView
import io.github.kamradsmeshnyavy.launcher.ui.HomeView
import io.github.kamradsmeshnyavy.launcher.ui.LineIcon
import io.github.kamradsmeshnyavy.launcher.ui.MATCH
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.Notice
import io.github.kamradsmeshnyavy.launcher.ui.RootLayout
import io.github.kamradsmeshnyavy.launcher.ui.WebDialog
import io.github.kamradsmeshnyavy.launcher.ui.WRAP
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.lp
import java.net.URLEncoder
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * NIHIL's home screen. Three layers in one window:
 *   HomeView   clock, battery, favourites (on the wallpaper)
 *   FrameView  the black frame and the Glyph light
 *   DrawerView the drawer, which rises with the frame
 */
class LauncherActivity :
    LookActivity(),
    HomeView.Host,
    DrawerView.Host,
    RootLayout.Listener,
    ActionHost,
    Glyph.Listener {

    override val paintsWindow = false

    private lateinit var prefs: Prefs
    private lateinit var root: RootLayout
    private lateinit var home: HomeView
    private lateinit var frame: FrameView
    /** Бар в скине бара Omarchy вместо строки состояния (Customizer → Бар); null — выключен. */
    private var bar: BarView? = null
    private lateinit var drawer: DrawerView
    private lateinit var noticeView: Notice
    private lateinit var popup: AppMenu
    private var actions: List<Action> = emptyList()

    private var drawerProgress = 0f
    private var settle: ValueAnimator? = null
    private var bandTop = 0
    private var bandBottom = 0
    private var imeBottom = 0
    private var receiving = false
    private var charging: Boolean? = null
    private var resumed = false
    private var backCallback: OnBackInvokedCallback? = null

    // --- Lifecycle -------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Apps.init(this)
        Torch.init(this)

        // Edge to edge on every version (Android 15 enforces it; 12-14 need the call).
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false

        popup = AppMenu(this)
        home = HomeView(this, this)
        frame = FrameView(this)
        drawer = DrawerView(this, this)
        noticeView = Notice(this)
        if (io.github.kamradsmeshnyavy.launcher.core.BarSkin.enabled(this)) bar = BarView(this) { home.showPage(it) }
        root = RootLayout(this, this).apply {
            addView(home, lp(MATCH, MATCH))
            addView(frame, lp(MATCH, MATCH))
            addView(drawer, lp(MATCH, MATCH))
            bar?.let { addView(it, lp(MATCH, WRAP, Gravity.TOP)) }
            addView(noticeView, lp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(root)

        root.scrollableAt = { x, y, dy -> home.favoritesScrollAt(x, y, dy) }
        root.pager = home
        home.onPage = { bar?.current = it }
        home.onRebuilt = { count, current ->
            bar?.spaces = count
            bar?.current = current
        }
        bar?.let {
            it.spaces = io.github.kamradsmeshnyavy.launcher.core.Desk.state(this).spaces.size
            it.current = home.page
            hideStatusBar()
        }
        root.blankAt = { x, y -> home.blankAt(x, y) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) applyInsets(insets)
                return insets
            }
        })

        actions = Actions.all(this, this)
        Apps.listen(appsChanged)
        Webs.listen(websChanged)
        prefs.listen(prefsChanged)
        Torch.listen(torchChanged)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!)
        }

        home.setDim(prefs.dimWallpaper)
        if (intent?.action == CustomizerActivity.ACTION_PICK_APP_WIDGET) root.post { bindAppWidget(intent) }
        setProgress(0f)
        refreshApps()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A single page: keep a wallpaper that is wider than the screen centred.
        runCatching {
            WallpaperManager.getInstance(this).setWallpaperOffsets(window.decorView.windowToken, 0.5f, 0.5f)
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { io.github.kamradsmeshnyavy.launcher.core.AppWidgets.host(this).startListening() }
        register()
        home.updateClock(animate = false)
        bar?.tick()
        updateAlarm()
        Glyph.listen(this)
        // Notification access granted but its listener not bound (after an
        // update, on some phones): ask Android to bind it again.
        if (Access.hasNotificationAccess(this) && !Glyph.connected) {
            runCatching { NotificationListenerService.requestRebind(Access.glyphComponent(this)) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.rebuildIfStale()
        if (bar != null) hideStatusBar()
        updateHomeShowing()
        home.updateClock(animate = false)
        bar?.tick()
        home.refreshTodo()
        io.github.kamradsmeshnyavy.launcher.core.Weather.refresh(this) { if (!isDestroyed) { home.updateWeather(it); bar?.setWeather(it) } }
        io.github.kamradsmeshnyavy.launcher.core.PcLook.syncSoon(this)
        updateAlarm()
        Webs.fetchIcons(this, prefs.webs)
        if (!prefs.wallpaperSeeded && Access.isDefaultHome(this)) seedWallpaper()
    }

    override fun onPause() {
        resumed = false
        updateHomeShowing()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateHomeShowing()
    }

    /**
     * Whether the home card is what the light runs round now, or the screen's edge
     * (Glyph.homeShowing). Not window focus: a heads-up notification takes it on
     * some phones (One UI) just as its light should run.
     */
    private fun updateHomeShowing() {
        Glyph.homeShowing = resumed && drawerProgress == 0f
    }

    override fun onStop() {
        super.onStop()
        runCatching { io.github.kamradsmeshnyavy.launcher.core.AppWidgets.host(this).stopListening() }
        unregister()
        Glyph.unlisten(this)
        // Coming back always lands on the home screen.
        popup.dismiss()
        settle?.cancel()
        setProgress(0f)
        drawer.closed()
        hideKeyboard()
    }

    override fun onDestroy() {
        Apps.unlisten(appsChanged)
        Webs.unlisten(websChanged)
        prefs.unlisten(prefsChanged)
        Torch.unlisten(torchChanged)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    /** The home button while NIHIL is in front: close whatever is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            popup.dismiss()
            if (drawerProgress > 0f) animateDrawer(open = false)
        }
        if (intent.action == CustomizerActivity.ACTION_PICK_APP_WIDGET) bindAppWidget(intent)
    }

    // --- Виджеты приложений -------------------------------------------------------------

    private class Pending(val space: Int, val id: Int, val provider: android.content.ComponentName)

    private var pending: Pending? = null

    /** Из выбора в Customizer: выделить id, получить право привязки, настройку — и на стол. */
    private fun bindAppWidget(intent: Intent) {
        val provider = intent.getStringExtra("provider")?.let { android.content.ComponentName.unflattenFromString(it) } ?: return
        val space = intent.getIntExtra("space", home.page)
        val widgets = io.github.kamradsmeshnyavy.launcher.core.AppWidgets
        val id = widgets.host(this).allocateAppWidgetId()
        pending = Pending(space, id, provider)
        if (widgets.manager(this).bindAppWidgetIdIfAllowed(id, provider)) {
            configureAppWidget()
        } else {
            val ask = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(ask, REQUEST_BIND) }.onFailure { dropPending() }
        }
    }

    private fun configureAppWidget() {
        val p = pending ?: return
        val widgets = io.github.kamradsmeshnyavy.launcher.core.AppWidgets
        val info = widgets.info(this, p.id) ?: return dropPending()
        if (info.configure != null) {
            runCatching { widgets.host(this).startAppWidgetConfigureActivityForResult(this, p.id, 0, REQUEST_CONFIG, null) }
                .onFailure { placeAppWidget() }
        } else {
            placeAppWidget()
        }
    }

    private fun placeAppWidget() {
        val p = pending ?: return
        pending = null
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val widgets = io.github.kamradsmeshnyavy.launcher.core.AppWidgets
        val info = widgets.info(this, p.id) ?: return
        val (w, h) = widgets.cells(this, info)
        desk.add(this, p.space, io.github.kamradsmeshnyavy.launcher.core.Desk.Item(desk.newId("aw"), "appwidget", info.provider.flattenToShortString(), appWidgetId = p.id, w = w, h = h))
        home.rebuild()
        home.post { home.showPage(p.space) }
    }

    private fun dropPending() {
        pending?.let { io.github.kamradsmeshnyavy.launcher.core.AppWidgets.delete(this, it.id) }
        pending = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Android 12: no OnBackInvokedDispatcher. A home screen never finishes on back.
        back()
    }

    private fun back() {
        when {
            popup.showing -> popup.dismiss()
            drawerProgress > 0f -> animateDrawer(open = false)
        }
    }

    // --- Insets and the drawer ---------------------------------------------------

    /** Своя строка вместо системной: системная показывается временно, свайпом от края. */
    private fun hideStatusBar() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val side = dp(6)
        val left = max(bars.left, side)
        val right = max(bars.right, side)
        bandTop = max(bars.top, dp(24))
        bar?.let { b ->
            // Бар — на месте строки состояния: не ниже выреза камеры, не ниже самой строки.
            val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            b.inset = (status - b.band).coerceAtLeast(0)
            b.measure(View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bandTop = max(b.measuredHeight, dp(24))
        }
        bandBottom = max(bars.bottom, dp(10))
        imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom

        // The card follows the screen's own rounded corners, a little tighter.
        val corner = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val radius = if (corner > 0) (corner - side).toFloat().coerceIn(dp(18f), dp(34f)) else dp(26f)

        frame.setFrame(left, bandTop, right, bandBottom, radius)
        home.setCard(left, bandTop, right, bandBottom)
        val bottom = max(bandBottom, imeBottom)
        drawer.setInsets(bandTop, bottom)
        (noticeView.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = bottom + dp(28)
        noticeView.requestLayout()
        setProgress(drawerProgress)
    }

    private fun setProgress(value: Float) {
        drawerProgress = value.coerceIn(0f, 1f)
        frame.progress = drawerProgress
        drawer.translationY = frame.cardBottom - bandTop
        drawer.alpha = Nihil.reveal(drawerProgress)
        drawer.visibility = if (drawerProgress > 0f) View.VISIBLE else View.INVISIBLE
        home.alpha = 1f - (drawerProgress / 0.35f).coerceIn(0f, 1f)
        home.translationY = -drawerProgress * dp(36f)
        home.importantForAccessibility =
            if (drawerProgress > 0.5f) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        updateHomeShowing()
    }

    private fun animateDrawer(open: Boolean, velocity: Float = 0f) {
        settle?.cancel()
        val target = if (open) 1f else 0f
        val distance = abs(target - drawerProgress)
        if (distance < 0.001f) {
            setProgress(target)
            settled(open)
            return
        }
        // A fast fling finishes sooner.
        val speed = (abs(velocity) / (frame.travel * 3f)).coerceIn(0f, 1f)
        settle = ValueAnimator.ofFloat(drawerProgress, target).apply {
            duration = (Nihil.DRAWER * distance * (1f - 0.45f * speed)).toLong().coerceAtLeast(120L)
            interpolator = EASE
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (settle === animation) settle = null
                    if (!cancelled) settled(open)
                }
            })
            start()
        }
        if (!open) hideKeyboard()
    }

    private fun settled(open: Boolean) {
        io.github.kamradsmeshnyavy.launcher.core.Sounds.play(this, if (open) io.github.kamradsmeshnyavy.launcher.core.Sounds.Event.OPEN else io.github.kamradsmeshnyavy.launcher.core.Sounds.Event.CLOSE)
        if (open) {
            drawer.opened(prefs.keyboardOnOpen)
        } else {
            drawer.closed()
            hideKeyboard()
        }
    }

    // RootLayout.Listener

    override val progress: Float get() = drawerProgress

    override val travel: Float get() = frame.travel

    override fun canCloseFrom(y: Float): Boolean = drawer.canCloseFrom(y - drawer.translationY)

    override fun onGrab() {
        settle?.cancel()
        settle = null
        popup.dismiss()
        hideKeyboard()
    }

    override fun onDrag(progress: Float) = setProgress(progress)

    override fun onRelease(velocityY: Float) {
        val open = when {
            velocityY < -root.flingVelocity -> true
            velocityY > root.flingVelocity -> false
            else -> drawerProgress > 0.5f
        }
        animateDrawer(open, velocityY)
    }

    override fun onSwipeDown() {
        openShade()
    }

    override fun onDoubleTap() {
        if (prefs.doubleTapLock) lockScreen()
    }

    override fun onLongPress(x: Float, y: Float) {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        popup.show(
            anchor = root,
            title = getString(R.string.app_name),
            point = x.toInt() to y.toInt(),
            items = listOf(
                AppMenu.Item(LineIcon.Shape.PLUS, getString(R.string.desk_add_widget)) { addWidget(x, y) },
                AppMenu.Item(LineIcon.Shape.GEAR, getString(R.string.desk_edit_items)) { editItems(x, y) },
                AppMenu.Item(LineIcon.Shape.TASKS, getString(R.string.desk_new_space)) { newSpace() },
                AppMenu.Item(LineIcon.Shape.IMAGE, getString(R.string.act_wallpaper)) { openStudio() },
                AppMenu.Item(LineIcon.Shape.WEB, getString(R.string.web_add)) { addWeb() },
                AppMenu.Item(LineIcon.Shape.SETTINGS, getString(R.string.act_nihil)) { openSettings() },
            ),
        )
    }

    // --- Рабочие столы ---------------------------------------------------------------

    private fun addWidget(@Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        startActivity(CustomizerActivity.intent(this, CustomizerActivity.ADD + home.page))
    }

    // --- Для виджетов: разрешения, фото, настройки ----------------------------------------

    private val asked = HashMap<Int, () -> Unit>()
    private var askCode = 100

    override fun ask(permission: String, then: () -> Unit) {
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            then()
            return
        }
        val code = askCode++
        asked[code] = then
        requestPermissions(arrayOf(permission), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val then = asked.remove(requestCode) ?: return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) then()
    }

    private var picked: ((List<android.net.Uri>) -> Unit)? = null

    override fun pickImages(then: (List<android.net.Uri>) -> Unit) {
        picked = then
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQUEST_IMAGES) }
    }

    override fun editOptions(item: io.github.kamradsmeshnyavy.launcher.core.Desk.Item) {
        startActivity(CustomizerActivity.intent(this, CustomizerActivity.ITEM + item.id))
    }

    /** Все элементы этого стола: выбрать, какой править (так правится и избранное). */
    private fun editItems(x: Float, y: Float) {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val space = desk.state(this).spaces.getOrNull(home.page) ?: return
        val catalog = io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog
        popup.show(anchor = root, title = getString(R.string.desk_edit_items), point = x.toInt() to y.toInt(),
            items = space.items.sortedBy { it.order }.map { item ->
                val name = when (item.kind) {
                    "favourites" -> getString(R.string.w_favourites)
                    "widget" -> catalog.spec(item.type)?.title?.let { getString(it) } ?: item.type
                    "app" -> io.github.kamradsmeshnyavy.launcher.core.Apps.find(item.type)?.label ?: item.type
                    else -> item.type
                }
                AppMenu.Item(LineIcon.Shape.CHEVRON, name) { root.post { editItem(item, root) } }
            })
    }

    private fun newSpace() {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val index = desk.addSpace(this, (desk.state(this).spaces.size + 1).toString())
        home.rebuild()
        home.post { home.showPage(index) }
    }

    override fun editItem(item: io.github.kamradsmeshnyavy.launcher.core.Desk.Item, from: View) {
        val desk = io.github.kamradsmeshnyavy.launcher.core.Desk
        val catalog = io.github.kamradsmeshnyavy.launcher.ui.desk.Catalog
        root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val state = desk.state(this)
        val (spaceIndex, _) = desk.find(this, item.id) ?: return
        val space = state.spaces[spaceIndex]
        val grid = desk.layoutMode(this) == "grid"
        val title = when (item.kind) {
            "favourites" -> getString(R.string.w_favourites)
            "widget" -> catalog.spec(item.type)?.title?.let { getString(it) } ?: item.type
            else -> getString(R.string.app_name)
        }
        val sizes = when (item.kind) {
            "favourites" -> listOf(4 to 3, 4 to 2, 4 to 4, 4 to 5)
            "app" -> listOf(1 to 1)
            else -> catalog.spec(item.type)?.sizes ?: listOf(item.w to item.h)
        }
        val changed = {
            desk.save(this)
            home.rebuild()
        }
        val items = ArrayList<AppMenu.Item>()
        if (item.kind == "widget" || item.kind == "appwidget") items += AppMenu.Item(LineIcon.Shape.SETTINGS, getString(R.string.desk_options)) { editOptions(item) }
        if (sizes.size > 1) items += AppMenu.Item(LineIcon.Shape.GEAR, getString(R.string.desk_size)) {
            popup.show(anchor = from, title = getString(R.string.desk_size), items = sizes.map { (w, h) ->
                AppMenu.Item(LineIcon.Shape.CHECK, "$w × $h" + if (w == item.w && h == item.h) "  ●" else "") {
                    io.github.kamradsmeshnyavy.launcher.core.Desk.Grid.resize(space.items, item, w, h)
                    changed()
                }
            })
        }
        if (grid) {
            items += AppMenu.Item(LineIcon.Shape.HOME, getString(R.string.desk_move)) { home.startMove(item, root) }
        } else {
            items += AppMenu.Item(LineIcon.Shape.UP, getString(R.string.menu_move_up)) {
                desk.shift(space.items, item, -1)
                changed()
            }
            items += AppMenu.Item(LineIcon.Shape.DOWN, getString(R.string.menu_move_down)) {
                desk.shift(space.items, item, 1)
                changed()
            }
        }
        if (state.spaces.size > 1) items += AppMenu.Item(LineIcon.Shape.NEXT, getString(R.string.desk_to_space)) {
            popup.show(anchor = from, title = getString(R.string.desk_to_space), items = state.spaces.indices.filter { it != spaceIndex }.map { i ->
                AppMenu.Item(LineIcon.Shape.NEXT, state.spaces[i].name) {
                    space.items.remove(item)
                    desk.add(this, i, item)
                    home.rebuild()
                }
            })
        }
        items += AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.desk_remove), danger = true) {
            space.items.remove(item)
            if (item.kind == "appwidget") io.github.kamradsmeshnyavy.launcher.core.AppWidgets.delete(this, item.appWidgetId)
            if (grid) io.github.kamradsmeshnyavy.launcher.core.Desk.Grid.compact(space.items)
            changed()
        }
        popup.show(anchor = from, title = title, items = items)
    }

    // --- Apps --------------------------------------------------------------------

    private val appsChanged: () -> Unit = { refreshApps() }

    private val websChanged: () -> Unit = { refreshApps() }

    private val torchChanged: (Boolean) -> Unit = { drawer.setContent(entries(), prefs.hidden, actions, prefs.iconStyle) }

    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.ICONS -> {
                Apps.clearIcons()
                Webs.clearIcons()
                refreshApps()
                onUnread(Glyph.unread)
            }
            Prefs.FAVORITES, Prefs.FAVORITE_ICONS, Prefs.HIDDEN -> refreshApps()
            Prefs.WEBS -> {
                refreshApps()
                Webs.fetchIcons(this, prefs.webs)
            }
            Prefs.DIM -> home.setDim(prefs.dimWallpaper)
            Prefs.GLYPH -> onUnread(Glyph.unread)
        }
    }

    private fun refreshApps() {
        if (Apps.loaded && !prefs.favoritesChosen) prefs.favorites = defaultFavorites()
        val webs = prefs.webs
        val favorites = prefs.favorites.mapNotNull { key -> Apps.find(key) ?: webs.firstOrNull { it.key == key } }
        home.setFavorites(favorites, prefs.favoriteIcons, prefs.iconStyle)
        drawer.setContent(entries(webs), prefs.hidden, actions, prefs.iconStyle)
    }

    /** The apps and the webs, by name, as the drawer lists them. */
    private fun entries(webs: List<WebEntry> = prefs.webs): List<Entry> {
        if (webs.isEmpty()) return Apps.all
        val collator = Collator.getInstance()
        return (Apps.all + webs).sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** First run: phone, messages, camera and browser, if they're there. */
    private fun defaultFavorites(): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
        )
        return intents.mapNotNull { intent ->
            val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return@mapNotNull null
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == "android") null else Apps.byPackage(pkg)?.key
        }.distinct()
    }

    private fun launchApp(entry: AppEntry, from: View) {
        val bounds = Rect().also { from.getGlobalVisibleRect(it) }
        val options = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.width, from.height).toBundle()
        runCatching { Apps.launch(entry, bounds, options) }
            .onFailure { notice(getString(R.string.launch_failed, entry.label)) }
    }

    /** On the home screen or not, and where there: how every menu of an app or a web starts. */
    private fun homeItems(key: String): ArrayList<AppMenu.Item> {
        val favorites = prefs.favorites
        val index = favorites.indexOf(key)
        val items = ArrayList<AppMenu.Item>()
        if (index >= 0) {
            items += AppMenu.Item(LineIcon.Shape.MINUS, getString(R.string.menu_remove_home)) { prefs.removeFavorite(key) }
            if (index > 0) items += AppMenu.Item(LineIcon.Shape.UP, getString(R.string.menu_move_up)) { prefs.moveFavorite(key, -1) }
            if (index < favorites.size - 1) {
                items += AppMenu.Item(LineIcon.Shape.DOWN, getString(R.string.menu_move_down)) { prefs.moveFavorite(key, 1) }
            }
        } else {
            items += AppMenu.Item(LineIcon.Shape.PLUS, getString(R.string.menu_add_home)) { prefs.addFavorite(key) }
        }
        return items
    }

    private fun showWebMenu(web: WebEntry, from: View) {
        from.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val items = homeItems(web.key)
        items += AppMenu.Item(LineIcon.Shape.PENCIL, getString(R.string.web_edit)) {
            WebDialog.show(this, web) { prefs.saveWeb(it, replacing = web.url) }
        }
        items += AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.deck_remove), danger = true) { removeWeb(web) }
        popup.show(anchor = from, title = web.label, items = items)
    }

    private fun removeWeb(web: WebEntry) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(getString(R.string.deck_remove_web, web.label))
            .setPositiveButton(R.string.deck_remove) { _, _ -> prefs.removeWeb(web.url) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addWeb() = WebDialog.show(this, null) { web ->
        prefs.saveWeb(web)
        notice(getString(R.string.web_added, web.label))
    }

    private fun openWeb(web: WebEntry, from: View) {
        val options = ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.width, from.height).toBundle()
        if (!Webs.open(this, web, options)) notice(getString(R.string.open_failed))
    }

    private fun showAppMenu(entry: AppEntry, from: View) {
        from.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val items = homeItems(entry.key)
        if (entry.key in prefs.hidden) {
            items += AppMenu.Item(LineIcon.Shape.CHECK, getString(R.string.menu_unhide)) { prefs.unhide(entry.key) }
        } else {
            items += AppMenu.Item(LineIcon.Shape.HIDE, getString(R.string.menu_hide)) { prefs.hide(entry.key) }
        }
        items += AppMenu.Item(LineIcon.Shape.INFO, getString(R.string.menu_info)) {
            runCatching { Apps.showInfo(entry, Rect().also { from.getGlobalVisibleRect(it) }) }
        }
        if (entry.canUninstall) {
            items += AppMenu.Item(LineIcon.Shape.TRASH, getString(R.string.menu_uninstall), danger = true) {
                start(Apps.uninstallIntent(entry))
            }
        }
        popup.show(
            anchor = from,
            title = entry.label,
            items = items,
            shortcuts = Apps.shortcuts(entry),
            style = prefs.iconStyle,
            onShortcut = { shortcut: ShortcutInfo, view: View ->
                val bounds = Rect().also { view.getGlobalVisibleRect(it) }
                runCatching { Apps.startShortcut(shortcut, bounds, null) }
                    .onFailure { notice(getString(R.string.launch_failed, shortcut.shortLabel)) }
            },
        )
    }

    // HomeView.Host and DrawerView.Host

    override fun launch(entry: Entry, from: View) = when (entry) {
        is AppEntry -> launchApp(entry, from)
        is WebEntry -> openWeb(entry, from)
    }

    override fun menu(entry: Entry, from: View) = when (entry) {
        is AppEntry -> showAppMenu(entry, from)
        is WebEntry -> showWebMenu(entry, from)
    }

    override fun openShade() {
        if (!Access.expandNotifications(this)) notice(getString(R.string.shade_failed))
    }

    override fun run(action: Action) {
        val ok = Actions.run(action, this)
        if (ok && action.id == Actions.TORCH) animateDrawer(open = false)
    }

    override fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("NIHIL", text))
        notice("${getString(R.string.calc_copied)}: $text")
    }

    override fun webSearch(query: String) {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8")))
        start(web, fallback)
    }

    override fun hideKeyboard() {
        window.insetsController?.hide(WindowInsets.Type.ime())
    }

    override fun showKeyboard(view: View) {
        view.post {
            window.insetsController?.show(WindowInsets.Type.ime())
            getSystemService(InputMethodManager::class.java)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // --- ActionHost --------------------------------------------------------------

    override val context: Context get() = this

    override fun start(vararg intents: Intent): Boolean {
        val ok = Access.startFirst(this, intents.toList())
        if (!ok) notice(getString(R.string.open_failed))
        return ok
    }

    override fun lockScreen() {
        io.github.kamradsmeshnyavy.launcher.core.Sounds.play(this, io.github.kamradsmeshnyavy.launcher.core.Sounds.Event.LOCK)
        if (LockService.lock()) return
        notice(getString(R.string.lock_needs_service))
        Access.startFirst(this, listOf(Access.lockServiceIntent()))
    }

    override fun openStudio() {
        startActivity(Intent(this, WallpaperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun orderFavorites(keys: List<String>) {
        prefs.favorites = keys + prefs.favorites.filter { it !in keys }
    }

    override fun closeMenu() = popup.dismiss()

    override fun openTasks() {
        start(Intent(this, TasksActivity::class.java))
    }

    override fun openDeck() {
        start(Intent(this, DeckActivity::class.java))
    }

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun notice(text: String) = noticeView.show(text)

    override fun nextAlarmText(): String? {
        val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val inMs = next.triggerTime - System.currentTimeMillis()
        val skeleton = (if (DateFormat.is24HourFormat(this)) "Hm" else "hm").let { if (inMs > DAY) "EEE$it" else it }
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return SimpleDateFormat(pattern, Locale.getDefault()).format(next.triggerTime).replace(".", "")
    }

    // --- Glyph ---------------------------------------------------------------------

    override fun onUnread(packages: List<String>) {
        home.updateUnread(if (prefs.glyph) packages else emptyList(), prefs.iconStyle)
    }

    override fun onPulse(pulse: Glyph.Pulse) {
        if (prefs.glyph && Glyph.homeShowing) frame.pulse(pulse)
        if (Glyph.homeShowing) io.github.kamradsmeshnyavy.launcher.core.Sounds.play(this, pulse.sound())
    }

    // --- Time, battery, alarm ------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK -> run { home.updateClock(animate = true); bar?.tick() }
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> {
                    run { home.updateClock(animate = true); bar?.tick() }
                    updateAlarm()
                }
                Intent.ACTION_BATTERY_CHANGED -> battery(intent)
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> updateAlarm()
            }
        }
    }

    private fun register() {
        if (receiving) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        // System broadcasts only; nothing else needs to reach this receiver.
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiving = true
        sticky?.let { battery(it) }
    }

    private fun unregister() {
        if (!receiving) return
        runCatching { unregisterReceiver(receiver) }
        receiving = false
        charging = null
    }

    private fun battery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // The charger going in while NIHIL is on screen: light rises from the bottom.
        if (charging == false && plugged && prefs.glyph && Glyph.homeShowing) frame.pulse(Glyph.Pulse.CHARGE)
        charging = plugged
        home.updateBattery(level * 100 / scale, plugged)
        bar?.setBattery(level * 100 / scale, plugged)
    }

    /**
     * The first time NIHIL is the home app, its own wallpaper, as the desktop
     * starts with it: the night in LEDs, drawn here at the screen's size with
     * the studio's algorithm. Only on a fresh install: an update never changes
     * a wallpaper someone already has, and the studio changes it any time.
     */
    private fun seedWallpaper() {
        prefs.wallpaperSeeded = true
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return
        if (info.firstInstallTime != info.lastUpdateTime) return
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = minOf(bounds.width(), bounds.height())
        val height = maxOf(bounds.width(), bounds.height())
        val pitch = prefs.dotPitch
        val app = applicationContext
        // Ночное небо в цветах темы и с параметрами Dotify стиля.
        val look = io.github.kamradsmeshnyavy.launcher.core.Look.current(this)
        val tint = look.color("dotTint", "foreground")
        val background = look.color("dotBackground", "background")
        val gamma = look.num("dotGamma", 1.25).toFloat()
        val off = look.num("dotOff", 0.07).toFloat()
        Thread {
            val done = runCatching {
                val photo = DotRender.decodeCover(ImageDecoder.createSource(app.assets, DEFAULT_WALLPAPER), width, height)
                val dots = DotRender.render(DotRender.grid(photo, pitch, gamma, off), width, height, tint, background)
                WallpaperManager.getInstance(app).setBitmap(dots, null, true, WallpaperManager.FLAG_SYSTEM)
            }.isSuccess
            if (done) runOnUiThread { if (!isDestroyed) notice(getString(R.string.wallpaper_seeded)) }
        }.start()
    }

    private fun updateAlarm() {
        home.updateAlarm(nextAlarmText()?.let { getString(R.string.alarm_next, it) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BIND) {
            if (resultCode == RESULT_OK) configureAppWidget() else dropPending()
            return
        }
        if (requestCode == REQUEST_CONFIG) {
            if (resultCode == RESULT_OK) placeAppWidget() else dropPending()
            return
        }
        if (requestCode == REQUEST_IMAGES) {
            val then = picked ?: return
            picked = null
            if (resultCode != RESULT_OK || data == null) return
            val uris = ArrayList<android.net.Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
            data.data?.let { if (it !in uris) uris += it }
            uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            if (uris.isNotEmpty()) then(uris)
        }
    }

    private companion object {
        const val REQUEST_IMAGES = 41
        const val REQUEST_BIND = 42
        const val REQUEST_CONFIG = 43
        const val DAY = 24L * 60 * 60 * 1000
        const val DEFAULT_WALLPAPER = "wallpapers/night.jpg"
    }
}
