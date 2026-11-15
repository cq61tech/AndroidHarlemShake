package dev.harlem.shake

import android.app.Activity
import android.content.Context
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

private fun beatFreq(r: Random): Float {
    val f = floatArrayOf(1.15f, 2.3f, 3.45f, 4.6f)
    return f[r.nextInt(f.size)]
}

private fun locX(v: View): Int {
    val a = IntArray(2)
    v.getLocationOnScreen(a)
    return a[0]
}

private fun smooth(x: Float): Float {
    val c = min(1f, max(0f, x))
    return c * c * (3f - 2f * c)
}

private fun tri(u: Float): Float {
    val f = u - floor(u)
    return 1f - abs(2f * f - 1f)
}

class Show(
    private val activity: Activity,
    private val nostalgic: Boolean,
    private val onEnd: () -> Unit
) {

    var active = false
        private set

    private class Dancer(val view: View, r: Random, sw: Int) {
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
        val f = beatFreq(r)
        val fx = beatFreq(r)
        val fy = beatFreq(r)
        val fr = beatFreq(r)
        val fs = beatFreq(r)
        val px = r.nextFloat() * TAU
        val py = r.nextFloat() * TAU
        val pr = r.nextFloat() * TAU
        val ps = r.nextFloat() * TAU
        val dir = if (r.nextBoolean()) 1f else -1f
        val sp = 0.5f + r.nextFloat()
        val amp = (0.9f + r.nextFloat() * 1.6f) * h
        val gapLeft = max(0, locX(view)).toFloat()
        val gapRight = max(0f, sw - locX(view) - w)
        var type = 0
    }

    private val decor = activity.window.decorView as ViewGroup
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(SystemClock.uptimeMillis())
    private val clipState = LinkedHashMap<ViewGroup, BooleanArray>()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var overlay: View? = null
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
            solo = Dancer(chosen, random, decor.width)
            unclip(chosen)
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
        for (d in all) settle(d)
        overlay?.animate()?.alpha(0f)?.setDuration(300)?.start()
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
                if (nostalgic) applySoloNostalgic(d, pos / 1000f) else applySolo(d, pos / 1000f)
            }
        } else {
            if (!dropped) beginCrazy()
            val t = (pos - Proto.DROP_MS) / 1000f
            val ramp = min(1f, t / 0.15f)
            if (nostalgic) {
                for (d in dancers) applyNostalgic(d, t, ramp)
                val phase = (t % 0.87f) / 0.87f
                overlay?.alpha = if (phase < 0.45f) 0.75f else 0.1f
            } else {
                for (d in dancers) applyCrazy(d, t, ramp)
                overlay?.alpha = min(1f, t / 0.5f) * 0.55f
            }
        }
    }

    private fun beginCrazy() {
        dropped = true
        val old = solo
        if (old != null) restore(old)
        val fresh = Targets.find(activity)
        val list = ArrayList<Dancer>()
        for (v in fresh) {
            val d = if (old != null && old.view === v) old else Dancer(v, random, decor.width)
            list.add(d)
            unclip(v)
        }
        if (nostalgic) {
            val head = listOf(0, 1, 2, 3, 4).shuffled(random)
            val tail = listOf(5, 6, 7, 8, 9, 10).shuffled(random)
            val order = head + tail
            for (i in list.indices) {
                list[i].type = order[i % order.size]
            }
        }
        dancers = list
    }

    private fun applySoloNostalgic(d: Dancer, t: Float) {
        val u = t % 2f
        var dx = 0f
        var dy = 0f
        if (u < 0.6f) {
            dy = -sin(PI_F * u / 0.6f) * 0.4f * d.h
        } else if (u < 1.3f) {
            dx = sin(PI_F * (u - 0.6f) / 0.7f) * 0.5f * d.w
        } else {
            dx = -sin(PI_F * (u - 1.3f) / 0.7f) * 0.5f * d.w
        }
        val v = d.view
        v.translationX = d.baseX + dx
        v.translationY = d.baseY + dy
    }

    private fun applyNostalgic(d: Dancer, t: Float, ramp: Float) {
        var dx = 0f
        var dy = 0f
        var rot = 0f
        var sx = 1f
        var sy = 1f
        when (d.type) {
            0 -> {
                rot = d.dir * 360f * d.sp * t
            }
            1 -> {
                val s = 0.25f + 2.95f * (0.5f + 0.5f * sin(TAU * d.sp * 0.8f * t + d.px))
                sx = s
                sy = s
            }
            2 -> {
                dy = -d.amp * abs(sin(PI_F * d.f * t + d.px))
            }
            3 -> {
                val edge = if (d.dir > 0f) d.gapRight else d.gapLeft
                val dist = edge + d.w * 1.3f
                val k = smooth(tri(t * 0.45f * d.sp + d.px / TAU))
                dx = d.dir * dist * k
                rot = d.dir * 14f * k
            }
            4 -> {
                val om = TAU * 0.45f * d.sp
                val radius = d.ax * 1.3f
                dx = radius * sin(om * t + d.px)
                dy = radius * 0.6f * sin(2f * om * t + d.px)
                rot = 18f * cos(om * t + d.px)
            }
            5 -> {
                dx = 0.12f * d.w * sin(TAU * 11f * t + d.px)
                dy = 0.12f * d.h * sin(TAU * 13f * t + d.py)
                rot = 12f * sin(TAU * 9f * t + d.pr)
            }
            6 -> {
                val a = TAU * d.f * 0.5f * t + d.px
                rot = 40f * sin(a)
                dx = 0.25f * d.w * sin(a)
            }
            7 -> {
                val a = TAU * d.f * 0.5f * t + d.px
                sx = 1f + 0.8f * sin(a)
                sy = 1f - 0.5f * sin(a)
            }
            8 -> {
                dy = d.ay * 0.9f * sin(TAU * d.sp * 0.4f * t + d.py)
            }
            9 -> {
                dx = d.ax * 0.8f * sin(TAU * d.sp * 0.5f * t + d.px)
                rot = dx / (d.w * 0.5f) * 57.29578f
            }
            else -> {
                dx = d.ax * (tri(t * 0.35f * d.sp + d.px / TAU) * 2f - 1f)
                dy = d.ay * (tri(t * 0.27f * d.sp + d.py / TAU) * 2f - 1f)
            }
        }
        val v = d.view
        v.translationX = d.baseX + ramp * dx
        v.translationY = d.baseY + ramp * dy
        v.rotation = d.baseRot + ramp * rot
        v.scaleX = d.baseSx * (1f + ramp * (sx - 1f))
        v.scaleY = d.baseSy * (1f + ramp * (sy - 1f))
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
