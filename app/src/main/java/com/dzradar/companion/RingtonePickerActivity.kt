package com.dzradar.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

class RingtonePickerActivity : Activity() {

    private val REQ_CODE = 9988
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUriStr = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("customer_custom_tone_uri", null)
        val currentUri = if (currentUriStr != null) Uri.parse(currentUriStr) else Settings.System.DEFAULT_NOTIFICATION_URI

        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة التنبيه للرادار من هاتفك 📱")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE && resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(this, uri)
                val title = ringtone?.getTitle(this) ?: "نغمة مخصصة"
                val prefs = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).edit()
                prefs.putString("customer_custom_tone_uri", uri.toString())
                prefs.putString("customer_custom_tone_name", title)
                prefs.putInt("customer_tone_idx", 5)
                prefs.apply()
                Toast.makeText(this, "تم اعتماد نغمة الهاتف: " + title + " 📱", Toast.LENGTH_SHORT).show()
                try {
                    ringtone?.play()
                    handler.postDelayed({ try { ringtone?.stop() } catch (_: Exception) {} }, 1800)
                } catch (_: Exception) {}
            }
        }
        finish()
    }
}
