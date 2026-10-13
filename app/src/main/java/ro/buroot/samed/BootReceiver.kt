package ro.buroot.samed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts protection automatically after a reboot, but only if the user had
 * it enabled before. The preference is written by MainActivity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("samed", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enabled", false)) {
            ShieldService.start(context)
        }
    }
}
