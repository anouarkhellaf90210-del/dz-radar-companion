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
    val statusTitle: String,
    val statusSubtitle: String,
    val storeName: String,
    val storeUrl: String
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

    fun fetchOrder(token: String): RadarOrderState? {
        if (token.isBlank() || token == "SENTINEL-IDLE") return null
        return try {
            val cleanToken = token.trim()
            val encodedToken = URLEncoder.encode(cleanToken, "UTF-8")
            val endpoint = "$baseOrigin/api/orders/track/$encodedToken?_cb=${System.currentTimeMillis()}"
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
                val sName = orderObj.optString("store_name", root.optString("store_name", "متجر $slug")).ifBlank { "متجر Dzball" }

                val resolvedUrl = "$baseOrigin/s/$slug"
                latestStoreUrl = resolvedUrl

                val station = when (rawStatus) {
                    "delivered", "completed", "received", "paid" -> 4
                    "out_for_delivery", "shipped", "in_transit", "dispatched", "with_courier", "delivering" -> 3
                    "confirmed", "processing", "preparing", "packaged", "ready_for_dispatch", "ready_to_ship", "assigned" -> 2
                    else -> 1
                }

                val (title, subtitle) = when (station) {
                    4 -> "تم التسليم بنجاح" to "وصل طردك بأمان، تتهنى به!"
                    3 -> "خرج للتوصيل" to "طردك الآن في مركبة التوصيل متجه إليك"
                    2 -> "تم التأكيد والتجهيز" to "تم توثيق طلبك ويجري تغليفه بعناية"
                    else -> "قيد التأكيد والمراجعة" to "تم تسجيل طلبك وهو قيد المراجعة"
                }

                RadarOrderState(
                    trackingToken = resolvedToken,
                    station = station,
                    statusTitle = title,
                    statusSubtitle = subtitle,
                    storeName = sName,
                    storeUrl = resolvedUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
