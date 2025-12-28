package com.mph070770.sendspinandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // We use equals() to avoid a NullPointerException
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Load saved settings from SharedPreferences
            val prefs = context.getSharedPreferences("SendspinPlayerPrefs", Context.MODE_PRIVATE)
            
            // Check if auto-start on boot is enabled (default: false for safety)
            val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false)
            if (!autoStartEnabled) {
                return
            }
            
            val wsUrl = prefs.getString("ws_url", "ws://192.168.1.137:8927/sendspin") ?: "ws://192.168.1.137:8927/sendspin"
            val clientName = prefs.getString("client_name", "Android Player") ?: "Android Player"
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-player"

            val serviceIntent = Intent(context, SendspinService::class.java).apply {
                putExtra("wsUrl", wsUrl)
                putExtra("clientId", deviceId)
                putExtra("clientName", clientName)
            }

            // Check Android version to decide how to start the service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
