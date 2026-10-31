package com.vegspro.aroundrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start", false)) {
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
