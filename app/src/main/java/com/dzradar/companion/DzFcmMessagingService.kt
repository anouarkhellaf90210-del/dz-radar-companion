package com.dzradar.companion

import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DzFcmMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerDeviceToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            val data = message.data
            val role = data["role"] ?: "customer"
            val trackingToken = data["tracking_token"] ?: ""
            val storeSlug = data["store_slug"] ?: "latest"

            val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            if (trackingToken.isNotBlank()) {
                prefs.edit().putString("last_token", trackingToken).putBoolean("is_order_terminal", false).apply()
            }
            val srvIntent = Intent(this, FloatingBubbleService::class.java).apply {
                putExtra("mode", if (role == "merchant") "merchant" else "customer")
                if (trackingToken.isNotBlank()) putExtra("token", trackingToken)
                if (storeSlug.isNotBlank()) putExtra("merchant_slug", storeSlug)
                putExtra("expand", true)
                putExtra("fcm_station", data["station"]?.toIntOrNull() ?: 0)
                putExtra("fcm_status_title", data["status_title"] ?: "")
                putExtra("fcm_status_subtitle", data["status_subtitle"] ?: "")
                putExtra("fcm_store_name", data["store_name"] ?: "")
                putExtra("fcm_store_slug", data["store_slug"] ?: "")
                putExtra("fcm_is_pickup", data["is_pickup"] == "1")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(srvIntent)
            } else {
                startService(srvIntent)
            }
        } catch (_: Exception) {}
    }

    companion object {
        fun syncCurrentToken(context: Context) {
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { tok ->
                    if (!tok.isNullOrBlank()) registerDeviceToken(context, tok)
                }
            } catch (_: Exception) {}
        }

        fun registerDeviceToken(context: Context, fcmToken: String) {
            Thread {
                try {
                    val prefs = context.getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
                    val mode = prefs.getString("radar_mode", "customer") ?: "customer"
                    val slug = prefs.getString("merchant_slug", "latest") ?: "latest"
                    val trackTok = prefs.getString("last_token", "") ?: ""
                    val payload = JSONObject().apply {
                        put("device_token", fcmToken)
                        put("role", if (mode == "merchant") "merchant" else "customer")
                        put("store_slug", slug)
                        put("tracking_token", trackTok)
                    }
                    val conn = (URL("https://elitemarketdz.top/api/push/fcm-token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }.start()
        }
    }
}
