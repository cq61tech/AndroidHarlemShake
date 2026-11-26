package dev.harlem.shake

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var answered = false

    private val pong = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            answered = true
            val n = intent.getIntExtra(Proto.EXTRA_ICONS, -1)
            val who = intent.getStringExtra(Proto.EXTRA_PACKAGE) ?: ""
            val build = intent.getIntExtra(Proto.EXTRA_BUILD, -1)
            val last = intent.getIntExtra(Proto.EXTRA_LAST_MODE, -1)
            val wallpaperSource = intent.getStringExtra(Proto.EXTRA_WALLPAPER) ?: "unknown"
            val lastName = when (last) {
                Proto.MODE_MODERN -> "modern"
                Proto.MODE_NOSTALGIC -> "nostalgic"
                else -> "none yet"
            }
            val icons = if (n > 0) "$n icons visible." else "Icons are counted only while the home screen is open."
            if (build != Proto.BUILD) {
                status.text = "The launcher is running an old copy of the module (build $build, app build ${Proto.BUILD}). Reboot the phone, then check again."
            } else {
                status.text = "Module is active in $who. Build $build. Last mode received: $lastName. Wallpaper: $wallpaperSource. $icons"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { CqStorePrompt.showIfNeeded(this) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this)
        status.textSize = 16f
        status.text = "Enable the module in LSPosed, select your launcher in its scope, then force stop the launcher."
        root.addView(status)

        val group = RadioGroup(this)
        val nostalgicButton = RadioButton(this)
        nostalgicButton.id = 102
        nostalgicButton.text = "Nostalgic: iOS 6 style, original animations"
        val modernButton = RadioButton(this)
        modernButton.id = 101
        modernButton.text = "Modern: 3D spins and flips"
        group.addView(nostalgicButton)
        group.addView(modernButton)
        group.check(if (Dispatch.savedMode(this) == Proto.MODE_MODERN) 101 else 102)
        group.setOnCheckedChangeListener { _, checked ->
            Dispatch.saveMode(this, if (checked == 101) Proto.MODE_MODERN else Proto.MODE_NOSTALGIC)
        }
        root.addView(group)

        root.addView(button("Add shortcut to home screen") { pinShortcut() })
        root.addView(button("Check module") { check() })
        root.addView(button("Go home and start") { goHomeAndStart() })
        setContentView(root)

        val prefs = getSharedPreferences("state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pinned", false)) {
            prefs.edit().putBoolean("pinned", true).apply()
            window.decorView.post { pinShortcut() }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Proto.ACTION_PONG)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pong, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pong, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(pong)
    }

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { action() }
        return b
    }

    private fun check() {
        answered = false
        status.text = "Asking the launcher..."
        Dispatch.ping(this)
        handler.postDelayed({
            if (!answered) {
                status.text = "No answer. Is the module enabled in LSPosed with the launcher in scope? Force stop the launcher and try again."
            }
        }, 1500)
    }

    private fun goHomeAndStart() {
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        handler.postDelayed({ Dispatch.toggle(this) }, 700)
    }

    private fun pinShortcut() {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Your launcher does not support pinned shortcuts", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, TriggerActivity::class.java)
        intent.action = Intent.ACTION_MAIN
        val info = ShortcutInfo.Builder(this, "harlem")
            .setShortLabel("Harlem Shake")
            .setLongLabel("Harlem Shake")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }
}
