package dev.harlem.shake

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object Backdrop {
    class Result(val drawable: Drawable, val source: String)

    fun load(context: Context): Result {
        val wm = WallpaperManager.getInstance(context)

        try {
            val d = wm.drawable
            if (d != null) return Result(d, "drawable")
        } catch (t: Throwable) {
            Unit
        }

        try {
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
            if (pfd != null) {
                val opts = BitmapFactory.Options()
                opts.inSampleSize = 2
                val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                pfd.close()
                if (bmp != null) return Result(BitmapDrawable(context.resources, bmp), "file")
            }
        } catch (t: Throwable) {
            Unit
        }

        try {
            val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                val list = ArrayList<Int>()
                list.add(colors.primaryColor.toArgb())
                val second = colors.secondaryColor
                if (second != null) list.add(second.toArgb())
                val third = colors.tertiaryColor
                if (third != null) list.add(third.toArgb())
                if (list.size == 1) list.add(list[0] and 0x00FFFFFF or 0x66000000)
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, list.toIntArray())
                return Result(g, "colors")
            }
        } catch (t: Throwable) {
            Unit
        }

        val g = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1E3C72.toInt(), 0xFF2A5298.toInt())
        )
        return Result(g, "default")
    }
}
