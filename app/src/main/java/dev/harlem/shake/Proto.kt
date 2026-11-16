package dev.harlem.shake

object Proto {
    const val APP_PACKAGE = "dev.harlem.shake"
    const val ACTION_TOGGLE = "dev.harlem.shake.TOGGLE"
    const val ACTION_PING = "dev.harlem.shake.PING"
    const val ACTION_PONG = "dev.harlem.shake.PONG"
    const val EXTRA_ICONS = "icons"
    const val EXTRA_PACKAGE = "pkg"
    const val EXTRA_MODE = "mode"
    const val MODE_MODERN = 0
    const val MODE_NOSTALGIC = 1
    const val DROP_MS = 15600L
    const val END_MS = 30000L
    const val SOLO_AMP = 1f
    val LAUNCHERS = arrayOf("com.google.android.apps.nexuslauncher", "com.android.launcher3")
}
