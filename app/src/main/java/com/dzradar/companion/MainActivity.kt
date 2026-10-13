package com.dzradar.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val OVERLAY_REQ_CODE = 4321
    private var pendingToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingToken = intent?.data?.getQueryParameter("token")

        if (checkOverlayPermission()) {
            startBubbleService(pendingToken)
            finish()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(60, 60, 60, 60)
            setBackgroundColor(0xFF09090B.toInt())
        }

        val title = TextView(this).apply {
            text = "تفعيل فقاعة الرادار العائمة ⚡"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val desc = TextView(this).apply {
            text = "يرجى تفعيل إذن «الظهور فوق التطبيقات» لتتمكن الدائرة من الطفو بحرية أثناء تصفح هاتفك."
            textSize = 13f
            setTextColor(0xFFA1A1AA.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 30, 0, 50)
        }

        val btn = Button(this).apply {
            text = "منح الإذن وتفعيل الفقاعة"
            setBackgroundColor(0xFF10B981.toInt())
            setTextColor(0xFF000000.toInt())
            setOnClickListener {
                requestOverlayPermission()
            }
        }

        layout.addView(title)
        layout.addView(desc)
        layout.addView(btn)

        setContentView(layout)
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQ_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQ_CODE) {
            if (checkOverlayPermission()) {
                startBubbleService(pendingToken)
                finish()
            } else {
                Toast.makeText(this, "يلزم تفعيل الإذن لتشغيل الفقاعة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startBubbleService(token: String?) {
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            putExtra("token", token)
        }
        startService(intent)
    }
}
