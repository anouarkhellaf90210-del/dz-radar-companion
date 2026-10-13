package com.dzradar.companion

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: RadarOverlayView? = null
    private var trackingToken: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true

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
            if (isScreenOn && trackingToken.isNotBlank()) {
                triggerFetch()
            }
            handler.postDelayed(this, 30000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("token")?.let {
            if (it.isNotBlank()) {
                trackingToken = it
                triggerFetch()
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val sizePx = (38 * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
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
            x = 20
            y = 350
        }

        overlayView = RadarOverlayView(
            this,
            onOpenStore = {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://my-private-store-app.vercel.app")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            },
            onCloseCompanion = {
                stopSelf()
            }
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView?.bubbleContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    triggerFetch()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - initialTouchX)
                    val diffY = abs(event.rawY - initialTouchY)
                    if (diffX < 12 && diffY < 12) {
                        val isOpen = overlayView?.isCardOpen() ?: false
                        overlayView?.toggleCard(!isOpen)
                    } else {
                        val screenWidth = resources.displayMetrics.widthPixels
                        params.x = if (params.x < screenWidth / 2) 16 else screenWidth - sizePx - 16
                        windowManager?.updateViewLayout(overlayView, params)
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

        handler.post(pollingRunnable)
    }

    private fun triggerFetch() {
        if (trackingToken.isBlank()) return
        Thread {
            val data = RadarApiClient.fetchOrder(trackingToken)
            if (data != null) {
                overlayView?.updateData(data)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollingRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {}
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
    }
}
