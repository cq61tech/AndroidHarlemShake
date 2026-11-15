package dev.harlem.shake

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

object Targets {
    private val icons = arrayOf("BubbleTextView", "FolderIcon", "AppWidgetHostView")
    private val apps = arrayOf("BubbleTextView")
    private val skip = arrayOf("AllApps")

    fun find(activity: Activity): List<View> {
        val out = ArrayList<View>()
        walk(activity.window.decorView, out, Rect(), 1f)
        return out
    }

    fun isApp(view: View): Boolean {
        return matches(view, apps)
    }

    private fun walk(view: View, out: MutableList<View>, rect: Rect, alpha: Float) {
        if (view.visibility != View.VISIBLE) return
        val a = alpha * view.alpha
        if (a < 0.1f) return
        if (matches(view, skip)) return
        if (matches(view, icons)) {
            val sized = view.width > 0 && view.height > 0
            if (sized && view.getGlobalVisibleRect(rect) &&
                rect.width() * 2 > view.width && rect.height() * 2 > view.height
            ) {
                out.add(view)
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), out, rect, a)
            }
        }
    }

    private fun matches(view: View, keys: Array<String>): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null && c != View::class.java) {
            val n = c.name
            for (k in keys) {
                if (n.contains(k)) return true
            }
            c = c.superclass
        }
        return false
    }
}
