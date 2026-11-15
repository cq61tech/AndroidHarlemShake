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
            val detail = if (n > 0) "Ana ekranda $n simge bulundu." else "Simge sayımı için ana ekrandayken tekrar dene."
            status.text = "Modül çalışıyor ($who). $detail"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this)
        status.textSize = 16f
        status.text = "LSPosed'da modülü etkinleştir, kapsamda Pixel Launcher'ı seç, sonra launcher'ı zorla durdur."
        root.addView(status)

        val group = RadioGroup(this)
        val nostalgicButton = RadioButton(this)
        nostalgicButton.id = 102
        nostalgicButton.text = "Nostalji: iOS 6 tarzı, her ikonun kendi dansı (2D)"
        val modernButton = RadioButton(this)
        modernButton.id = 101
        modernButton.text = "Modern: 3D dönüşler ve ters yüz olmalar"
        group.addView(nostalgicButton)
        group.addView(modernButton)
        group.check(if (Dispatch.savedMode(this) == Proto.MODE_MODERN) 101 else 102)
        group.setOnCheckedChangeListener { _, checked ->
            Dispatch.saveMode(this, if (checked == 101) Proto.MODE_MODERN else Proto.MODE_NOSTALGIC)
        }
        root.addView(group)

        root.addView(button("Ana ekrana kısayol ekle") { pinShortcut() })
        root.addView(button("Modülü kontrol et") { check() })
        root.addView(button("Ana ekrana dön ve başlat") { goHomeAndStart() })
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
        status.text = "Launcher'a soruluyor..."
        Dispatch.ping(this)
        handler.postDelayed({
            if (!answered) {
                status.text = "Cevap yok. LSPosed'da modül açık mı, kapsamda launcher seçili mi? Launcher'ı zorla durdurup tekrar dene."
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
            Toast.makeText(this, "Launcher kısayol eklemeyi desteklemiyor", Toast.LENGTH_LONG).show()
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
