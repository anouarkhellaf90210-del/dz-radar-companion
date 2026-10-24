package com.dzradar.companion

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isWatchingPermission = false
    private lateinit var fullRootScroll: ScrollView
    private lateinit var pipRootContainer: LinearLayout
    private lateinit var pipStatusTitle: TextView
    private lateinit var pipStatusSubtitle: TextView
    private lateinit var pipStoreBadge: TextView
    private var embeddedRadarView: RadarOverlayView? = null

    private val permissionWatcher = object : Runnable {
        override fun run() {
            if (!isWatchingPermission) return
            if (hasOverlayPermission()) {
                isWatchingPermission = false
                Toast.makeText(this@MainActivity, "✅ تم إطلاق كبسولة الرادار العائمة بنجاح!", Toast.LENGTH_LONG).show()
                startBubbleAndFinish()
            } else {
                handler.postDelayed(this, 600)
            }
        }
    }

    private val liveStateUpdater = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            val mode = prefs.getString("radar_mode", "customer") ?: "customer"
            val mSlug = prefs.getString("merchant_slug", "latest") ?: "latest"
            val token = getSavedToken()

            Thread {
                val state = if (mode == "merchant") {
                    RadarApiClient.fetchMerchantState(mSlug)
                } else {
                    RadarApiClient.fetchOrder(token) ?: RadarApiClient.fetchOrder("latest")
                }
                if (state != null) {
                    handler.post {
                        embeddedRadarView?.updateData(state)
                        if (::pipStoreBadge.isInitialized) {
                            pipStoreBadge.text = state.storeName
                            pipStatusTitle.text = state.statusTitle
                            pipStatusSubtitle.text = state.statusSubtitle
                        }
                    }
                }
            }.start()

            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDeepLinkParams(intent)
        requestNotificationPermissionIfNeeded()

        startBubbleServiceOnly(expand = true)

        if (hasOverlayPermission()) {
            finishAndRemoveTask()
            return
        }

        buildOfficialZeroRestrictionUI()
        handler.post(liveStateUpdater)
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
        startBubbleServiceOnly(expand = true)
        if (hasOverlayPermission()) {
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasOverlayPermission()) {
            isWatchingPermission = false
            startBubbleAndFinish()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!hasOverlayPermission() && !isWatchingPermission) {
            enterFloatingPipCapsule()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::fullRootScroll.isInitialized && ::pipRootContainer.isInitialized) {
            fullRootScroll.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
            pipRootContainer.visibility = if (isInPictureInPictureMode) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isWatchingPermission = false
        handler.removeCallbacks(permissionWatcher)
        handler.removeCallbacks(liveStateUpdater)
    }

    private fun isInstalledAsOfficialStoreSource(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            val info = packageManager.getInstallSourceInfo(packageName)
            info.packageSource == PackageInstaller.PACKAGE_SOURCE_STORE
        } catch (_: Exception) {
            false
        }
    }

    private fun upgradeToOfficialStoreSource() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                try {
                    val allowIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    Toast.makeText(
                        this,
                        "اضغط «سماح» مرة واحدة لتوثيق التطبيق كمصدر متجر رسمي وفتح جميع الأذونات تلقائياً",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(allowIntent)
                    return
                } catch (_: Exception) {}
            }
        }

        Thread {
            try {
                val apkFile = File(applicationInfo.sourceDir)
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= 33) {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                FileInputStream(apkFile).use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val callbackIntent = Intent(this, StoreSourceReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(this, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Exception) {
                handler.post {
                    openOverlaySettingsWithWatcher()
                }
            }
        }.start()
    }

    private fun openOverlaySettingsWithWatcher() {
        isWatchingPermission = true
        handler.removeCallbacks(permissionWatcher)
        handler.postDelayed(permissionWatcher, 600)
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 101)
                }
            } catch (_: Exception) {}
        }
    }

    private fun enterFloatingPipCapsule(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 10))
                    .build()
                return enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
        return false
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
            .getString("last_token", "latest") ?: "latest"
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

    private fun startBubbleServiceOnly(expand: Boolean = true) {
        captureClipboardHandshakeIfNeeded()
        val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("radar_mode", "customer") ?: "customer"
        val mSlug = prefs.getString("merchant_slug", "latest") ?: "latest"
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("mode", mode)
            putExtra("merchant_slug", mSlug)
            putExtra("token", getSavedToken())
            putExtra("store_url", getSavedStoreUrl())
            putExtra("expand", expand)
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

    private fun startBubbleAndFinish() {
        startBubbleServiceOnly(expand = true)
        finishAndRemoveTask()
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

    private fun buildOfficialZeroRestrictionUI() {
        val masterFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }

        pipRootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = makeCardBg("#09090B", "#10B981", 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        pipStoreBadge = TextView(this).apply {
            text = "💠 رادار المتجر المباشر"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        pipStatusTitle = TextView(this).apply {
            text = "طردك: قيد المتابعة الحية ⚡"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        pipStatusSubtitle = TextView(this).apply {
            text = "متصل بالمتجر لحظة بلحظة"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }

        pipRootContainer.addView(pipStoreBadge)
        pipRootContainer.addView(pipStatusTitle)
        pipRootContainer.addView(pipStatusSubtitle)

        fullRootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val activeBadge = TextView(this).apply {
            text = "✅ الإصدار الرسمي v1.3.0 نشط ومتصل"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = makeCardBg("#052E16", "#10B981", 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        root.addView(activeBadge)

        val title = TextView(this).apply {
            text = "كبسولة رادار الطرد والتاجر"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "متابعة حية فورية فوق شاشتك بدون قيود"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }
        root.addView(subtitle)

        val radarHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        }

        embeddedRadarView = RadarOverlayView(
            this,
            onOpenStore = {
                val dest = getSavedStoreUrl()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dest)))
                } catch (_: Exception) {}
            },
            onKillCompanion = {
                finish()
            },
            onHideOverlay = {
                enterFloatingPipCapsule()
            }
        ).apply {
            bubbleContainer.visibility = View.GONE
            toggleCard(true)
        }
        radarHolder.addView(embeddedRadarView)
        root.addView(radarHolder)

        // الزر الأول: التوثيق كمصدر متجر رسمي (PACKAGE_SOURCE_STORE) لفتح قفل Display over other apps مباشرة
        val storeUnlockBtn = Button(this).apply {
            text = "🛡️ تفعيل الكبسولة الدائرية بصلاحية المتجر الرسمي 💠"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener {
                if (hasOverlayPermission()) {
                    startBubbleAndFinish()
                } else if (!isInstalledAsOfficialStoreSource()) {
                    upgradeToOfficialStoreSource()
                } else {
                    openOverlaySettingsWithWatcher()
                }
            }
        }
        root.addView(storeUnlockBtn)

        // الزر الثاني: التعويم الفوري بدون إعدادات عبر Picture-in-Picture
        val instantPipBtn = Button(this).apply {
            text = "🚀 أو تعويم النافذة المصغرة فوراً بدون إعدادات"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = makeCardBg("#18181B", "#38BDF8", 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startBubbleServiceOnly(expand = true)
                if (!enterFloatingPipCapsule()) {
                    moveTaskToBack(true)
                }
            }
        }
        root.addView(instantPipBtn)

        fullRootScroll.addView(root)
        masterFrame.addView(fullRootScroll)
        masterFrame.addView(pipRootContainer)
        setContentView(masterFrame)
    }
}
