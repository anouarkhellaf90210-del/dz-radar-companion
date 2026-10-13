package com.dzradar.companion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class RadarOverlayView(
    context: Context,
    private val onOpenStore: () -> Unit,
    private val onCloseCompanion: () -> Unit,
    private val onConfirmOrder: () -> Unit = {},
    private val onCallCustomer: (String) -> Unit = {},
    private val onMerchantAdvance: () -> Unit = {}
) : FrameLayout(context) {

    private val uiHandler = Handler(Looper.getMainLooper())
    val bubbleContainer: FrameLayout
    private val bubbleRing: GradientDrawable
    private val bubbleIcon: TextView
    private val badgeDot: View

    private val cardContainer: LinearLayout
    private val headerTitle: TextView
    private val modeToggleBtn: TextView
    private val statusText: TextView
    private val subText: TextView

    private val stepperRow: LinearLayout
    private val stepDots = mutableListOf<TextView>()

    private val customerConfirmBtn: TextView
    private val merchantBox: LinearLayout
    private val merchantInfoText: TextView
    private val merchantCallBtn: TextView
    private val merchantAdvanceBtn: TextView

    private var cardOpen = false
    private var isMerchantMode = false
    private var lastState: RadarOrderState? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    init {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.TOP
        }

        bubbleRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#09090B"))
            setStroke(dp(2), Color.parseColor("#10B981"))
        }

        bubbleContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = bubbleRing
            elevation = dp(8).toFloat()
        }

        bubbleIcon = TextView(context).apply {
            text = "🚚"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        bubbleContainer.addView(bubbleIcon)

        badgeDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#10B981"))
                setStroke(dp(1), Color.parseColor("#09090B"))
            }
            layoutParams = LayoutParams(dp(10), dp(10)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(2)
                rightMargin = dp(2)
            }
        }
        bubbleContainer.addView(badgeDot)

        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F209090B"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#3310B981"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        headerTitle = TextView(context).apply {
            text = "رادار الشحنة المباشر ⚡"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        modeToggleBtn = TextView(context).apply {
            text = "👑 التاجر"
            setTextColor(Color.parseColor("#FBBF24"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#27272A"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#52525B"))
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setOnClickListener {
                isMerchantMode = !isMerchantMode
                refreshModeUI()
            }
        }

        topBar.addView(headerTitle)
        topBar.addView(modeToggleBtn)
        cardContainer.addView(topBar)

        statusText = TextView(context).apply {
            text = "طردك: مُستلم ⚡"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        }
        cardContainer.addView(statusText)

        subText = TextView(context).apply {
            text = "متجر Dzball — تزامن فوري"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        }
        cardContainer.addView(subText)

        stepperRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val labels = listOf("1.مستلم", "2.تجهيز", "3.بالطريق", "4.تسليم")
        for (i in 0 until 4) {
            val stepChip = TextView(context).apply {
                text = labels[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) rightMargin = dp(3)
                }
            }
            stepDots.add(stepChip)
            stepperRow.addView(stepChip)
        }
        cardContainer.addView(stepperRow)
        updateStepperStyle(1)

        customerConfirmBtn = TextView(context).apply {
            text = "✅ تأكيد استلامي للطلب الآن"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#34D399"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(7), dp(10), dp(7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener {
                text = "⏳ جاري تأكيد الطلب..."
                onConfirmOrder()
            }
        }
        cardContainer.addView(customerConfirmBtn)

        merchantBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        merchantInfoText = TextView(context).apply {
            text = "🔔 بانتظار بيانات أحدث طلب..."
            setTextColor(Color.parseColor("#FDE68A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setLineSpacing(0f, 1.2f)
        }
        merchantBox.addView(merchantInfoText)

        val merchantActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }

        merchantCallBtn = TextView(context).apply {
            text = "📞 اتصال"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
            setOnClickListener {
                val ph = lastState?.customerPhone ?: ""
                onCallCustomer(ph)
            }
        }

        merchantAdvanceBtn = TextView(context).apply {
            text = "⚡ ترقية الحالة"
            setTextColor(Color.parseColor("#09090B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FBBF24"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                merchantAdvanceBtn.text = "⏳ جاري التحديث..."
                onMerchantAdvance()
            }
        }

        merchantActionsRow.addView(merchantCallBtn)
        merchantActionsRow.addView(merchantAdvanceBtn)
        merchantBox.addView(merchantActionsRow)
        cardContainer.addView(merchantBox)

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val openStoreBtn = TextView(context).apply {
            text = "فتح المتجر 🌐"
            setTextColor(Color.parseColor("#052E16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onOpenStore() }
        }

        val closeBtn = TextView(context).apply {
            text = "إغلاق ✕"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onCloseCompanion() }
        }

        bottomRow.addView(openStoreBtn)
        bottomRow.addView(closeBtn)
        cardContainer.addView(bottomRow)

        rootLayout.addView(bubbleContainer)
        rootLayout.addView(cardContainer)
        addView(rootLayout)
    }

    private fun updateStepperStyle(activeStation: Int) {
        for (i in 0 until stepDots.size) {
            val stepNum = i + 1
            val chip = stepDots[i]
            val reached = stepNum <= activeStation
            chip.setTextColor(if (reached) Color.parseColor("#052E16") else Color.parseColor("#71717A"))
            chip.typeface = if (stepNum == activeStation) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.background = GradientDrawable().apply {
                setColor(if (reached) Color.parseColor("#10B981") else Color.parseColor("#18181B"))
                cornerRadius = dp(6).toFloat()
            }
        }
    }

    private fun refreshModeUI() {
        if (isMerchantMode) {
            headerTitle.text = "رادار التاجر الفوري 👑"
            modeToggleBtn.text = "👤 الزبون"
            bubbleRing.setStroke(dp(2), Color.parseColor("#F59E0B"))
            stepperRow.visibility = View.GONE
            customerConfirmBtn.visibility = View.GONE
            merchantBox.visibility = View.VISIBLE
        } else {
            headerTitle.text = "رادار الشحنة المباشر ⚡"
            modeToggleBtn.text = "👑 التاجر"
            bubbleRing.setStroke(dp(2), Color.parseColor("#10B981"))
            stepperRow.visibility = View.VISIBLE
            merchantBox.visibility = View.GONE
            val st = lastState?.station ?: 1
            customerConfirmBtn.visibility = if (st == 1) View.VISIBLE else View.GONE
        }
    }

    fun isCardOpen(): Boolean = cardOpen

    fun toggleCard(open: Boolean) {
        uiHandler.post {
            cardOpen = open
            cardContainer.visibility = if (open) View.VISIBLE else View.GONE
        }
    }

    fun updateData(data: RadarOrderState) {
        uiHandler.post {
            lastState = data
            statusText.text = data.statusLabel
            subText.text = "${data.storeName} — تتبع حي"
            updateStepperStyle(data.station)

            customerConfirmBtn.text = "✅ تأكيد استلامي للطلب الآن"
            customerConfirmBtn.visibility = if (!isMerchantMode && data.station == 1) View.VISIBLE else View.GONE

            merchantAdvanceBtn.text = "⚡ ترقية الحالة"
            val cName = if (data.customerName.isNotBlank()) data.customerName else "زبون المتجر"
            val cWilaya = if (data.wilaya.isNotBlank()) " (${data.wilaya})" else ""
            val cPrice = if (data.totalPrice.isNotBlank()) "\n💰 المبلغ: ${data.totalPrice} د.ج" else ""
            val cPhone = if (data.customerPhone.isNotBlank()) "\n📱 الهاتف: ${data.customerPhone}" else ""
            merchantInfoText.text = "👤 $cName$cWilaya$cPrice$cPhone"
        }
    }
}
