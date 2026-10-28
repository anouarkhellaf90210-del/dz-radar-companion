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
    val storeLogoUrl: String,
    val storeUrl: String,
    val isMerchantMode: Boolean = false,
    val activeOrderId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val nextActionLabel: String = "",
    val newCount: Int = 0,
    val preparingCount: Int = 0,
    val shippingCount: Int = 0,
    val deliveredCount: Int = 0,
    val isPickup: Boolean = false
)

object RadarApiClient {
    @Volatile var baseOrigin: String = "https://my-private-store-app.vercel.app"
    @Volatile var latestStoreUrl: String = "https://my-private-store-app.vercel.app/s/dzball"

    fun updateOriginFromStoreUrl(url: String) {
        if (url.startsWith("http")) {
            try {
                val parsed = URL(url)
                val portPart = if (parsed.port != -1 && parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""
                baseOrigin = "${parsed.protocol}://${parsed.host}$portPart"
                if (url.contains("/s/")) {
                    latestStoreUrl = url.split("?")[0]
                }
            } catch (_: Exception) {}
        }
    }

    fun fetchOrder(token: String): RadarOrderState? {
        val cleanToken = if (token.isBlank() || token == "SENTINEL-IDLE" || token == "DEMO-TRACK-DZ") "latest" else token.trim()
        return try {
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

                var sName = orderObj.optString("store_name", root.optString("store_name", "")).trim()
                if (sName.isBlank() || sName.equals("Dzball", ignoreCase = true) || sName.equals("Marik", ignoreCase = true)) {
                    sName = "Patron"
                }

                val sLogo = orderObj.optString("store_logo", root.optString("store_logo", "")).trim()
                val resolvedUrl = "$baseOrigin/s/$slug"
                latestStoreUrl = resolvedUrl

                val serverStation = orderObj.optInt("station", root.optInt("station", 0))
                val station = if (serverStation in 1..4) {
                    serverStation
                } else {
                    when (rawStatus) {
                        "delivered", "completed", "received", "paid" -> 4
                        "out_for_delivery", "shipped", "in_transit", "dispatched", "with_courier", "delivering" -> 3
                        "confirmed", "processing", "preparing", "packaged", "ready_for_dispatch", "ready_to_ship", "assigned" -> 2
                        else -> 1
                    }
                }

                val isPickup = orderObj.optBoolean("is_pickup", root.optBoolean("is_pickup", false)) ||
                    orderObj.optString("delivery_type", "").lowercase() == "pickup" ||
                    orderObj.optString("wilaya", "").contains("استلام") ||
                    orderObj.optString("shipping_address", "").contains("استلام") ||
                    orderObj.optString("notes", "").contains("PICKUP")

                val serverTitle = orderObj.optString("status_title", root.optString("status_title", "")).trim()
                val serverSubtitle = orderObj.optString("status_subtitle", root.optString("status_subtitle", "")).trim()

                val (title, subtitle) = if (serverTitle.isNotEmpty()) {
                    serverTitle to serverSubtitle
                } else if (isPickup) {
                    when (station) {
                        4 -> "تم الاستلام من المحل 🤝" to "تشرفت بزيارتك للمحل، تتهنى بسلعتك!"
                        3 -> "سلعتك: جاهزة بالمحل 🏪" to "سلعتك واجدة في المحل! تفضل بالحضور لاستلامها متى شئت"
                        2 -> "سلعتك: قيد التجهيز بالمحل 📦" to "يقوم التاجر الآن بتحضير سلعتك لتكون جاهزة عند حضورك"
                        else -> "طلبيتك: قيد التأكيد ⏳" to "سيتصل بك التاجر قريباً لتأكيد حضورك للمحل"
                    }
                } else {
                    when (station) {
                        4 -> "طردك: تم التسليم 🤝" to "وصل طردك بأمان، تتهنى به!"
                        3 -> "طردك: في الطريق إليك 🚚" to "طردك الآن مع الموزع متجه إليك"
                        2 -> "طردك: قيد التجهيز 📦" to "يجري فرز وتغليف طردك بمستودعنا"
                        else -> "طردك: مُستلم ⚡" to "تم تسجيل وتثبيت طلبيتك بنجاح"
                    }
                }

                RadarOrderState(
                    trackingToken = resolvedToken,
                    station = station,
                    statusTitle = title,
                    statusSubtitle = subtitle,
                    storeName = "متجر $sName",
                    storeLogoUrl = sLogo,
                    storeUrl = resolvedUrl,
                    isMerchantMode = false,
                    isPickup = isPickup
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun fetchMerchantState(slug: String, advanceOrderId: String = ""): RadarOrderState? {
        val cleanSlug = slug.trim().ifBlank { "latest" }
        return try {
            val encodedSlug = URLEncoder.encode(cleanSlug, "UTF-8")
            val actionPart = if (advanceOrderId.isNotBlank()) {
                "&action=advance&order_id=${URLEncoder.encode(advanceOrderId.trim(), "UTF-8")}"
            } else ""
            val endpoint = "$baseOrigin/api/merchant/companion?slug=$encodedSlug$actionPart&_cb=${System.currentTimeMillis()}"
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
                val sName = root.optString("store_name", "متجري").trim().ifBlank { "متجري" }
                val sLogo = root.optString("store_logo", "").trim()
                val sSlug = root.optString("store_slug", cleanSlug).trim().ifBlank { "dzball" }
                val newCnt = root.optInt("new_count", 0)
                val prepCnt = root.optInt("preparing_count", 0)
                val shipCnt = root.optInt("shipping_count", 0)
                val delivCnt = root.optInt("delivered_count", 0)
                val todayRev = root.optInt("today_revenue", 0)

                val activeObj = root.optJSONObject("active_order")
                val ordId = activeObj?.optString("id", "") ?: ""
                val station = activeObj?.optInt("station", 1) ?: 1
                val custName = activeObj?.optString("customer_name", "") ?: ""
                val custPhone = activeObj?.optString("customer_phone", "") ?: ""
                val wilaya = activeObj?.optString("wilaya", "") ?: ""
                val totalDzd = activeObj?.optInt("total", 0) ?: 0
                val itemsStr = activeObj?.optString("items_summary", "") ?: ""
                val nextLabel = activeObj?.optString("next_action_label", "⚡ قبول وتأكيد الطلب فوراً") ?: ""

                val title = if (ordId.isNotBlank()) {
                    "👤 $custName ($wilaya) • ${totalDzd} د.ج"
                } else {
                    "رادار التاجر نشط 🟢 (بانتظار الطلبات)"
                }

                val subtitle = if (ordId.isNotBlank()) {
                    "🛍️ $itemsStr | مبيعات اليوم: ${todayRev} د.ج"
                } else {
                    "مبيعات اليوم: ${todayRev} د.ج | ستنبثق الكبسولة تلقائياً فور وصول أي طلب!"
                }

                val merchantDashboardUrl = "$baseOrigin/merchant?store=$sSlug"

                RadarOrderState(
                    trackingToken = ordId.ifBlank { "MERCHANT-IDLE" },
                    station = station,
                    statusTitle = title,
                    statusSubtitle = subtitle,
                    storeName = "👑 $sName",
                    storeLogoUrl = sLogo,
                    storeUrl = merchantDashboardUrl,
                    isMerchantMode = true,
                    activeOrderId = ordId,
                    customerName = custName,
                    customerPhone = custPhone,
                    nextActionLabel = nextLabel,
                    newCount = newCnt,
                    preparingCount = prepCnt,
                    shippingCount = shipCnt,
                    deliveredCount = delivCnt
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
