package dev.harlem.shake

import android.app.Activity
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ModuleEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!Proto.LAUNCHERS.contains(lpparam.packageName)) return
        if (lpparam.processName != lpparam.packageName) return
        XposedBridge.log("HarlemShake: hooking " + lpparam.packageName)
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    Controller.attach(activity)
                }
            }
        )
    }
}
