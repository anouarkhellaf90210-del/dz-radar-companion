package com.dzradar.companion

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RadarOrderState(
    val trackingToken: String,
    val station: Int,
    val statusRaw: String,
    val statusLabel: String,
    val storeName: String,
    val storeUrl: String,
    val customerName: String = "",
    val customerPhone: String = "",
    val wilaya: String = "",
    val totalPrice: String = ""
)

object RadarApiClient {
    @Volatile var baseOrigin: String = "https://my-private-store-app.vercel.app"
    @Volatile var latestStoreUrl: String = "https://my-private-store-app.vercel.app/s/dzball"

    fun updateOriginFromStoreUrl(url: String) {
        if (url.startsWith("http")) {
            try {
                val parsed = URL(url)
                baseOrigin = "${parsed.protocol}://${parsed.host}"
                if (url.contains("/s/")) {
                    latestStoreUrl = url.split("?")[0]
                }
            } catch (_: Exception) {}
        }
    }

    fun fetchOrder(token: String, action: String = ""): RadarOrderState? {
        val cleanToken = if (token.isBlank()) "DEMO-TRACK-DZ" else token.trim()
        return try {
            val encodedToken = URLEncoder.encode(cleanToken, "UTF-8")
            val actionParam = if (action.isNotBlank()) "&action=${URLEncoder.encode(action, "UTF-8")}" else ""
            val endpoint = "$baseOrigin/api/orders/track/$encodedToken?_cb=${System.currentTimeMillis()}$actionParam"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
                val orderObj = root.optJSONObject("order") ?: root.optJSONObject("data") ?: root

                val resolvedToken = orderObj.optString("tracking_token", root.optString("tracking_token", cleanToken)).ifBlank { cleanToken }
                val rawStatus = orderObj.optString("status", root.optString("status", "pending")).lowercase().trim()
                val slug = orderObj.optString("store_slug", root.optString("store_slug", "dzball")).ifBlank { "dzball" }
                val sName = orderObj.optString("store_name", root.optString("store_name", "متجر Dzball")).ifBlank { "متجر Dzball" }

                val cName = orderObj.optString("customer_name", orderObj.optString("full_name", root.optString("customer_name", "زبون المتجر")))
                val cPhone = orderObj.optString("customer_phone", orderObj.optString("phone", root.optString("customer_phone", "")))
                val cWilaya = orderObj.optString("wilaya", orderObj.optString("shipping_wilaya", root.optString("wilaya", "")))
                val cTotal = orderObj.optString("total_price", orderObj.optString("total_amount", root.optString("total_price", "")))

                val resolvedUrl = "$baseOrigin/s/$slug"
                latestStoreUrl = resolvedUrl

                val (station, label) = when (rawStatus) {
                    "delivered", "completed", "received", "paid" -> 4 to "طردك: تم التسليم بنجاح 🤝"
                    "out_for_delivery", "shipped", "in_transit", "dispatched", "with_courier", "delivering" -> 3 to "طردك: في الطريق إليك 🚚"
                    "confirmed", "processing", "preparing", "packaged", "ready_for_dispatch", "ready_to_ship", "assigned" -> 2 to "طردك: مؤكد وقيد التجهيز 📦"
                    else -> 1 to "طردك: مُستلم ⚡"
                }

                RadarOrderState(
                    trackingToken = resolvedToken,
                    station = station,
                    statusRaw = rawStatus,
                    statusLabel = label,
                    storeName = sName,
                    storeUrl = resolvedUrl,
                    customerName = cName,
                    customerPhone = cPhone,
                    wilaya = cWilaya,
                    totalPrice = cTotal
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
