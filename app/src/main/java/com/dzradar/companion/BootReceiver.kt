package com.dzradar.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
            if (!canOverlay) return

            val prefs = context.getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString("radar_mode", "customer") ?: "customer"
            val slug = prefs.getString("merchant_slug", "latest") ?: "latest"
            val token = prefs.getString("last_token", "latest") ?: "latest"
            val storeUrl = prefs.getString("last_store_url", "https://my-private-store-app.vercel.app/s/dzball")
                ?: "https://my-private-store-app.vercel.app/s/dzball"

            val srvIntent = Intent(context, FloatingBubbleService::class.java).apply {
                putExtra("mode", mode)
                putExtra("merchant_slug", slug)
                putExtra("token", token)
                putExtra("store_url", storeUrl)
                putExtra("expand", false)
                putExtra("boot_standby", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(srvIntent)
            } else {
                context.startService(srvIntent)
            }
        } catch (_: Exception) {}
    }
}
