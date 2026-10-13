package com.dzradar.companion

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class RadarOverlayView(
    context: Context,
    private val onOpenStore: () -> Unit,
    private val onCloseCompanion: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    val bubbleContainer: FrameLayout
    private val cardContainer: LinearLayout
    private val stationText: TextView
    private val detailsText: TextView

    init {
        // 1. الدائرة العائمة الأصلية 38dp
        val sizePx = (38 * density).toInt()
        bubbleContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(sizePx, sizePx)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E609090B"))
                setStroke((1.5 * density).toInt(), Color.parseColor("#10B981"))
            }
            background = bg

            val icon = TextView(context).apply {
                text = "⚡"
                textSize = 15f
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(icon)
        }
        addView(bubbleContainer)

        // 2. البطاقة المنبثقة المصغرة (260dp x 140dp)
        val cardWidth = (260 * density).toInt()
        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(cardWidth, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (44 * density).toInt()
            }
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#F509090B"))
                setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
            }
            background = bg
        }

        val headerText = TextView(context).apply {
            text = "رادار الشحنة المباشر ⚡"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.RIGHT
        }

        stationText = TextView(context).apply {
            text = "طردك: مُستلم"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.RIGHT
            setPadding(0, (6 * density).toInt(), 0, 0)
        }

        detailsText = TextView(context).apply {
            text = "تتبع مباشر من المتجر"
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 11f
            gravity = Gravity.RIGHT
            setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnStore = TextView(context).apply {
            text = "فتح المتجر 🌐"
            setTextColor(Color.BLACK)
            textSize = 11f
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10 * density
                setColor(Color.parseColor("#10B981"))
            }
            background = bg
            setOnClickListener {
                toggleCard(false)
                onOpenStore()
            }
        }

        val btnClose = TextView(context).apply {
            text = "إغلاق ✕"
            setTextColor(Color.parseColor("#FDA4AF"))
            textSize = 11f
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                toggleCard(false)
                onCloseCompanion()
            }
        }

        actionRow.addView(btnClose)
        actionRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams((12 * density).toInt(), 1) })
        actionRow.addView(btnStore)

        cardContainer.addView(headerText)
        cardContainer.addView(stationText)
        cardContainer.addView(detailsText)
        cardContainer.addView(actionRow)

        addView(cardContainer)
    }

    fun isCardOpen(): Boolean = cardContainer.visibility == View.VISIBLE

    fun toggleCard(show: Boolean) {
        cardContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun updateData(data: RadarOrderData) {
        post {
            val label = when (data.station) {
                1 -> "طردك: مُستلم ⚡"
                2 -> "طردك: قيد التجهيز 📦"
                3 -> "طردك: في الطريق إليك 🚚"
                4 -> "طردك: تم التسليم 🤝"
                else -> "طردك قيد المتابعة"
            }
            stationText.text = label
            detailsText.text = if (data.wilaya.isNotBlank()) "ولاية ${data.wilaya} • تتبع مباشر" else "تتبع مباشر من المتجر"
        }
    }
}
