package dev.harlem.shake

import android.content.Context
import android.content.Intent

object Dispatch {
    fun savedMode(context: Context): Int {
        val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        return prefs.getInt("mode", Proto.MODE_NOSTALGIC)
    }

    fun saveMode(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        prefs.edit().putInt("mode", mode).apply()
    }

    fun toggle(context: Context) {
        val mode = savedMode(context)
        send(context, Proto.ACTION_TOGGLE, mode)
    }

    fun ping(context: Context) {
        send(context, Proto.ACTION_PING, 0)
    }

    private fun send(context: Context, action: String, mode: Int) {
        for (pkg in Proto.LAUNCHERS) {
            val intent = Intent(action)
            intent.setPackage(pkg)
            intent.putExtra(Proto.EXTRA_MODE, mode)
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        }
    }
}
