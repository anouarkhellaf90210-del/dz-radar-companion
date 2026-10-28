package com.dzradar.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private var lastKnownStatusTitle: String = ""
    @Volatile private var isOverlayInStandby: Boolean = false
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var savedBubbleX: Int = 24
    private var savedBubbleY: Int = 320
    private var localServer: ServerSocket? = null
    @Volatile private var isRunning = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var backgroundScheduler: ScheduledExecutorService? = null
    private val isFetching = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isScreenOn = true

    private val autoCollapseRunnable = Runnable {
        if (radarMode != "merchant" && !isOverlayInStandby) {
            overlayView?.toggleCard(false)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
            } else if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                isScreenOn = true
                triggerFetch()
            }
        }
    }

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                triggerFetch()
                handler.postDelayed(this, 3000)
            }
        }
    }

    
    private fun wakeUpCustomerScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "dzradar:CustomerStationWakeup"
            )
            screenLock?.acquire(10000)
        } catch (_: Exception) {}
    }

    private fun fireCustomerOrderNotification(data: RadarOrderState) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val alertChannelId = "dz_radar_urgent_customer_alerts_v2"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(alertChannelId) == null) {
                    val chan = NotificationChannel(
                        alertChannelId,
                        "تنبيهات حالة طلبيتك الفورية 📦",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "إشعارات صوتية وبصرية فورية فوق شاشة القفل عند تغير محطة طلبيتك"
                        enableLights(true)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 250, 120, 300)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                this.data = Uri.parse("dzradar://track?expand=1&token=" + URLEncoder.encode(data.trackingToken, "UTF-8"))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000).toInt(),
                openIntent,
                piFlags
            )

            val titleText = data.storeName + " • " + data.statusTitle
            val bodyText = data.statusSubtitle.ifBlank { "اضغط هنا لمتابعة حالة طلبيتك مباشرة" }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, alertChannelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setDefaults(Notification.DEFAULT_ALL)
            }

            builder.setContentTitle(titleText)
                .setContentText(bodyText)
                .setStyle(Notification.BigTextStyle().bigText(bodyText))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_CALL)
                builder.setVisibility(Notification.VISIBILITY_PUBLIC)
            }

            nm.notify((System.currentTimeMillis() % 100000).toInt() + 60000, builder.build())
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForegroundGuardian() {
        try {
            val channelId = "dz_radar_guardian_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.getNotificationChannel(channelId) == null) {
                    val chan = NotificationChannel(
                        channelId,
                        "حارس رادار الطرد الحي",
                        NotificationManager.IMPORTANCE_MIN
                    ).apply {
                        description = "يبقي رادار تتبع الطرد متصلاً في الخلفية ليرصد تحديثات التاجر فوراً"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(chan)
                }
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notification = builder
                .setContentTitle("رادار الطرد نشط بالخلفية")
                .setContentText("يرصد تحديثات حالة طلبيتك لحظة بلحظة")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    49210,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    49210,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(49210, notification)
            }
        } catch (_: Exception) {
            try {
                val fallback = @Suppress("DEPRECATION") Notification.Builder(this)
                    .setContentTitle("رادار الطرد")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
                startForeground(49210, fallback)
            } catch (_: Exception) {}
        }
    }

    private fun acquireImmortalWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dzradar:SentinelWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun enterStandbyMode() {
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
        isOverlayInStandby = true
        prefs.edit().putBoolean("is_overlay_in_standby", true).apply()
        handler.removeCallbacks(autoCollapseRunnable)

        handler.post {
            try {
                overlayView?.toggleCard(false)
                overlayView?.setStandbyMode(true)
                overlayView?.visibility = View.VISIBLE
                windowLayoutParams?.let { lp ->
                    if (lp.width != 1) {
                        savedBubbleX = lp.x
                        savedBubbleY = lp.y
                    }
                    lp.width = 1
                    lp.height = 1
                    lp.alpha = 0.01f
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun exitStandbyAndExpand(data: RadarOrderState? = null) {
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
        isOverlayInStandby = false
        prefs.edit().putBoolean("is_overlay_in_standby", false).apply()

        handler.post {
            try {
                windowLayoutParams?.let { lp ->
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.alpha = 1.0f
                    @Suppress("DEPRECATION")
                    lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    lp.x = savedBubbleX
                    lp.y = savedBubbleY
                    try {
                        windowManager?.updateViewLayout(overlayView, lp)
                    } catch (_: Exception) {}
                }
                overlayView?.visibility = View.VISIBLE
                overlayView?.setStandbyMode(false)
                wakeUpCustomerScreen()
                if (data != null) {
                    overlayView?.updateData(data)
                    fireCustomerOrderNotification(data)
                }
                overlayView?.requestLayout()
                playStationTransitionAlert()
                overlayView?.toggleCard(true)
                handler.removeCallbacks(autoCollapseRunnable)
                if (radarMode != "merchant") {
                    handler.postDelayed(autoCollapseRunnable, 10000)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundGuardian()
        acquireImmortalWakeLock()

        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)

        if (lastKnownStation == 0) {
            lastKnownStation = prefs.getInt("last_known_station", 0)
        }
        if (lastKnownStatusTitle.isBlank()) {
            lastKnownStatusTitle = prefs.getString("last_known_status_title", "") ?: ""
        }
        if (lastResolvedOrderToken.isBlank()) {
            lastResolvedOrderToken = prefs.getString("last_resolved_token", "") ?: ""
        }

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
            val cleanIncoming = incomingToken.trim()
            if (cleanIncoming != "latest" && cleanIncoming != lastResolvedOrderToken && lastResolvedOrderToken.isNotBlank()) {
                lastKnownStation = 0
                lastKnownStatusTitle = ""
                prefs.edit().putInt("last_known_station", 0).putString("last_known_status_title", "").apply()
            }
            trackingToken = cleanIncoming
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

        val isBootStandby = intent?.getBooleanExtra("boot_standby", false) ?: false
        val shouldExpand = intent?.getBooleanExtra("expand", false) ?: false

        if (shouldExpand) {
            exitStandbyAndExpand()
        } else if (isBootStandby || prefs.getBoolean("is_overlay_in_standby", false)) {
            enterStandbyMode()
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
        val toneIdx = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).getInt("customer_tone_idx", 0)
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = when (toneIdx) {
                0 -> longArrayOf(0, 140, 80, 220)
                1 -> longArrayOf(0, 90, 50, 150)
                2 -> longArrayOf(0, 100, 60, 100, 60, 200)
                3 -> longArrayOf(0, 200, 100, 300)
                else -> longArrayOf(0, 280, 120, 380)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(300)
            }
        } catch (_: Exception) {}

        if (toneIdx == 4) return

        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            when (toneIdx) {
                0 -> {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 350) } catch (_: Exception) {}
                    }, 220)
                }
                1 -> {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 260) } catch (_: Exception) {}
                    }, 160)
                }
                2 -> {
                    toneGen.startTone(ToneGenerator.TONE_SUP_RADIO_ACK, 150)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 280) } catch (_: Exception) {}
                    }, 190)
                }
                3 -> {
                    toneGen.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 240)
                    handler.postDelayed({
                        try { toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 360) } catch (_: Exception) {}
                    }, 260)
                }
            }
            handler.postDelayed({
                try { toneGen.release() } catch (_: Exception) {}
            }, 700)
        } catch (_: Exception) {}
    }

    private fun startLocalLoopbackBridge() {
        Thread {
            try {
                localServer = ServerSocket(49210, 16, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val client = localServer?.accept() ?: break
                    Thread {
                        try {
                            client.soTimeout = 2500
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val reqLine = reader.readLine() ?: ""

                            var headerLine = reader.readLine()
                            while (!headerLine.isNullOrEmpty()) {
                                headerLine = reader.readLine()
                            }

                            val isOptions = reqLine.startsWith("OPTIONS ")
                            val isWakeup = reqLine.contains("/wakeup")
                            val isSync = reqLine.contains("/sync")

                            if (!isOptions && (isSync || isWakeup)) {
                                var forcePopup = isWakeup
                                var incomingStation = 0
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
                                            } else if (k == "token" && v.isNotBlank() && v != "ACTIVE-ORDER" && v != "DEMO-TRACK-DZ" && v != "SENTINEL-IDLE" && v != "latest") {
                                                trackingToken = v.trim()
                                                prefs.putString("last_token", trackingToken)
                                            } else if (k == "store_url" && v.startsWith("http")) {
                                                targetStoreUrl = v.trim()
                                                RadarApiClient.updateOriginFromStoreUrl(targetStoreUrl)
                                                prefs.putString("last_store_url", targetStoreUrl)
                                            } else if ((k == "expand" || k == "open") && (v == "1" || v == "true")) {
                                                forcePopup = true
                                            } else if (k == "station") {
                                                incomingStation = v.toIntOrNull() ?: 0
                                            }
                                        }
                                    }
                                    prefs.apply()
                                }

                                if (incomingStation in 1..4 && lastKnownStation != 0 && incomingStation != lastKnownStation) {
                                    forcePopup = true
                                }

                                if (forcePopup) {
                                    exitStandbyAndExpand()
                                }
                                triggerFetch(forceExpandOnFirstLoad = forcePopup)
                            }

                            val writer = OutputStreamWriter(client.getOutputStream(), "UTF-8")
                            val body = if (isOptions) "" else "{\"active\":true,\"version\":\"1.6.0\",\"mode\":\"$radarMode\",\"token\":\"$trackingToken\",\"station\":$lastKnownStation,\"standby\":$isOverlayInStandby}"
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
        ensureForegroundGuardian()
        acquireImmortalWakeLock()

        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
        lastKnownStation = prefs.getInt("last_known_station", 0)
        lastKnownStatusTitle = prefs.getString("last_known_status_title", "") ?: ""
        lastResolvedOrderToken = prefs.getString("last_resolved_token", "") ?: ""
        isOverlayInStandby = prefs.getBoolean("is_overlay_in_standby", false)

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
            x = savedBubbleX
            y = savedBubbleY
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
                getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("is_overlay_in_standby", false)
                    .apply()
                stopSelf()
            },
            onHideOverlay = {
                enterStandbyMode()
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
                        try {
                            val wakeConn = URL("http://127.0.0.1:49210/wakeup?event=status_changed&expand=1").openConnection() as HttpURLConnection
                            wakeConn.connectTimeout = 1000
                            wakeConn.readTimeout = 1000
                            wakeConn.responseCode
                            wakeConn.disconnect()
                        } catch (_: Exception) {}
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
                        savedBubbleX = params.x
                        savedBubbleY = params.y

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

        windowLayoutParams = params
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
                windowManager?.addView(overlayView, params)
                if (isOverlayInStandby) {
                    enterStandbyMode()
                }
            }
        } catch (_: Exception) {}

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)

        startLocalLoopbackBridge()
        handler.post(pollingRunnable)

        backgroundScheduler = Executors.newSingleThreadScheduledExecutor()
        backgroundScheduler?.scheduleAtFixedRate({
            try {
                if (isRunning) {
                    triggerFetch()
                }
            } catch (_: Exception) {}
        }, 2, 3, TimeUnit.SECONDS)
    }

    private fun triggerFetch(forceExpandOnFirstLoad: Boolean = false) {
        if (!forceExpandOnFirstLoad && !isFetching.compareAndSet(false, true)) {
            return
        }
        Thread {
            try {
                val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)

                if (radarMode == "merchant") {
                    val data = RadarApiClient.fetchMerchantState(merchantSlug)
                    if (data != null) {
                        val isNewOrderArrived =
                            (lastResolvedOrderToken.isNotBlank() && lastResolvedOrderToken != data.trackingToken && data.activeOrderId.isNotBlank()) ||
                            (lastMerchantNewCount >= 0 && data.newCount > lastMerchantNewCount)

                        lastResolvedOrderToken = data.trackingToken
                        lastMerchantNewCount = data.newCount
                        lastKnownStation = data.station

                        prefs.edit()
                            .putString("last_resolved_token", lastResolvedOrderToken)
                            .putInt("last_known_station", lastKnownStation)
                            .apply()

                        if (isNewOrderArrived || forceExpandOnFirstLoad) {
                            exitStandbyAndExpand(data)
                        } else {
                            overlayView?.updateData(data)
                        }
                    }
                    return@Thread
                }

                val tokenToFetch = when {
                    trackingToken.isNotBlank() && trackingToken != "latest" && trackingToken != "SENTINEL-IDLE" && trackingToken != "DEMO-TRACK-DZ" -> trackingToken
                    else -> {
                        val saved = prefs.getString("last_token", "latest") ?: "latest"
                        if (saved != "SENTINEL-IDLE" && saved != "DEMO-TRACK-DZ") saved else "latest"
                    }
                }

                val data = RadarApiClient.fetchOrder(tokenToFetch) ?: (if (tokenToFetch != "latest") RadarApiClient.fetchOrder("latest") else null)
                if (data != null) {
                    val savedStation = if (lastKnownStation != 0) lastKnownStation else prefs.getInt("last_known_station", 0)
                    val savedTitle = if (lastKnownStatusTitle.isNotBlank()) lastKnownStatusTitle else (prefs.getString("last_known_status_title", "") ?: "")
                    val savedResolvedToken = if (lastResolvedOrderToken.isNotBlank()) lastResolvedOrderToken else (prefs.getString("last_resolved_token", "") ?: "")
                    val inStandby = isOverlayInStandby || prefs.getBoolean("is_overlay_in_standby", false)

                    val isNewOrder = savedResolvedToken.isNotBlank() && savedResolvedToken != data.trackingToken
                    val isStationChanged = (savedStation != 0 && savedStation != data.station) || (inStandby && savedStation == 0 && data.station > 1)
                    val isStatusChanged = savedTitle.isNotBlank() && savedTitle != data.statusTitle
                    val isEmergenceNeeded = isNewOrder || isStationChanged || isStatusChanged || forceExpandOnFirstLoad

                    if (data.trackingToken.isNotBlank() && data.trackingToken != "latest") {
                        trackingToken = data.trackingToken
                        lastResolvedOrderToken = data.trackingToken
                    }

                    lastKnownStation = data.station
                    lastKnownStatusTitle = data.statusTitle

                    prefs.edit()
                        .putString("last_token", trackingToken)
                        .putString("last_resolved_token", lastResolvedOrderToken)
                        .putInt("last_known_station", lastKnownStation)
                        .putString("last_known_status_title", lastKnownStatusTitle)
                        .apply()

                    if (isEmergenceNeeded) {
                        exitStandbyAndExpand(data)
                    } else {
                        overlayView?.updateData(data)
                    }
                }
            } finally {
                isFetching.set(false)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { backgroundScheduler?.shutdownNow() } catch (_: Exception) {}
        try { localServer?.close() } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        handler.removeCallbacks(pollingRunnable)
        handler.removeCallbacks(autoCollapseRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}
