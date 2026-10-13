package com.dzradar.companion

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RadarOrderData(
    val station: Int,
    val status: String,
    val wilaya: String,
    val commune: String
)

object RadarApiClient {
    private var lastModifiedHeader: String? = null

    fun fetchOrder(token: String): RadarOrderData? {
        if (token.isBlank()) return null
        return try {
            val endpoint = "https://my-private-store-app.vercel.app/api/orders/track/$token"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            lastModifiedHeader?.let {
                conn.setRequestProperty("If-Modified-Since", it)
            }

            if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                lastModifiedHeader = conn.getHeaderField("Last-Modified")
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val order = json.optJSONObject("order") ?: return null
                val status = order.optString("status", "new")
                val wilaya = order.optString("wilaya", "")
                val commune = order.optString("commune", "")

                val station = when (status.lowercase()) {
                    "delivered", "completed", "received", "paid" -> 4
                    "out_for_delivery", "shipped", "in_transit", "dispatched", "with_courier", "delivering" -> 3
                    "confirmed", "processing", "preparing", "packaged", "ready_for_dispatch", "ready_to_ship", "assigned" -> 2
                    else -> 1
                }

                RadarOrderData(station, status, wilaya, commune)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
