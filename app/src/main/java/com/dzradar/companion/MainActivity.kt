package com.dzradar.companion

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var isWatchingPermission = false
    private lateinit var step1Card: LinearLayout
    private lateinit var primaryActionBtn: Button

    private val permissionWatcher = object : Runnable {
        override fun run() {
            if (!isWatchingPermission) return
            if (hasOverlayPermission()) {
                isWatchingPermission = false
                Toast.makeText(this@MainActivity, "✅ تم تفعيل رادار السكينة بنجاح!", Toast.LENGTH_LONG).show()
                startBubbleAndFinish()
            } else {
                handler.postDelayed(this, 600)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDeepLinkParams(intent)

        if (hasOverlayPermission()) {
            startBubbleAndFinish()
            return
        }

        buildReassuringOnboardingUI()
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
        if (hasOverlayPermission()) {
            startBubbleAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasOverlayPermission()) {
            isWatchingPermission = false
            startBubbleAndFinish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isWatchingPermission = false
        handler.removeCallbacks(permissionWatcher)
    }

    private fun captureClipboardHandshakeIfNeeded() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (clipText.startsWith("DZRADAR|")) {
                val parts = clipText.split("|")
                val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()
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

        val token = data.getQueryParameter("token")
        if (!token.isNullOrBlank()) {
            prefs.putString("last_token", token.trim())
        }

        val storeUrl = data.getQueryParameter("store_url")
        if (!storeUrl.isNullOrBlank()) {
            prefs.putString("last_store_url", storeUrl.trim())
        }

        val slug = data.getQueryParameter("slug")
        if (!slug.isNullOrBlank()) {
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

    private fun startBubbleAndFinish() {
        captureClipboardHandshakeIfNeeded()
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("token", getSavedToken())
            putExtra("store_url", getSavedStoreUrl())
            putExtra("expand", true)
        }
        startService(serviceIntent)
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

    private fun buildReassuringOnboardingUI() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val logoBadge = TextView(this).apply {
            text = "💠"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0C4A6E"))
                setStroke(dp(3), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                bottomMargin = dp(14)
            }
        }
        root.addView(logoBadge)

        val title = TextView(this).apply {
            text = "رادار السكينة وتتبع الطرد"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "طلبك في أيدٍ أمينة ونحن نراقبه لأجلك لحظة بلحظة"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(18)
            }
        }
        root.addView(subtitle)

        val trustShield = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#052E16", "#10B981", 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(20)
            }
        }

        val shieldHeader = TextView(this).apply {
            text = "🛡️ ضمان الأمان والخصوصية 100%"
            setTextColor(Color.parseColor("#34D399"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val shieldBody = TextView(this).apply {
            text = "تطبيق خفيف وآمن تماماً؛ وظيفته الوحيدة هي إظهار كبسولة التتبع المصغرة على شاشتك، ولا يملك أي صلاحية للوصول إلى صورك أو ملفاتك أو أرقام هاتفك."
            setTextColor(Color.parseColor("#D1FAE5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        trustShield.addView(shieldHeader)
        trustShield.addView(shieldBody)
        root.addView(trustShield)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            step1Card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeCardBg("#18181B", "#27272A", 16)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(14)
                }
            }

            val step1Title = TextView(this).apply {
                text = "الخطوة 1: فك القيد الاحترازي (تُفعل مرة واحدة)"
                setTextColor(Color.parseColor("#FBBF24"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val step1Desc = TextView(this).apply {
                text = "إذا كان مفتاح التفعيل رمادياً، اضغط الزر بالأسفل ثم اضغط النقاط الثلاث (⋮) في أعلى الزاوية واختر «السماح بالإعدادات المقيّدة»."
                setTextColor(Color.parseColor("#D4D4D8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.25f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(12)
                }
            }

            val step1Btn = Button(this).apply {
                text = "1. فتح صفحة السماح السريع (⋮) 🔓"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                background = makeCardBg("#27272A", "#3F3F46", 12)
                setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }

            step1Card.addView(step1Title)
            step1Card.addView(step1Desc)
            step1Card.addView(step1Btn)
            root.addView(step1Card)
        }

        val step2Card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#18181B", "#38BDF8", 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val step2Title = TextView(this).apply {
            text = "الخطوة النهائية: إطلاق كبسولة الرادار 💠"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val step2Desc = TextView(this).apply {
            text = "اضغط الزر بالأسفل وفعّل مفتاح «الظهور فوق التطبيقات»؛ ستنطلق كبسولة التتبع على شاشتك فوراً!"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(14)
            }
        }

        primaryActionBtn = Button(this).apply {
            text = "تفعيل الرادار بنقرة واحدة 💠"
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
                if (hasOverlayPermission()) {
                    startBubbleAndFinish()
                } else {
                    isWatchingPermission = true
                    handler.removeCallbacks(permissionWatcher)
                    handler.postDelayed(permissionWatcher, 600)

                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        step2Card.addView(step2Title)
        step2Card.addView(step2Desc)
        step2Card.addView(primaryActionBtn)
        root.addView(step2Card)

        scrollView.addView(root)
        setContentView(scrollView)
    }
}
