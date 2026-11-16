package dev.harlem.shake

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

private val keyTimes = floatArrayOf(0f, 0.1f, 0.3f, 0.4f, 0.5f, 0.7f, 0.8f, 1f)
private val keyXs = floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f, 0f, 0f)
private val keyYs = floatArrayOf(0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f)

private fun beatFreq(r: Random): Float {
    val f = floatArrayOf(1.15f, 2.3f, 3.45f, 4.6f)
    return f[r.nextInt(f.size)]
}

private fun smooth(x: Float): Float {
    val c = min(1f, max(0f, x))
    return c * c * (3f - 2f * c)
}

private fun easeOut(x: Float): Float {
    val c = min(1f, max(0f, x))
    return 1f - (1f - c) * (1f - c)
}

private class Style(val kind: Int, val seed: Float)

private class Pose {
    var dx = 0f
    var dy = 0f
    var rot = 0f
    var scale = 1f

    fun reset() {
        dx = 0f
        dy = 0f
        rot = 0f
        scale = 1f
    }
}

class Show(
    private val activity: Activity,
    private val nostalgic: Boolean,
    private val onEnd: () -> Unit
) {

    var active = false
        private set

    private class Dancer(val view: View, r: Random) {
        val baseX = view.translationX
        val baseY = view.translationY
        val baseRot = view.rotation
        val baseRx = view.rotationX
        val baseRy = view.rotationY
        val baseSx = view.scaleX
        val baseSy = view.scaleY
        val w = max(view.width, 1).toFloat()
        val h = max(view.height, 1).toFloat()
        val ax = (0.5f + r.nextFloat() * 2f) * w
        val ay = (0.5f + r.nextFloat() * 2.5f) * h
        val arot = 25f + r.nextFloat() * 70f
        val asc = 0.25f + r.nextFloat() * 0.55f
        val flip = if (r.nextFloat() < 0.4f) 30f + r.nextFloat() * 50f else 0f
        val fx = beatFreq(r)
        val fy = beatFreq(r)
        val fr = beatFreq(r)
        val fs = beatFreq(r)
        val px = r.nextFloat() * TAU
        val py = r.nextFloat() * TAU
        val pr = r.nextFloat() * TAU
        val ps = r.nextFloat() * TAU
        var styleA: Style? = null
        var styleB: Style? = null
    }

    private val decor = activity.window.decorView as ViewGroup
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(SystemClock.uptimeMillis())
    private val density = activity.resources.displayMetrics.density
    private val pose = Pose()
    private val clipState = LinkedHashMap<ViewGroup, BooleanArray>()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var overlay: View? = null
    private var wallpaper: Drawable? = null
    private var replica: Dancer? = null
    private var dancers = ArrayList<Dancer>()
    private var solo: Dancer? = null
    private var dropped = false
    private var anchorPos = 0L
    private var anchorTime = 0L
    private var lastRaw = -1
    private var lastEstimate = 0L
    private var hiddenSince = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            tick()
            if (active) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun start() {
        active = true
        val list = Targets.find(activity)
        val apps = list.filter { Targets.isApp(it) }
        val pool = if (apps.isNotEmpty()) apps else list
        if (pool.isNotEmpty()) {
            val chosen = pool[random.nextInt(pool.size)]
            val d = Dancer(chosen, random)
            if (nostalgic) {
                d.styleB = Style(2, random.nextFloat())
            }
            solo = d
            unclip(chosen)
        }
        if (nostalgic) {
            wallpaper = Backdrop.load(activity).drawable
        }
        addOverlay()
        startAudio()
    }

    fun stop() {
        if (!active) return
        active = false
        Choreographer.getInstance().removeFrameCallback(frame)
        val mp = player
        player = null
        if (mp != null) {
            try {
                mp.stop()
            } catch (e: IllegalStateException) {
                lastRaw = -1
            }
            mp.release()
        }
        val request = focus
        focus = null
        if (request != null) {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(request)
        }
        val all = ArrayList<Dancer>(dancers)
        val s = solo
        if (s != null && !all.contains(s)) all.add(s)
        val rep = replica
        if (rep != null) all.add(rep)
        for (d in all) settle(d)
        if (replica == null) {
            overlay?.animate()?.alpha(0f)?.setDuration(300)?.start()
        }
        handler.postDelayed({ cleanup() }, 350)
        onEnd()
    }

    private fun startAudio() {
        try {
            val pkgContext = activity.createPackageContext(Proto.APP_PACKAGE, 0)
            val res = pkgContext.resources
            val id = res.getIdentifier("harlem_shake", "raw", Proto.APP_PACKAGE)
            val afd = res.openRawResourceFd(id)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.setOnPreparedListener { prepared ->
                requestFocus(attrs)
                prepared.start()
                beginFrames()
            }
            player = mp
            mp.prepareAsync()
        } catch (t: Throwable) {
            stop()
        }
    }

    private fun requestFocus(attrs: AudioAttributes) {
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .build()
        am.requestAudioFocus(request)
        focus = request
    }

    private fun beginFrames() {
        anchorTime = SystemClock.uptimeMillis()
        anchorPos = 0L
        lastRaw = -1
        lastEstimate = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun position(): Long {
        val mp = player ?: return lastEstimate
        val raw = try {
            mp.currentPosition
        } catch (e: IllegalStateException) {
            max(lastRaw, 0)
        }
        val now = SystemClock.uptimeMillis()
        if (raw != lastRaw) {
            lastRaw = raw
            anchorPos = raw.toLong()
            anchorTime = now
        }
        val estimate = anchorPos + (now - anchorTime)
        if (estimate > lastEstimate) lastEstimate = estimate
        return lastEstimate
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (activity.isDestroyed || !decor.isShown) {
            if (hiddenSince == 0L) hiddenSince = now
            if (now - hiddenSince > 2500L) {
                stop()
                return
            }
        } else {
            hiddenSince = 0L
        }
        val pos = position()
        if (pos >= Proto.END_MS) {
            stop()
            return
        }
        if (pos > Proto.END_MS - 1500L) {
            val vol = (Proto.END_MS - pos) / 1500f
            player?.setVolume(vol, vol)
        }
        if (pos < Proto.DROP_MS) {
            val d = solo
            if (d != null) {
                if (nostalgic) {
                    applyStyles(d, pos / 1000f, density * Proto.SOLO_AMP)
                } else {
                    applySolo(d, pos / 1000f)
                }
            }
        } else {
            if (!dropped) beginCrazy()
            val t = (pos - Proto.DROP_MS) / 1000f
            if (nostalgic) {
                for (d in dancers) applyStyles(d, t, density)
                val rep = replica
                if (rep != null) applyPanel(rep, t)
            } else {
                val ramp = min(1f, t / 0.15f)
                for (d in dancers) applyCrazy(d, t, ramp)
                overlay?.alpha = min(1f, t / 0.5f) * 0.55f
            }
        }
    }

    private fun assignStyles(d: Dancer) {
        val a = random.nextInt(3)
        val b = random.nextInt(3)
        d.styleB = Style(b, random.nextFloat())
        d.styleA = if (a == b) null else Style(a, random.nextFloat())
    }

    private fun beginCrazy() {
        dropped = true
        val old = solo
        if (old != null) restore(old)
        val fresh = Targets.find(activity)
        val list = ArrayList<Dancer>()
        for (v in fresh) {
            val d = if (old != null && old.view === v) old else Dancer(v, random)
            if (nostalgic) assignStyles(d)
            list.add(d)
            unclip(v)
        }
        dancers = list
        val wp = wallpaper
        if (nostalgic && wp != null) {
            val iv = ImageView(activity)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageDrawable(wp)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            decor.addView(iv, 1, lp)
            overlay?.alpha = 1f
            replica = Dancer(iv, random)
        }
    }

    private fun applyStyles(d: Dancer, t: Float, unit: Float) {
        pose.reset()
        val a = d.styleA
        if (a != null) evalStyle(a, t, unit)
        val b = d.styleB
        if (b != null) evalStyle(b, t, unit)
        val v = d.view
        v.translationX = d.baseX + pose.dx
        v.translationY = d.baseY + pose.dy
        v.rotation = d.baseRot + pose.rot
        v.scaleX = d.baseSx * pose.scale
        v.scaleY = d.baseSy * pose.scale
    }

    private fun evalStyle(style: Style, t: Float, unit: Float) {
        val seed = style.seed
        val p = pose
        when (style.kind) {
            0 -> {
                val rd = 1f + seed
                val pd = 0.5f + seed
                val total = rd + pd
                val idx = floor(t / total).toInt()
                var u = t - idx * total
                if (idx % 2 == 1) u = total - u
                if (u < rd) {
                    val frac = u / rd
                    p.rot = if (seed < 0.5f) 360f * (1f - frac) else 360f * frac
                    p.scale = 1f
                } else {
                    p.rot = 0f
                    p.scale = 1f + 0.2f * ((u - rd) / pd)
                }
            }
            1 -> {
                val per = 1f + seed
                val ph = (t % per) / per
                val sign = if (seed < 0.5f) 1f else -1f
                val s2 = 1f + seed * sign
                val s3 = 1f - seed * sign
                val s = if (ph < 0.4f) {
                    1f + (s2 - 1f) * easeOut(ph / 0.4f)
                } else if (ph < 0.7f) {
                    s2 + (s3 - s2) * smooth((ph - 0.4f) / 0.3f)
                } else {
                    s3 + (1f - s3) * smooth((ph - 0.7f) / 0.3f)
                }
                p.dx = 0f
                p.dy = 0f
                p.rot = 0f
                p.scale = max(0.02f, s)
            }
            else -> {
                val per = 1f + seed
                if (t <= per * 10f) {
                    val ph = (t % per) / per
                    val sign = if (seed < 0.5f) 1f else -1f
                    val o = (10f + 20f * seed) * sign * unit
                    var i = 0
                    while (i < keyTimes.size - 2 && ph >= keyTimes[i + 1]) i++
                    val k = smooth((ph - keyTimes[i]) / (keyTimes[i + 1] - keyTimes[i]))
                    p.dx = (keyXs[i] + (keyXs[i + 1] - keyXs[i]) * k) * o
                    p.dy = (keyYs[i] + (keyYs[i + 1] - keyYs[i]) * k) * o
                }
            }
        }
    }

    private fun applyPanel(d: Dancer, t: Float) {
        val ramp = min(1f, t / 0.2f)
        val w = decor.width.toFloat()
        val h = decor.height.toFloat()
        val cycle = (t / 5f) % 1f
        val spin = 360f * smooth(cycle * 2f)
        val rot = 25f * sin(TAU * 0.6f * t) + spin
        val s = 0.75f + 0.35f * sin(TAU * 0.55f * t) + 0.06f * sin(TAU * 2.3f * t)
        val dy = -abs(sin(PI_F * 1.15f * t)) * 0.08f * h
        val dx = 0.1f * w * sin(TAU * 0.4f * t)
        val v = d.view
        v.translationX = d.baseX + ramp * dx
        v.translationY = d.baseY + ramp * dy
        v.rotation = d.baseRot + ramp * rot
        val sc = 1f + ramp * (s - 1f)
        v.scaleX = d.baseSx * sc
        v.scaleY = d.baseSy * sc
    }

    private fun applySolo(d: Dancer, t: Float) {
        val hop = abs(sin(PI_F * t / 0.435f))
        val v = d.view
        v.translationY = d.baseY - hop * 0.6f * d.h
        v.translationX = d.baseX + sin(TAU * t / 1.74f) * 0.45f * d.w
        v.rotation = d.baseRot + sin(TAU * t / 0.87f) * 9f
        val s = 1f + 0.1f * hop
        v.scaleX = d.baseSx * s
        v.scaleY = d.baseSy * s
    }

    private fun applyCrazy(d: Dancer, t: Float, ramp: Float) {
        val v = d.view
        v.translationX = d.baseX + ramp * d.ax * sin(TAU * d.fx * t + d.px)
        v.translationY = d.baseY + ramp * d.ay * sin(TAU * d.fy * t + d.py)
        v.rotation = d.baseRot + ramp * d.arot * sin(TAU * d.fr * t + d.pr)
        val s = max(0.4f, 1f + ramp * d.asc * sin(TAU * d.fs * t + d.ps))
        v.scaleX = d.baseSx * s
        v.scaleY = d.baseSy * s
        if (d.flip > 0f) {
            v.rotationY = d.baseRy + ramp * d.flip * sin(TAU * d.fr * 0.5f * t + d.px)
        }
    }

    private fun restore(d: Dancer) {
        val v = d.view
        v.translationX = d.baseX
        v.translationY = d.baseY
        v.rotation = d.baseRot
        v.rotationX = d.baseRx
        v.rotationY = d.baseRy
        v.scaleX = d.baseSx
        v.scaleY = d.baseSy
    }

    private fun settle(d: Dancer) {
        var delta = (d.view.rotation - d.baseRot) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        d.view.rotation = d.baseRot + delta
        d.view.animate()
            .translationX(d.baseX)
            .translationY(d.baseY)
            .rotation(d.baseRot)
            .rotationX(d.baseRx)
            .rotationY(d.baseRy)
            .scaleX(d.baseSx)
            .scaleY(d.baseSy)
            .setDuration(300)
            .start()
    }

    private fun unclip(view: View) {
        var p = view.parent
        while (p is ViewGroup) {
            if (!clipState.containsKey(p)) {
                clipState[p] = booleanArrayOf(p.clipChildren, p.clipToPadding)
                p.clipChildren = false
                p.clipToPadding = false
            }
            if (p === decor) break
            p = p.parent
        }
    }

    private fun addOverlay() {
        val v = View(activity)
        v.setBackgroundColor(Color.BLACK)
        v.alpha = 0f
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        decor.addView(v, 0, lp)
        overlay = v
    }

    private fun cleanup() {
        val rep = replica
        if (rep != null) decor.removeView(rep.view)
        replica = null
        val o = overlay
        if (o != null) decor.removeView(o)
        overlay = null
        for ((group, flags) in clipState) {
            group.clipChildren = flags[0]
            group.clipToPadding = flags[1]
        }
        clipState.clear()
    }
}
