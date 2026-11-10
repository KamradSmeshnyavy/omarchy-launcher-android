package io.github.kamradsmeshnyavy.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kamradsmeshnyavy.launcher.core.DotRender
import io.github.kamradsmeshnyavy.launcher.core.Look
import io.github.kamradsmeshnyavy.launcher.core.LookEdit
import io.github.kamradsmeshnyavy.launcher.core.Recolor
import io.github.kamradsmeshnyavy.launcher.ui.LedSlider
import io.github.kamradsmeshnyavy.launcher.core.Prefs
import io.github.kamradsmeshnyavy.launcher.ui.MATCH
import io.github.kamradsmeshnyavy.launcher.ui.Nihil
import io.github.kamradsmeshnyavy.launcher.ui.KeyButton
import io.github.kamradsmeshnyavy.launcher.ui.LineIcon
import io.github.kamradsmeshnyavy.launcher.ui.AppMenu
import io.github.kamradsmeshnyavy.launcher.ui.Choice
import io.github.kamradsmeshnyavy.launcher.ui.WRAP
import io.github.kamradsmeshnyavy.launcher.ui.dp
import io.github.kamradsmeshnyavy.launcher.ui.llp
import io.github.kamradsmeshnyavy.launcher.ui.lp
import io.github.kamradsmeshnyavy.launcher.ui.mono
import io.github.kamradsmeshnyavy.launcher.ui.rounded
import io.github.kamradsmeshnyavy.launcher.ui.text
import java.util.concurrent.Executors

/**
 * The wallpaper studio: a photo becomes an LED matrix at the exact resolution
 * of the screen, with the same algorithm as the desktop's wallpapers. All on
 * the phone; nothing is uploaded anywhere.
 */
class WallpaperActivity : LookActivity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: ImageView
    private lateinit var empty: LinearLayout
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var mode: Choice

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0
    private var cover: Bitmap? = null
    private var result: Bitmap? = null
    private var generation = 0

    /** Шаг сетки Dotify в пикселях: dotPitch Customizer'а (логические px десктопа) ≈ 0.75 dp. */
    private fun pitchPx(): Int = dp((Look.current(this).num("dotPitch", 9.0) * 0.75).toFloat()).toInt().coerceAtLeast(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        // Edge to edge on every version (Android 15 enforces it; 12-14 need the call).
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false

        val bounds = windowManager.maximumWindowMetrics.bounds
        screenW = minOf(bounds.width(), bounds.height())
        screenH = maxOf(bounds.width(), bounds.height())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Nihil.VOID)
        }
        setContentView(column)
        column.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(bars.left + dp(20), bars.top + dp(16), bars.right + dp(20), bars.bottom + dp(16))
            insets
        }

        // Title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(getString(R.string.studio_title), 22f, Nihil.INK, Nihil.fonts(this).sans), llp(0, WRAP, 1f))
        header.addView(KeyButton(this, getString(R.string.studio_pick)).apply { setOnClickListener { pick() } })
        column.addView(header, llp(MATCH, WRAP))

        // The preview, in the screen's proportions.
        val stage = AspectFrame(this, screenW.toFloat() / screenH)
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Nihil.VOID, dp(22f), dp(1), Nihil.LINE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22f))
            }
            clipToOutline = true
        }
        stage.addView(preview, lp(MATCH, MATCH))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            addView(text(getString(R.string.studio_empty), 15f, Nihil.DIM, Nihil.fonts(this@WallpaperActivity).sans).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(4f), 1f)
            })
            addView(KeyButton(this@WallpaperActivity, getString(R.string.studio_pick), accent = true).apply {
                setOnClickListener { pick() }
            }, llp(WRAP, WRAP).apply { topMargin = dp(22) })
        }
        stage.addView(empty, lp(MATCH, MATCH))
        column.addView(stage, llp(MATCH, 0, 1f).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        })

        // Controls.
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Dotify (LED-матрица), Recolor (в цвета темы) или как есть.
        mode = Choice(
            this,
            listOf(getString(R.string.studio_mode_dotify), getString(R.string.studio_mode_recolor), getString(R.string.studio_mode_plain)),
            0,
        ) { render() }
        controls.addView(labelled(R.string.studio_mode, mode))
        val look = Look.current(this)
        val pitch = LedSlider(this, 6.0, 24.0, 1.0, look.num("dotPitch", 9.0)) { value ->
            LookEdit.set(this, "dotPitch", value)
            render()
        }
        controls.addView(labelled(R.string.studio_dots, pitch), llp(MATCH, WRAP))
        controls.addView(mono(getString(R.string.studio_colours_hint), 10f, Nihil.MUTED), llp(MATCH, WRAP).apply { topMargin = dp(4) })

        // One key to apply; then where: the home screen, the lock screen or both.
        val apply = KeyButton(this, getString(R.string.studio_apply_button), accent = true)
        apply.setOnClickListener {
            AppMenu(this).show(anchor = apply, title = getString(R.string.studio_where), items = listOf(
                AppMenu.Item(LineIcon.Shape.HOME, getString(R.string.apply_home)) { apply(WallpaperManager.FLAG_SYSTEM) },
                AppMenu.Item(LineIcon.Shape.LOCK, getString(R.string.apply_lock)) { apply(WallpaperManager.FLAG_LOCK) },
                AppMenu.Item(LineIcon.Shape.IMAGE, getString(R.string.apply_both)) {
                    apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                },
            ))
        }
        controls.addView(apply, llp(WRAP, WRAP).apply { topMargin = dp(16) })
        status = mono("", 11f, Nihil.DIM).apply {
            gravity = Gravity.CENTER
            minHeight = dp(18)
        }
        controls.addView(status, llp(MATCH, WRAP).apply { topMargin = dp(14) })
        column.addView(controls, llp(MATCH, WRAP))
        showControls(false)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun labelled(label: Int, control: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(mono(getString(label), 11f, Nihil.MUTED), llp(dp(92), WRAP))
        addView(control, if (control is LedSlider) llp(0, WRAP, 1f) else llp(WRAP, WRAP))
    }

    private fun showControls(on: Boolean) {
        controls.alpha = if (on) 1f else 0.35f
        setEnabledDeep(controls, on)
        empty.visibility = if (on || cover != null) View.GONE else View.VISIBLE
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    @Suppress("DEPRECATION")
    private fun pick() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        runCatching { startActivityForResult(intent, PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && uri != null) load(uri)
    }

    private fun load(uri: Uri) {
        val job = ++generation
        status.text = getString(R.string.studio_rendering)
        val resolver = contentResolver
        worker.execute {
            val decoded = runCatching { DotRender.decodeCover(resolver, uri, screenW, screenH) }.getOrNull()
            main.post {
                if (job != generation) return@post
                if (decoded == null) {
                    status.text = getString(R.string.studio_failed)
                    return@post
                }
                cover = decoded
                render()
            }
        }
    }

    private fun render() {
        val photo = cover ?: return
        val job = ++generation
        val look = Look.current(this)
        val pitch = pitchPx()
        val gamma = look.num("dotGamma", 1.25).toFloat()
        val off = look.num("dotOff", 0.07).toFloat()
        val tint = look.color("dotTint", "foreground")
        val background = look.color("dotBackground", "background")
        val palette = look.theme?.let { Recolor.palette(it) }
        val which = mode.selected
        status.text = getString(R.string.studio_rendering)
        worker.execute {
            val bitmap = runCatching {
                when {
                    which == 0 -> DotRender.render(DotRender.grid(photo, pitch, gamma, off), screenW, screenH, tint, background)
                    which == 1 && palette != null && palette.isNotEmpty() -> DotRender.recolor(photo, palette)
                    else -> photo
                }
            }.getOrNull()
            main.post {
                if (job != generation) return@post
                if (bitmap == null) {
                    status.text = getString(R.string.studio_failed)
                    return@post
                }
                result = bitmap
                preview.setImageBitmap(bitmap)
                status.text = ""
                showControls(true)
            }
        }
    }

    private fun apply(which: Int) {
        val bitmap = result ?: return
        status.text = getString(R.string.studio_rendering)
        val context: Context = applicationContext
        worker.execute {
            val ok = runCatching { WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, which) }.isSuccess
            main.post { status.text = getString(if (ok) R.string.studio_done else R.string.studio_failed) }
        }
    }

    /** A frame that keeps a width / height ratio inside whatever space it gets. */
    private class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            val maxH = MeasureSpec.getSize(heightMeasureSpec)
            var w = maxW
            var h = (w / ratio).toInt()
            if (maxH in 1 until h) {
                h = maxH
                w = (h * ratio).toInt()
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
            setMeasuredDimension(maxW, h)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            // Centre the content horizontally.
            val inner = (measuredHeight * ratio).toInt().coerceAtMost(measuredWidth)
            val dx = (measuredWidth - inner) / 2
            for (i in 0 until childCount) getChildAt(i).layout(dx, 0, dx + inner, measuredHeight)
        }
    }

    private companion object {
        const val PICK = 1
    }
}
