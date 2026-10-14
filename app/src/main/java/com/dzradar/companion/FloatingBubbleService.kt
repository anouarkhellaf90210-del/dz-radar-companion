package com.dzradar.companion

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.hypot

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: RadarOverlayView? = null
    private var radarMode: String = "customer"
    private var merchantSlug: String = "latest"
    private var trackingToken: String = "latest"
    private var lastResolvedOrderToken: String = ""
    private var lastMerchantNewCount: Int = -1
    private var targetStoreUrl: String = ""
    private var lastKnownStation: Int = 0
    private var localServer: ServerSocket? = null
    @Volatile private var isRunning = true

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true

    private val autoCollapseRunnable = Runnable {
        if (radarMode != "merchant") {
            overlayView?.toggleCard(false)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                isScreenOn = true
                triggerFetch()
            }
        }
    }

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                triggerFetch()
            }
            handler.postDelayed(this, 3500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)

        val incomingMode = intent?.getStringExtra("mode")
        if (!incomingMode.isNullOrBlank()) {
            radarMode = incomingMode.trim()
            prefs.edit().putString("radar_mode", radarMode).apply()
        } else {
            radarMode = prefs.getString("radar_mode", "customer") ?: "customer"
        }

        val incomingMerchantSlug = intent?.getStringExtra("merchant_slug")
        if (!incomingMerchantSlug.isNullOrBlank()) {
            merchantSlug = incomingMerchantSlug.trim()
            prefs.edit().putString("merchant_slug", merchantSlug).apply()
        } else {
            merchantSlug = prefs.getString("merchant_slug", "latest") ?: "latest"
        }

        val incomingToken = intent?.getStringExtra("token")
        if (!incomingToken.isNullOrBlank() && incomingToken != "SENTINEL-IDLE" && incomingToken != "DEMO-TRACK-DZ") {
            trackingToken = incomingToken.trim()
            prefs.edit().putString("last_token", trackingToken).apply()
        } else {
            val saved = prefs.getString("last_token", "latest") ?: "latest"
            trackingToken = if (saved == "SENTINEL-IDLE" || saved == "DEMO-TRACK-DZ") "latest" else saved
        }

        val incomingUrl = intent?.getStringExtra("store_url")
        if (!incomingUrl.isNullOrBlank()) {
            targetStoreUrl = incomingUrl.trim()
            RadarApiClient.updateOriginFromStoreUrl(targetStoreUrl)
            prefs.edit().putString("last_store_url", targetStoreUrl).apply()
        } else if (targetStoreUrl.isBlank()) {
            targetStoreUrl = prefs.getString("last_store_url", "") ?: ""
            if (targetStoreUrl.isNotBlank()) {
                RadarApiClient.updateOriginFromStoreUrl(targetStoreUrl)
            }
        }

        val shouldExpand = intent?.getBooleanExtra("expand", false) ?: false
        if (shouldExpand) {
            handler.post {
                overlayView?.visibility = View.VISIBLE
                playStationTransitionAlert()
                overlayView?.toggleCard(true)
                handler.removeCallbacks(autoCollapseRunnable)
                if (radarMode != "merchant") {
                    handler.postDelayed(autoCollapseRunnable, 6000)
                }
            }
        }

        triggerFetch(forceExpandOnFirstLoad = shouldExpand)
        return START_STICKY
    }

    private fun resolveDestinationUrl(): String {
        if (radarMode == "merchant") {
            return "${RadarApiClient.baseOrigin}/merchant?store=${URLEncoder.encode(merchantSlug, "UTF-8")}"
        }
        val base = when {
            targetStoreUrl.isNotBlank() && targetStoreUrl.startsWith("http") && targetStoreUrl.contains("/s/") -> targetStoreUrl
            else -> {
                val savedUrl = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
                    .getString("last_store_url", "") ?: ""
                if (savedUrl.isNotBlank() && savedUrl.startsWith("http") && savedUrl.contains("/s/")) {
                    savedUrl
                } else {
                    RadarApiClient.latestStoreUrl
                }
            }
        }
        val cleanBase = base.split("?")[0]
        return "$cleanBase?native_bubble=1"
    }

    private fun playMicroTapHaptic() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        } catch (_: Exception) {}
    }

    private fun playStationTransitionAlert() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 60, 140), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200)
            }
        } catch (_: Exception) {}

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 240)
            handler.postDelayed({
                try { toneGen.release() } catch (_: Exception) {}
            }, 300)
        } catch (_: Exception) {}
    }

    private fun startLocalLoopbackBridge() {
        Thread {
            try {
                localServer = ServerSocket(49210, 10, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val client = localServer?.accept() ?: break
                    Thread {
                        try {
                            client.soTimeout = 2000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val reqLine = reader.readLine() ?: ""

                            var headerLine = reader.readLine()
                            while (!headerLine.isNullOrEmpty()) {
                                headerLine = reader.readLine()
                            }

                            val isOptions = reqLine.startsWith("OPTIONS ")

                            if (!isOptions && reqLine.contains("/sync")) {
                                val qStart = reqLine.indexOf("?")
                                val qEnd = reqLine.indexOf(" HTTP/")
                                if (qStart != -1 && qEnd > qStart) {
                                    val query = reqLine.substring(qStart + 1, qEnd)
                                    val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()
                                    for (pair in query.split("&")) {
                                        val kv = pair.split("=")
                                        if (kv.size == 2) {
                                            val k = URLDecoder.decode(kv[0], "UTF-8")
                                            val v = URLDecoder.decode(kv[1], "UTF-8")
                                            if (k == "mode" && v.isNotBlank()) {
                                                radarMode = v.trim()
                                                prefs.putString("radar_mode", radarMode)
                                            } else if (k == "slug" && v.isNotBlank()) {
                                                merchantSlug = v.trim()
                                                prefs.putString("merchant_slug", merchantSlug)
                                            } else if (k == "token" && v.isNotBlank() && v != "ACTIVE-ORDER" && v != "DEMO-TRACK-DZ" && v != "SENTINEL-IDLE") {
                                                trackingToken = v.trim()
                                                prefs.putString("last_token", trackingToken)
                                            } else if (k == "store_url" && v.startsWith("http")) {
                                                targetStoreUrl = v.trim()
                                                RadarApiClient.updateOriginFromStoreUrl(targetStoreUrl)
                                                prefs.putString("last_store_url", targetStoreUrl)
                                            }
                                        }
                                    }
                                    prefs.apply()
                                    triggerFetch()
                                }
                            }

                            val writer = OutputStreamWriter(client.getOutputStream(), "UTF-8")
                            val body = if (isOptions) "" else "{\"active\":true,\"version\":\"1.2.0\",\"mode\":\"$radarMode\",\"token\":\"$trackingToken\",\"station\":$lastKnownStation}"
                            val bodyBytes = body.toByteArray(Charsets.UTF_8)
                            writer.write(if (isOptions) "HTTP/1.1 204 No Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            writer.write("Content-Type: application/json; charset=utf-8\r\n")
                            writer.write("Access-Control-Allow-Origin: *\r\n")
                            writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                            writer.write("Access-Control-Allow-Headers: *\r\n")
                            writer.write("Access-Control-Allow-Private-Network: true\r\n")
                            writer.write("Content-Length: ${bodyBytes.size}\r\n")
                            writer.write("Connection: close\r\n\r\n")
                            if (!isOptions) {
                                writer.write(body)
                            }
                            writer.flush()
                            client.close()
                        } catch (_: Exception) {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        val tapSlopPx = 5f * density

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        overlayView = RadarOverlayView(
            this,
            onOpenStore = {
                overlayView?.toggleCard(false)
                val destUrl = resolveDestinationUrl()
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(destUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            },
            onKillCompanion = {
                stopSelf()
            },
            onHideOverlay = {
                overlayView?.toggleCard(false)
                overlayView?.visibility = View.GONE
            },
            onAdvanceOrder = { orderId ->
                playMicroTapHaptic()
                Thread {
                    val updated = RadarApiClient.fetchMerchantState(merchantSlug, advanceOrderId = orderId)
                    if (updated != null) {
                        lastKnownStation = updated.station
                        lastMerchantNewCount = updated.newCount
                        overlayView?.updateData(updated)
                        playStationTransitionAlert()
                    }
                }.start()
            },
            onCallCustomer = { phone ->
                overlayView?.toggleCard(false)
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.trim()}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dialIntent)
                } catch (_: Exception) {}
            },
            onWhatsAppCustomer = { phone, custName, storeName ->
                overlayView?.toggleCard(false)
                try {
                    val digits = phone.replace(Regex("\\D"), "")
                    val intlPhone = when {
                        digits.startsWith("0") -> "213" + digits.substring(1)
                        !digits.startsWith("213") && digits.length == 9 -> "213" + digits
                        else -> digits
                    }
                    val msg = "السلام عليكم $custName، معك متجر $storeName لتأكيد طلبيتك وتجهيزها للشحن. شكراً لثقتك بنا! ✨"
                    val waUrl = "https://wa.me/$intlPhone?text=${URLEncoder.encode(msg, "UTF-8")}"
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(waIntent)
                } catch (_: Exception) {}
            }
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.bubbleContainer?.setOnTouchListener { _, event ->
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(autoCollapseRunnable)
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    triggerFetch()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (hypot(dx.toDouble(), dy.toDouble()) >= tapSlopPx) {
                        val newX = initialX + dx.toInt()
                        val newY = initialY + dy.toInt()

                        val maxX = (screenWidth - sizePx).coerceAtLeast(0)
                        val maxY = (screenHeight - sizePx).coerceAtLeast(0)

                        params.x = newX.coerceIn(0, maxX)
                        params.y = newY.coerceIn(0, maxY)

                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (hypot(dx.toDouble(), dy.toDouble()) < tapSlopPx) {
                        playMicroTapHaptic()
                        val isOpen = overlayView?.isCardOpen() ?: false
                        overlayView?.toggleCard(!isOpen)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(overlayView, params)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        startLocalLoopbackBridge()
        handler.post(pollingRunnable)
    }

    private fun triggerFetch(forceExpandOnFirstLoad: Boolean = false) {
        Thread {
            if (radarMode == "merchant") {
                val data = RadarApiClient.fetchMerchantState(merchantSlug)
                if (data != null) {
                    val isNewOrderArrived =
                        (lastResolvedOrderToken.isNotBlank() && lastResolvedOrderToken != data.trackingToken && data.activeOrderId.isNotBlank()) ||
                        (lastMerchantNewCount >= 0 && data.newCount > lastMerchantNewCount)

                    lastResolvedOrderToken = data.trackingToken
                    lastMerchantNewCount = data.newCount
                    lastKnownStation = data.station

                    if (isNewOrderArrived || forceExpandOnFirstLoad) {
                        handler.post {
                            overlayView?.visibility = View.VISIBLE
                            playStationTransitionAlert()
                            overlayView?.toggleCard(true)
                        }
                    }
                    overlayView?.updateData(data)
                }
                return@Thread
            }

            val data = RadarApiClient.fetchOrder("latest") ?: RadarApiClient.fetchOrder(trackingToken)
            if (data != null) {
                val isNewOrder = lastResolvedOrderToken.isNotBlank() && lastResolvedOrderToken != data.trackingToken
                val isStationChanged = lastKnownStation != 0 && lastKnownStation != data.station

                if (data.trackingToken.isNotBlank()) {
                    trackingToken = data.trackingToken
                    lastResolvedOrderToken = data.trackingToken
                }

                if (isNewOrder || isStationChanged || forceExpandOnFirstLoad) {
                    handler.post {
                        overlayView?.visibility = View.VISIBLE
                        playStationTransitionAlert()
                        overlayView?.toggleCard(true)
                        handler.removeCallbacks(autoCollapseRunnable)
                        handler.postDelayed(autoCollapseRunnable, 6000)
                    }
                }
                lastKnownStation = data.station
                overlayView?.updateData(data)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { localServer?.close() } catch (_: Exception) {}
        handler.removeCallbacks(pollingRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
    }
}
