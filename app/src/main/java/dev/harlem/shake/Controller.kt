package dev.harlem.shake

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.lang.ref.WeakReference

object Controller {
    private var registered = false
    private var activityRef: WeakReference<Activity>? = null
    private var show: Show? = null
    private var lastMode = -1

    fun attach(activity: Activity) {
        if (!looksLikeLauncher(activity)) return
        activityRef = WeakReference(activity)
        if (registered) return
        registered = true
        val context = activity.applicationContext
        val filter = IntentFilter()
        filter.addAction(Proto.ACTION_TOGGLE)
        filter.addAction(Proto.ACTION_PING)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    handle(c, intent)
                } catch (t: Throwable) {
                    show = null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun looksLikeLauncher(activity: Activity): Boolean {
        var c: Class<*>? = activity.javaClass
        while (c != null && c != Activity::class.java) {
            val n = c.name
            if (n.endsWith(".Launcher") || n.contains("LauncherActivity") || n.endsWith("QuickstepLauncher")) {
                return true
            }
            c = c.superclass
        }
        val intent = activity.intent
        return intent != null && intent.hasCategory(Intent.CATEGORY_HOME)
    }

    private fun handle(context: Context, intent: Intent) {
        val activity = activityRef?.get()
        val alive = activity != null && !activity.isDestroyed
        when (intent.action) {
            Proto.ACTION_PING -> {
                var count = -1
                if (alive && activity != null) {
                    count = Targets.find(activity).size
                }
                val reply = Intent(Proto.ACTION_PONG)
                reply.setPackage(Proto.APP_PACKAGE)
                reply.putExtra(Proto.EXTRA_ICONS, count)
                reply.putExtra(Proto.EXTRA_PACKAGE, context.packageName)
                reply.putExtra(Proto.EXTRA_BUILD, Proto.BUILD)
                reply.putExtra(Proto.EXTRA_LAST_MODE, lastMode)
                context.sendBroadcast(reply)
            }
            Proto.ACTION_TOGGLE -> {
                val running = show
                if (running != null && running.active) {
                    running.stop()
                    show = null
                } else if (alive && activity != null) {
                    val mode = intent.getIntExtra(Proto.EXTRA_MODE, Proto.MODE_NOSTALGIC)
                    lastMode = mode
                    val nostalgic = mode == Proto.MODE_NOSTALGIC
                    val created = Show(activity, nostalgic) { show = null }
                    show = created
                    created.start()
                }
            }
        }
    }
}
