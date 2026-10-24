package com.dzradar.companion

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isWatchingPermission = false
    private var isInPipMode = false
    private lateinit var pipLiveStatusText: TextView

    private val permissionWatcher = object : Runnable {
        override fun run() {
            if (!isWatchingPermission) return
            if (hasOverlayPermission()) {
                isWatchingPermission = false
                Toast.makeText(this@MainActivity, "✅ تم تفعيل فقاعة الرادار بنجاح!", Toast.LENGTH_LONG).show()
                startBubbleService()
                finish()
            } else {
                handler.postDelayed(this, 600)
            }
        }
    }

    private val pipPollRunnable = object : Runnable {
        override fun run() {
            if (!isInPipMode) return
            Thread {
                val tok = getSavedToken()
                val data = RadarApiClient.fetchOrder(if (tok == "SENTINEL-IDLE") "latest" else tok)
                if (data != null) {
                    handler.post {
                        if (::pipLiveStatusText.isInitialized) {
                            pipLiveStatusText.text = "💠 ${data.storeName}\n${data.statusTitle}\n${data.statusSubtitle}"
                        }
                    }
                }
            }.start()
            handler.postDelayed(this, 3500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDeepLinkParams(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 102)
            } catch (_: Exception) {}
        }

        // نشغّل خدمة التتبع والرادار المحلي (127.0.0.1:49210) فوراً!
        startBubbleService()

        if (hasOverlayPermission()) {
            finish()
            return
        }

        buildInstantOfficialOnboardingUI()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            captureClipboardHandshakeIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        captureDeepLinkParams(intent)
        startBubbleService()
        if (hasOverlayPermission()) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasOverlayPermission()) {
            isWatchingPermission = false
            startBubbleService()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isWatchingPermission = false
        handler.removeCallbacks(permissionWatcher)
        handler.removeCallbacks(pipPollRunnable)
    }

    private fun captureClipboardHandshakeIfNeeded() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (clipText.startsWith("DZMERCHANT|")) {
                val parts = clipText.split("|")
                val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()
                prefs.putString("radar_mode", "merchant")
                if (parts.size >= 2 && parts[1].isNotBlank()) {
                    prefs.putString("merchant_slug", parts[1].trim())
                }
                if (parts.size >= 3 && parts[2].startsWith("http")) {
                    prefs.putString("last_store_url", parts[2].trim())
                }
                prefs.apply()
            } else if (clipText.startsWith("DZRADAR|")) {
                val parts = clipText.split("|")
                val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()
                prefs.putString("radar_mode", "customer")
                if (parts.size >= 2 && parts[1].isNotBlank()) {
                    prefs.putString("last_token", parts[1].trim())
                }
                if (parts.size >= 3 && parts[2].startsWith("http")) {
                    prefs.putString("last_store_url", parts[2].trim())
                }
                prefs.apply()
            }
        } catch (_: Exception) {}
    }

    private fun captureDeepLinkParams(intent: Intent?) {
        val data = intent?.data ?: return
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()

        val host = data.host ?: ""
        val modeParam = data.getQueryParameter("mode") ?: ""
        if (host.equals("merchant", ignoreCase = true) || modeParam.equals("merchant", ignoreCase = true)) {
            prefs.putString("radar_mode", "merchant")
            val mSlug = data.getQueryParameter("slug")
            if (!mSlug.isNullOrBlank()) {
                prefs.putString("merchant_slug", mSlug.trim())
            }
        } else {
            prefs.putString("radar_mode", "customer")
        }

        val token = data.getQueryParameter("token")
        if (!token.isNullOrBlank()) {
            prefs.putString("last_token", token.trim())
        }

        val storeUrl = data.getQueryParameter("store_url")
        if (!storeUrl.isNullOrBlank()) {
            prefs.putString("last_store_url", storeUrl.trim())
        }

        val slug = data.getQueryParameter("slug")
        if (!slug.isNullOrBlank() && host != "merchant") {
            prefs.putString("last_store_url", "https://my-private-store-app.vercel.app/s/${slug.trim()}")
        }

        prefs.apply()
    }

    private fun getSavedToken(): String {
        return getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("last_token", "SENTINEL-IDLE") ?: "SENTINEL-IDLE"
    }

    private fun getSavedStoreUrl(): String {
        return getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("last_store_url", "https://my-private-store-app.vercel.app/s/dzball")
            ?: "https://my-private-store-app.vercel.app/s/dzball"
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startBubbleService() {
        captureClipboardHandshakeIfNeeded()
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("radar_mode", "customer") ?: "customer"
        val mSlug = prefs.getString("merchant_slug", "latest") ?: "latest"
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("mode", mode)
            putExtra("merchant_slug", mSlug)
            putExtra("token", getSavedToken())
            putExtra("store_url", getSavedStoreUrl())
            putExtra("expand", true)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
            try { startService(serviceIntent) } catch (_: Exception) {}
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun makeCardBg(fillColor: String, strokeColor: String, radiusDp: Int = 18): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }

    private fun enterOfficialPipCapsule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "✅ رادار الطرد يعمل الآن في الخلفية!", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            val pipRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#09090B"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    val dest = getSavedStoreUrl().split("?")[0] + "?native_bubble=1"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dest)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
            pipLiveStatusText = TextView(this).apply {
                text = "💠 رادار الطرد نشط 🟢\nجاري متابعة حالة طلبيتك لحظة بلحظة..."
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
            }
            pipRoot.addView(pipLiveStatusText)
            setContentView(pipRoot)
            handler.removeCallbacks(pipPollRunnable)
            handler.post(pipPollRunnable)
        } else {
            handler.removeCallbacks(pipPollRunnable)
            if (!hasOverlayPermission()) {
                buildInstantOfficialOnboardingUI()
            }
        }
    }

    private fun installPartnerAsOfficialStore(apkUrl: String, targetPkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "يرجى السماح بالتثبيت الرسمي أولاً ثم أعد الضغط", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        Toast.makeText(this, "⏳ جاري تثبيت كبسولة التاجر بوسم المتجر الرسمي...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val tmpFile = File(cacheDir, "official_merchant_target.apk")
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 20000
                }
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(targetPkg)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                FileInputStream(tmpFile).use { input ->
                    session.openWrite("base.apk", 0, tmpFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val callbackIntent = Intent(this, StoreSourceReceiver::class.java)
                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(this, sessionId, callbackIntent, pendingFlags)
                session.commit(pending.intentSender)
                session.close()
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this, "⚠️ تعذر التثبيت المباشر: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildInstantOfficialOnboardingUI() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(32), dp(22), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val logoBadge = TextView(this).apply {
            text = "💠"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0C4A6E"))
                setStroke(dp(3), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(78)).apply {
                bottomMargin = dp(12)
            }
        }
        root.addView(logoBadge)

        val title = TextView(this).apply {
            text = "رادار السكينة وتتبع الطرد"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "🟢 الرادار يعمل الآن في الخلفية ويتابع طلبيتك لحظة بلحظة"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(16)
            }
        }
        root.addView(subtitle)

        // بطاقة الإطلاق المباشر بنقرة واحدة (PiP Official Capsule - Allowed by Default)
        val instantCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#18181B", "#38BDF8", 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val instantTitle = TextView(this).apply {
            text = "⚡ إطلاق كبسولة الرادار العائمة بنقرة واحدة"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val instantDesc = TextView(this).apply {
            text = "اضغط الزر بالأسفل لإطلاق النافذة العائمة الرسمية فوراً دون الحاجة لأي إعدادات إضافية."
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(14)
            }
        }

        val instantBtn = Button(this).apply {
            text = "🚀 تشغيل الكبسولة العائمة فوراً 💠"
            setTextColor(Color.parseColor("#082F49"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#38BDF8"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                startBubbleService()
                if (hasOverlayPermission()) {
                    finish()
                } else {
                    enterOfficialPipCapsule()
                }
            }
        }

        instantCard.addView(instantTitle)
        instantCard.addView(instantDesc)
        instantCard.addView(instantBtn)
        root.addView(instantCard)

        // بطاقة الجسر المتبادل لتثبيت كبسولة التاجر بصفة متجر رسمي (PACKAGE_SOURCE_STORE)
        val merchantBridgeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#18181B", "#F59E0B", 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }

        val bridgeTitle = TextView(this).apply {
            text = "👑 جسر المتجر الرسمي لتثبيت «كبسولة التاجر»"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val bridgeDesc = TextView(this).apply {
            text = "يثبّت تطبيق كبسولة التاجر (v3.3.0 الموقّع رسمياً) بصفة متجر رسمي (PACKAGE_SOURCE_STORE) لفتح كافة الصلاحيات مباشرة دون قيود."
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        }

        val bridgeBtn = Button(this).apply {
            text = "👑 تثبيت كبسولة التاجر بصفة متجر رسمي معتمد"
            setTextColor(Color.parseColor("#09090B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F59E0B"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                installPartnerAsOfficialStore(
                    apkUrl = "https://github.com/anouarkhellaf90210-del/dz-merchant-cockpit/releases/latest/download/DZ-Merchant-Cockpit.apk",
                    targetPkg = "com.dzmerchant.cockpit"
                )
            }
        }

        merchantBridgeCard.addView(bridgeTitle)
        merchantBridgeCard.addView(bridgeDesc)
        merchantBridgeCard.addView(bridgeBtn)
        root.addView(merchantBridgeCard)

        val optionalOverlayBtn = Button(this).apply {
            text = "⚙️ تفعيل الفقاعة الحرة (Display over other apps)"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            background = makeCardBg("#18181B", "#27272A", 12)
            setOnClickListener {
                isWatchingPermission = true
                handler.removeCallbacks(permissionWatcher)
                handler.postDelayed(permissionWatcher, 600)
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
        root.addView(optionalOverlayBtn)

        scrollView.addView(root)
        setContentView(scrollView)
    }
}
