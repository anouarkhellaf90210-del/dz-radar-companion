package com.dzradar.companion

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RadarOrderState(
    val station: Int,
    val statusLabel: String,
    val storeName: String,
    val storeUrl: String
)

object RadarApiClient {
    private const val BASE_URL = "https://my-private-store-app.vercel.app"
    @Volatile var latestStoreUrl: String = "$BASE_URL/s/dzball"

    fun fetchOrder(token: String): RadarOrderState? {
        val cleanToken = if (token.isBlank()) "DEMO-TRACK-DZ" else token.trim()
        return try {
            val url = URL("$BASE_URL/api/orders/track/$cleanToken?_cb=${System.currentTimeMillis()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val root = JSONObject(response)
                val orderObj = root.optJSONObject("order") ?: root
                val rawStatus = orderObj.optString("status", "pending").lowercase().trim()
                val slug = orderObj.optString("store_slug", root.optString("store_slug", "dzball")).ifBlank { "dzball" }
                val sName = orderObj.optString("store_name", root.optString("store_name", "تتبع مباشر من المتجر")).ifBlank { "تتبع مباشر من المتجر" }

                val resolvedUrl = "$BASE_URL/s/$slug"
                latestStoreUrl = resolvedUrl

                val (station, label) = when (rawStatus) {
                    "delivered", "completed", "received", "paid" -> 4 to "طردك: تم التسليم 🤝"
                    "out_for_delivery", "shipped", "in_transit", "dispatched", "with_courier", "delivering" -> 3 to "طردك: في الطريق إليك 🚚"
                    "confirmed", "processing", "preparing", "packaged", "ready_for_dispatch", "ready_to_ship", "assigned" -> 2 to "طردك: قيد التجهيز 📦"
                    else -> 1 to "طردك: مُستلم ⚡"
                }

                RadarOrderState(station, label, sName, resolvedUrl)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
