package com.dzradar.companion

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.net.HttpURLConnection
import java.net.URL

class RadarOverlayView(
    context: Context,
    private val onOpenStore: () -> Unit,
    private val onKillCompanion: () -> Unit,
    private val onHideOverlay: () -> Unit
) : FrameLayout(context) {

    private val uiHandler = Handler(Looper.getMainLooper())
    val bubbleContainer: FrameLayout
    private val bubbleOuterHalo: GradientDrawable
    private val bubbleRing: GradientDrawable
    private val bubbleIcon: TextView
    private val livePingDot: View
    private val livePingDrawable: GradientDrawable

    val cardContainer: LinearLayout
    private val cardBorderDrawable: GradientDrawable
    private val headerPingDot: View
    private val headerPingDrawable: GradientDrawable
    private val storeBadgeContainer: LinearLayout
    private val storeLogoImg: ImageView
    private val storeBadgeText: TextView
    private val currentStatusTitle: TextView
    private val currentStatusSubtitle: TextView

    private val stationNodeDrawables = mutableListOf<GradientDrawable>()
    private val stationLabels = mutableListOf<TextView>()
    private val connectorLines = mutableListOf<View>()

    private var cardOpen = false
    private var currentStation = 0

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    
    private var lastLoadedLogoUrl = ""
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
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
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
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = bubbleOuterHalo
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(10).toFloat()
        }

        val innerBubble = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = bubbleRing
        }

        bubbleIcon = TextView(context).apply {
            text = "🛍️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        innerBubble.addView(bubbleIcon)
        bubbleContainer.addView(innerBubble)

        livePingDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D4AF37"))
            setStroke(dp(1), Color.parseColor("#09090B"))
        }

        livePingDot = View(context).apply {
            background = livePingDrawable
            layoutParams = LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(2)
                rightMargin = dp(2)
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
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(276), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            // النقر على مساحة فارغة في البطاقة يطويها لتعود كبسولة مصغرة
            setOnClickListener {
                toggleCard(false)
            }
        }

        // شريط الرأس: زر الإغلاق النهائي الأحمر الصغير + العنوان + اسم المتجر
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

        val modalHeaderTitle = TextView(context).apply {
            text = "رادار تتبع الطرد الحي"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        storeBadgeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.parseColor("#D4AF37"))
            }
            setPadding(dp(6), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
        }

        storeLogoImg = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
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

        topRow.addView(headerPingDot)
        topRow.addView(modalHeaderTitle)
        topRow.addView(storeBadgeContainer)
        topRow.addView(killRedXBtn)
        cardContainer.addView(topRow)

        // لوحة الحالة
        val statusHeroBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1418181B"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#27272A"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
            setOnClickListener { toggleCard(false) }
        }

        currentStatusTitle = TextView(context).apply {
            text = "متابعة الطلب المباشرة"
            setTextColor(Color.parseColor("#D4AF37"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }

        currentStatusSubtitle = TextView(context).apply {
            text = "طلبك في أيدٍ أمينة ونحن نراقبه لأجلك لحظة بلحظة"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }

        statusHeroBox.addView(currentStatusTitle)
        statusHeroBox.addView(currentStatusSubtitle)
        cardContainer.addView(statusHeroBox)

        // شريط المحطات الأربع
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
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                background = nodeBg
            }

            val iconTv = TextView(context).apply {
                text = icons[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
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
                topMargin = dp(6)
                bottomMargin = dp(14)
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

        // شريط الأزرار: [العودة للمتجر 🌐] + [إخفاء من الشاشة 👁️]
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val openStoreBtn = TextView(context).apply {
            text = "العودة للمتجر 🌐"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener { onOpenStore() }
        }

        val hideStandbyBtn = TextView(context).apply {
            text = "إخفاء من الشاشة 👁️"
            setTextColor(Color.parseColor("#D4D4D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

        rootLayout.addView(bubbleContainer)
        rootLayout.addView(cardContainer)
        addView(rootLayout)

        applyStationVisuals(1, animateMorph = false)
    }

    private fun resolveStationTheme(station: Int): Triple<String, String, String> {
        return when (station) {
            1 -> Triple("⏳", "#FBBF24", "#22FBBF24")
            2 -> Triple("🎁", "#A855F7", "#22A855F7")
            3 -> Triple("🚚", "#10B981", "#2210B981")
            4 -> Triple("✅", "#34D399", "#2234D399")
            else -> Triple("🛍️", "#D4AF37", "#22D4AF37")
        }
    }

    private fun applyStationVisuals(station: Int, animateMorph: Boolean) {
        val (iconSymbol, hexPrimary, hexHalo) = resolveStationTheme(station)
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
            if (data.storeLogoUrl.isNotBlank()) { loadStoreLogoAsync(data.storeLogoUrl) }
            currentStatusTitle.text = data.statusTitle
            currentStatusSubtitle.text = data.statusSubtitle
            applyStationVisuals(data.station, animateMorph = shouldAnimate)
        }
    }
}
