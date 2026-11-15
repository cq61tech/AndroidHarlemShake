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

class Show(private val activity: Activity, private val onEnd: () -> Unit) {

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
            solo = Dancer(chosen, random)
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
            if (d != null) applySolo(d, pos / 1000f)
        } else {
            if (!dropped) beginCrazy()
            val t = (pos - Proto.DROP_MS) / 1000f
            val ramp = min(1f, t / 0.15f)
            for (d in dancers) applyCrazy(d, t, ramp)
            overlay?.alpha = min(1f, t / 0.5f) * 0.55f
        }
    }

    private fun beginCrazy() {
        dropped = true
        val old = solo
        if (old != null) restore(old)
        val fresh = Targets.find(activity)
        val list = ArrayList<Dancer>()
        for (v in fresh) {
            val d = if (old != null && old.view === v) old else Dancer(v, random)
            list.add(d)
            unclip(v)
        }
        dancers = list
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
