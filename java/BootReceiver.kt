package com.my.remotewebcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("cam_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_boot", true)) {
                val serviceIntent = Intent(context, CameraStreamService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
