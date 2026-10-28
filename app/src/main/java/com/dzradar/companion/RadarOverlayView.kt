package com.dzradar.companion

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class RadarOverlayView(
    context: Context,
    private val onOpenStore: () -> Unit,
    private val onKillCompanion: () -> Unit,
    private val onHideOverlay: () -> Unit,
    private val onAdvanceOrder: (String) -> Unit = {},
    private val onCallCustomer: (String) -> Unit = {},
    private val onWhatsAppCustomer: (String, String, String) -> Unit = { _, _, _ -> }
) : FrameLayout(context) {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val sentinelPixelView: View
    val bubbleContainer: FrameLayout
    private val bubbleOuterHalo: GradientDrawable
    private val bubbleRing: GradientDrawable
    private val bubbleIcon: TextView
    private val bubbleLogoImg: ImageView
    private val livePingDot: TextView
    private val livePingDrawable: GradientDrawable

    val cardContainer: LinearLayout
    private val cardBorderDrawable: GradientDrawable
    private val headerPingDot: View
    private val headerPingDrawable: GradientDrawable
    private val modalHeaderTitle: TextView
    private val storeBadgeContainer: LinearLayout
    private val storeLogoImg: ImageView
    private val storeBadgeText: TextView
    private val killRedXBtn: TextView
    private val currentStatusTitle: TextView
    private val currentStatusSubtitle: TextView

    private val merchantActionsContainer: LinearLayout
    private val merchantAdvanceBtn: TextView
    private val merchantCallBtn: TextView
    private val merchantWaBtn: TextView
    private val openStoreBtn: TextView

    private val stationNodeDrawables = mutableListOf<GradientDrawable>()
    private val stationLabels = mutableListOf<TextView>()
    private val stationNodeIcons = mutableListOf<TextView>()
    private val connectorLines = mutableListOf<View>()

    private var cardOpen = false
    private var currentStation = 0
    private var lastLoadedLogoUrl = ""
    private var currentActiveOrderId = ""
    private var currentCustomerPhone = ""
    private var currentCustomerName = ""
    private var currentCleanStoreName = ""

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val rect = Rect(0, 0, size, size)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, (bitmap.width - size) / -2f, (bitmap.height - size) / -2f, paint)
        return output
    }

    private fun loadStoreLogoAsync(urlStr: String) {
        if (urlStr.isBlank() || !urlStr.startsWith("http") || urlStr == lastLoadedLogoUrl) return
        Thread {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.connect()
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                conn.inputStream.close()
                if (bmp != null) {
                    val circleBmp = getCircularBitmap(bmp)
                    lastLoadedLogoUrl = urlStr
                    uiHandler.post {
                        storeLogoImg.setImageBitmap(circleBmp)
                        storeLogoImg.visibility = View.VISIBLE
                        bubbleLogoImg.setImageBitmap(circleBmp)
                        bubbleLogoImg.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    init {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.TOP
        }

        bubbleOuterHalo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#22D4AF37"))
        }

        bubbleRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#09090B"))
            setStroke(dp(2), Color.parseColor("#D4AF37"))
        }

        bubbleContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = bubbleOuterHalo
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(10).toFloat()
        }

        val innerBubble = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = bubbleRing
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        bubbleLogoImg = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        bubbleIcon = TextView(context).apply {
            text = "🛍️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        innerBubble.addView(bubbleLogoImg)
        innerBubble.addView(bubbleIcon)
        bubbleContainer.addView(innerBubble)

        livePingDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D4AF37"))
            setStroke(dp(1), Color.parseColor("#09090B"))
        }

        livePingDot = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = livePingDrawable
            layoutParams = LayoutParams(dp(14), dp(14)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        bubbleContainer.addView(livePingDot)

        cardBorderDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#F709090B"))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.parseColor("#55D4AF37"))
        }

        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = cardBorderDrawable
            setPadding(dp(15), dp(13), dp(15), dp(13))
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(292), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            setOnClickListener { toggleCard(false) }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        headerPingDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D4AF37"))
        }

        headerPingDot = View(context).apply {
            background = headerPingDrawable
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                leftMargin = dp(6)
            }
        }

        modalHeaderTitle = TextView(context).apply {
            text = "رادار تتبع الطرد الحي"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        storeBadgeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#D4AF37"))
            }
            setPadding(dp(6), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            }
        }

        storeLogoImg = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                leftMargin = dp(4)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        storeBadgeText = TextView(context).apply {
            text = "متجر Patron"
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
        }

        storeBadgeContainer.addView(storeLogoImg)
        storeBadgeContainer.addView(storeBadgeText)

        killRedXBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setOnClickListener { onKillCompanion() }
        }

        topRow.addView(headerPingDot)
        topRow.addView(modalHeaderTitle)
        topRow.addView(storeBadgeContainer)
        topRow.addView(killRedXBtn)
        cardContainer.addView(topRow)

        val statusHeroBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1418181B"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setPadding(dp(11), dp(9), dp(11), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
                bottomMargin = dp(10)
            }
            setOnClickListener { toggleCard(false) }
        }

        currentStatusTitle = TextView(context).apply {
            text = "متابعة الطلب المباشرة"
            setTextColor(Color.parseColor("#D4AF37"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        currentStatusSubtitle = TextView(context).apply {
            text = "طلبك في أيدٍ أمينة ونحن نراقبه لأجلك لحظة بلحظة"
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }

        statusHeroBox.addView(currentStatusTitle)
        statusHeroBox.addView(currentStatusSubtitle)
        cardContainer.addView(statusHeroBox)

        merchantActionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        merchantAdvanceBtn = TextView(context).apply {
            text = "⚡ قبول وتأكيد الطلب فوراً"
            setTextColor(Color.parseColor("#09090B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FBBF24"))
                cornerRadius = dp(11).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            setOnClickListener {
                if (currentActiveOrderId.isNotBlank()) {
                    this@apply.text = "⏳ جاري تحديث حالة الطلب..."
                    onAdvanceOrder(currentActiveOrderId)
                }
            }
        }

        val merchantContactRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        merchantCallBtn = TextView(context).apply {
            text = "📞 اتصال بالزبون"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1B4B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#6366F1"))
            }
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(5)
            }
            setOnClickListener {
                if (currentCustomerPhone.isNotBlank()) {
                    onCallCustomer(currentCustomerPhone)
                }
            }
        }

        merchantWaBtn = TextView(context).apply {
            text = "💬 واتساب الزبون"
            setTextColor(Color.parseColor("#6EE7B7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#052E16"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (currentCustomerPhone.isNotBlank()) {
                    onWhatsAppCustomer(currentCustomerPhone, currentCustomerName, currentCleanStoreName)
                }
            }
        }

        merchantContactRow.addView(merchantCallBtn)
        merchantContactRow.addView(merchantWaBtn)
        merchantActionsContainer.addView(merchantAdvanceBtn)
        merchantActionsContainer.addView(merchantContactRow)
        cardContainer.addView(merchantActionsContainer)

        val stepperTrackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { toggleCard(false) }
        }

        val icons = listOf("⏳", "🎁", "🚚", "✅")
        for (i in 0 until 4) {
            val nodeBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#18181B"))
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            stationNodeDrawables.add(nodeBg)

            val nodeFrame = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                background = nodeBg
            }

            val iconTv = TextView(context).apply {
                text = icons[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            stationNodeIcons.add(iconTv)
            nodeFrame.addView(iconTv)
            stepperTrackRow.addView(nodeFrame)

            if (i < 3) {
                val connector = View(context).apply {
                    setBackgroundColor(Color.parseColor("#27272A"))
                    layoutParams = LinearLayout.LayoutParams(0, dp(2), 1f).apply {
                        leftMargin = dp(4)
                        rightMargin = dp(4)
                    }
                }
                connectorLines.add(connector)
                stepperTrackRow.addView(connector)
            }
        }
        cardContainer.addView(stepperTrackRow)

        val labelsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
                bottomMargin = dp(12)
            }
            setOnClickListener { toggleCard(false) }
        }

        val titles = listOf("قيد التأكيد", "تم التجهيز", "خرج للتوصيل", "تم الاستلام")
        for (i in 0 until 4) {
            val lbl = TextView(context).apply {
                text = titles[i]
                setTextColor(Color.parseColor("#71717A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            stationLabels.add(lbl)
            labelsRow.addView(lbl)
        }
        cardContainer.addView(labelsRow)

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        openStoreBtn = TextView(context).apply {
            text = "العودة للمتجر 🌐"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener { onOpenStore() }
        }

        val hideStandbyBtn = TextView(context).apply {
            text = "إخفاء من الشاشة 👁️"
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#27272A"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
            setOnClickListener { onHideOverlay() }
        }

        actionsRow.addView(openStoreBtn)
        actionsRow.addView(hideStandbyBtn)
        cardContainer.addView(actionsRow)

        val toneNames = listOf(
            "🎵 الكاشير 💰 ▼",
            "🎵 كريستال ✨ ▼",
            "🎵 نبض الرادار 📡 ▼",
            "🎵 الجرس الملكي 👑 ▼",
            "🎵 اهتزاز فقط 📳 ▼"
        )
        val radarPrefs = context.getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)

        fun playCustomerTonePreview(idx: Int) {
            try {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 120, 60, 180), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(250)
                }
            } catch (_: Exception) {}
            if (idx == 4) return
            try {
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                when (idx) {
                    0 -> {
                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 200)
                        uiHandler.postDelayed({ try { tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 350) } catch (_: Exception) {} }, 220)
                    }
                    1 -> {
                        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 140)
                        uiHandler.postDelayed({ try { tg.startTone(android.media.ToneGenerator.TONE_CDMA_PIP, 260) } catch (_: Exception) {} }, 160)
                    }
                    2 -> {
                        tg.startTone(android.media.ToneGenerator.TONE_SUP_RADIO_ACK, 150)
                        uiHandler.postDelayed({ try { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 280) } catch (_: Exception) {} }, 190)
                    }
                    3 -> {
                        tg.startTone(android.media.ToneGenerator.TONE_CDMA_ABBR_ALERT, 240)
                        uiHandler.postDelayed({ try { tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 360) } catch (_: Exception) {} }, 260)
                    }
                }
                uiHandler.postDelayed({ try { tg.release() } catch (_: Exception) {} }, 700)
            } catch (_: Exception) {}
        }

        val toneRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val savedToneIdx = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, toneNames.size - 1)
        val toneSelectBtn = TextView(context).apply {
            text = toneNames[savedToneIdx]
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener {
                val cur = radarPrefs.getInt("customer_tone_idx", 0)
                val next = (cur + 1) % toneNames.size
                radarPrefs.edit().putInt("customer_tone_idx", next).apply()
                text = toneNames[next]
                playCustomerTonePreview(next)
                android.widget.Toast.makeText(context, "تم اختيار نغمة: " + toneNames[next].replace(" ▼", ""), android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val testToneBtn = TextView(context).apply {
            text = "🔔 تجربة الرنين"
            setTextColor(Color.parseColor("#E4E4E7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#3F3F46"))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
            setOnClickListener {
                val cur = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, toneNames.size - 1)
                playCustomerTonePreview(cur)
            }
        }

        toneRow.addView(toneSelectBtn)
        toneRow.addView(testToneBtn)
        cardContainer.addView(toneRow)

        sentinelPixelView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 1)
            setBackgroundColor(Color.argb(2, 212, 175, 55))
        }
        rootLayout.addView(sentinelPixelView)
        rootLayout.addView(bubbleContainer)
        rootLayout.addView(cardContainer)
        addView(rootLayout)

        applyStationVisuals(1, animateMorph = false)
    }

    private fun resolveStationTheme(station: Int, isPickup: Boolean = false): Triple<String, String, String> {
        return when (station) {
            1 -> Triple("⏳", "#FBBF24", "#22FBBF24")
            2 -> if (isPickup) Triple("📦", "#38BDF8", "#2238BDF8") else Triple("🎁", "#A855F7", "#22A855F7")
            3 -> if (isPickup) Triple("🏪", "#C084FC", "#22C084FC") else Triple("🚚", "#10B981", "#2210B981")
            4 -> if (isPickup) Triple("🤝", "#34D399", "#2234D399") else Triple("✅", "#34D399", "#2234D399")
            else -> Triple("🛍️", "#D4AF37", "#22D4AF37")
        }
    }

    private fun applyStationVisuals(station: Int, animateMorph: Boolean, isPickup: Boolean = false) {
        val (iconSymbol, hexPrimary, hexHalo) = resolveStationTheme(station, isPickup)
        val primaryColor = Color.parseColor(hexPrimary)
        val haloColor = Color.parseColor(hexHalo)

        if (animateMorph && bubbleIcon.text != iconSymbol) {
            val fadeOut = ObjectAnimator.ofPropertyValuesHolder(
                bubbleIcon,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.4f)
            ).apply { duration = 160 }

            fadeOut.start()
            uiHandler.postDelayed({
                bubbleIcon.text = iconSymbol
                bubbleOuterHalo.setColor(haloColor)
                bubbleRing.setStroke(dp(2), primaryColor)
                livePingDrawable.setColor(primaryColor)
                headerPingDrawable.setColor(primaryColor)
                cardBorderDrawable.setStroke(dp(1), primaryColor)
                currentStatusTitle.setTextColor(primaryColor)

                ObjectAnimator.ofPropertyValuesHolder(
                    bubbleIcon,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1.12f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1.12f, 1f)
                ).apply {
                    duration = 260
                    interpolator = DecelerateInterpolator()
                }.start()
            }, 160)
        } else {
            bubbleIcon.text = iconSymbol
            bubbleOuterHalo.setColor(haloColor)
            bubbleRing.setStroke(dp(2), primaryColor)
            livePingDrawable.setColor(primaryColor)
            headerPingDrawable.setColor(primaryColor)
            cardBorderDrawable.setStroke(dp(1), primaryColor)
            currentStatusTitle.setTextColor(primaryColor)
        }

        for (i in 0 until 4) {
            val stepNum = i + 1
            val isCompleted = station >= stepNum && station > 0
            val isCurrent = station == stepNum
            val nodeDrawable = stationNodeDrawables[i]
            val lbl = stationLabels[i]

            if (isCurrent) {
                nodeDrawable.setColor(haloColor)
                nodeDrawable.setStroke(dp(2), primaryColor)
                lbl.setTextColor(Color.WHITE)
                lbl.typeface = Typeface.DEFAULT_BOLD
            } else if (isCompleted) {
                nodeDrawable.setColor(Color.parseColor("#052E16"))
                nodeDrawable.setStroke(dp(1), Color.parseColor("#10B981"))
                lbl.setTextColor(Color.parseColor("#D4D4D8"))
                lbl.typeface = Typeface.DEFAULT
            } else {
                nodeDrawable.setColor(Color.parseColor("#18181B"))
                nodeDrawable.setStroke(dp(1), Color.parseColor("#27272A"))
                lbl.setTextColor(Color.parseColor("#71717A"))
                lbl.typeface = Typeface.DEFAULT
            }
        }

        for (i in 0 until connectorLines.size) {
            val reached = station > (i + 1)
            connectorLines[i].setBackgroundColor(
                if (reached) Color.parseColor("#10B981") else Color.parseColor("#27272A")
            )
        }
    }

    fun setStandbyMode(standby: Boolean) {
        uiHandler.post {
            if (standby) {
                cardOpen = false
                cardContainer.visibility = View.GONE
                bubbleContainer.visibility = View.GONE
                sentinelPixelView.visibility = View.VISIBLE
            } else {
                bubbleContainer.visibility = View.VISIBLE
                sentinelPixelView.visibility = View.VISIBLE
            }
        }
    }

    fun isCardOpen(): Boolean = cardOpen

    fun toggleCard(open: Boolean) {
        uiHandler.post {
            cardOpen = open
            if (open) {
                cardContainer.alpha = 0f
                cardContainer.scaleX = 0.92f
                cardContainer.scaleY = 0.92f
                cardContainer.visibility = View.VISIBLE
                cardContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                cardContainer.visibility = View.GONE
            }
        }
    }

    fun updateData(data: RadarOrderState) {
        uiHandler.post {
            val shouldAnimate = currentStation != data.station
            currentStation = data.station
            storeBadgeText.text = data.storeName
            currentStatusTitle.text = data.statusTitle
            currentStatusSubtitle.text = data.statusSubtitle
            currentActiveOrderId = data.activeOrderId
            currentCustomerPhone = data.customerPhone
            currentCustomerName = data.customerName
            currentCleanStoreName = data.storeName.replace("👑", "").trim()

            if (data.isMerchantMode) {
                modalHeaderTitle.text = "رادار قيادة التاجر"
                openStoreBtn.text = "لوحة التاجر 🌐"
                stationLabels[0].text = "جديد (${data.newCount})"
                stationLabels[1].text = "تجهيز (${data.preparingCount})"
                stationLabels[2].text = "شحن (${data.shippingCount})"
                stationLabels[3].text = "تم (${data.deliveredCount})"

                if (data.newCount > 0) {
                    livePingDot.text = data.newCount.toString()
                    livePingDrawable.setColor(Color.parseColor("#E11D48"))
                } else {
                    livePingDot.text = ""
                }

                if (data.activeOrderId.isNotBlank()) {
                    merchantActionsContainer.visibility = View.VISIBLE
                    merchantAdvanceBtn.text = data.nextActionLabel.ifBlank { "⚡ قبول وتأكيد الطلب فوراً" }
                } else {
                    merchantActionsContainer.visibility = View.GONE
                }
                if (stationNodeIcons.size >= 4) {
                    stationNodeIcons[0].text = "⏳"
                    stationNodeIcons[1].text = "🎁"
                    stationNodeIcons[2].text = "🚚"
                    stationNodeIcons[3].text = "✅"
                }
            } else {
                modalHeaderTitle.text = if (data.isPickup) "رادار الاستلام من المحل 🏪" else "رادار تتبع الطرد الحي"
                openStoreBtn.text = "العودة للمتجر 🌐"
                stationLabels[0].text = "قيد التأكيد"
                stationLabels[1].text = if (data.isPickup) "تجهيز بالمحل" else "تم التجهيز"
                stationLabels[2].text = if (data.isPickup) "جاهز بالمحل" else "خرج للتوصيل"
                stationLabels[3].text = "تم الاستلام"
                if (stationNodeIcons.size >= 4) {
                    stationNodeIcons[0].text = "⏳"
                    stationNodeIcons[1].text = if (data.isPickup) "📦" else "🎁"
                    stationNodeIcons[2].text = if (data.isPickup) "🏪" else "🚚"
                    stationNodeIcons[3].text = if (data.isPickup) "🤝" else "✅"
                }
                livePingDot.text = ""
                merchantActionsContainer.visibility = View.GONE
            }

            applyStationVisuals(data.station, animateMorph = shouldAnimate, isPickup = data.isPickup)
            if (data.storeLogoUrl.isNotBlank()) {
                loadStoreLogoAsync(data.storeLogoUrl)
                bubbleIcon.visibility = View.GONE
            } else {
                bubbleIcon.visibility = View.VISIBLE
            }
        }
    }
}
