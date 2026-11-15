package dev.harlem.shake

import android.content.Context
import android.content.Intent

object Dispatch {
    fun toggle(context: Context) {
        send(context, Proto.ACTION_TOGGLE)
    }

    fun ping(context: Context) {
        send(context, Proto.ACTION_PING)
    }

    private fun send(context: Context, action: String) {
        for (pkg in Proto.LAUNCHERS) {
            val intent = Intent(action)
            intent.setPackage(pkg)
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
        }
    }
}
