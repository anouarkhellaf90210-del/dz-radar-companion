package com.dzradar.companion

import android.app.Activity
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
    private lateinit var statusBadge: TextView
    private lateinit var step1Card: LinearLayout
    private lateinit var primaryActionBtn: Button

    private val permissionWatcher = object : Runnable {
        override fun run() {
            if (!isWatchingPermission) return
            if (hasOverlayPermission()) {
                isWatchingPermission = false
                Toast.makeText(this@MainActivity, "✅ تم تفعيل رادار الطرد بنجاح!", Toast.LENGTH_LONG).show()
                startBubbleAndFinish()
            } else {
                handler.postDelayed(this, 600)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureDeepLinkToken(intent)

        // إذا كان الإذن ممنوحاً مسبقاً، أطلق الفقاعة فوراً دون إزعاج الزبون بأي شاشة
        if (hasOverlayPermission()) {
            startBubbleAndFinish()
            return
        }

        buildReassuringOnboardingUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        captureDeepLinkToken(intent)
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

    private fun captureDeepLinkToken(intent: Intent?) {
        val token = intent?.data?.getQueryParameter("token")
        if (!token.isNullOrBlank()) {
            getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_token", token.trim())
                .apply()
        }
    }

    private fun getSavedToken(): String {
        return getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("last_token", "DEMO-TRACK-DZ") ?: "DEMO-TRACK-DZ"
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startBubbleAndFinish() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("token", getSavedToken())
        }
        startService(serviceIntent)
        finish()
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

        // 1. شعار الشاحنة الدائري المتوهج
        val logoBadge = TextView(this).apply {
            text = "🚚"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#064E3B"))
                setStroke(dp(3), Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                bottomMargin = dp(14)
            }
        }
        root.addView(logoBadge)

        // 2. العنوان الرئيسي المطمئن
        val title = TextView(this).apply {
            text = "رادار تتبع الطرد المباشر"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "تابع مكان شحنتك لحظة بلحظة عبر فقاعة مصغرة على شاشتك"
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

        // 3. درع الضمان الأمني والخصوصية (يزيل خوف الزبون تماماً)
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
            text = "هذا التطبيق خفيف وآمن تماماً؛ وظيفته الوحيدة هي رسم أيقونة الشاحنة المصغرة على شاشتك. التطبيق لا يطلب ولا يملك أي صلاحية للوصول إلى صورك أو ملفاتك أو أرقام هاتفك."
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

        // 4. بطاقة الخطوة الأولى (مخصصة لأندرويد 13 فما فوق لفك القيد الاحترازي بسهولة)
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
                text = "الخطوة 1: السماح للتطبيق (تُفعل مرة واحدة)"
                setTextColor(Color.parseColor("#FBBF24"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val step1Desc = TextView(this).apply {
                text = "إذا ظهر لك أن المفتاح رمادي، اضغط الزر بالأسفل ثم اضغط على النقاط الثلاث (⋮) في أعلى الزاوية واختر «السماح بالإعدادات المقيّدة»."
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
                        Toast.makeText(
                            this@MainActivity,
                            "اضغط على (⋮) في الأعلى ثم اختر: السماح بالإعدادات المقيّدة",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                }
            }

            step1Card.addView(step1Title)
            step1Card.addView(step1Desc)
            step1Card.addView(step1Btn)
            root.addView(step1Card)
        }

        // 5. بطاقة الخطوة الثانية (تفعيل الفقاعة والانطلاق الفوري التلقائي)
        val step2Card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBg("#18181B", "#10B981", 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val step2Title = TextView(this).apply {
            text = "الخطوة النهائية: تشغيل فقاعة الشاحنة 🚚"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val step2Desc = TextView(this).apply {
            text = "اضغط على الزر الأخضر بالأسفل وفعّل مفتاح «الظهور فوق التطبيقات»؛ ستنطلق الشاحنة العائمة على شاشتك تلقائياً في نفس اللحظة!"
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
            text = "تفعيل وإطلاق شاحنة التتبع الآن 🚚"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
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
