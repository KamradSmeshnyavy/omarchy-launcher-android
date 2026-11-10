package io.github.kamradsmeshnyavy.launcher.ui

import android.graphics.Bitmap
import android.widget.ImageView
import io.github.kamradsmeshnyavy.launcher.core.AppEntry
import io.github.kamradsmeshnyavy.launcher.core.Apps
import io.github.kamradsmeshnyavy.launcher.core.Entry
import io.github.kamradsmeshnyavy.launcher.core.IconStyle
import io.github.kamradsmeshnyavy.launcher.core.WebEntry
import io.github.kamradsmeshnyavy.launcher.core.Webs

/** [entry]'s icon here: at once when it is cached, or once painted, if this still shows it. */
fun ImageView.bindIcon(entry: Entry, size: Int, style: IconStyle) {
    tag = entry.key
    setImageDrawable(null)
    val ready: (Bitmap) -> Unit = { bitmap -> if (tag == entry.key) setImageBitmap(bitmap) }
    val cached = when (entry) {
        is AppEntry -> Apps.icon(entry, size, style, ready)
        is WebEntry -> Webs.icon(context, entry, size, style, ready)
    }
    if (cached != null) setImageBitmap(cached)
}
