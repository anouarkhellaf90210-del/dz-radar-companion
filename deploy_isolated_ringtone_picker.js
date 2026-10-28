const fs = require("fs");
const { execSync } = require("child_process");

console.log("====================================================");
console.log("📱 إنشاء نشاط منتقي رنات الهاتف المستقل (RingtonePickerActivity)...");
console.log("====================================================");

// 1. إنشاء ملف RingtonePickerActivity.kt
const actPath = "app/src/main/java/com/dzradar/companion/RingtonePickerActivity.kt";
const actCode = `package com.dzradar.companion

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
`;
fs.writeFileSync(actPath, actCode, "utf8");
console.log("✅ 1/3: تم إنشاء RingtonePickerActivity.kt بنجاح.");

// 2. تسجيل النشاط في AndroidManifest.xml
const manifestPath = "app/src/main/AndroidManifest.xml";
let manifest = fs.readFileSync(manifestPath, "utf8");

if (!manifest.includes("RingtonePickerActivity")) {
  manifest = manifest.replace(
    "</application>",
    `    <activity
            android:name=".RingtonePickerActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
    </application>`
  );
  fs.writeFileSync(manifestPath, manifest, "utf8");
}
console.log("✅ 2/3: تم تسجيل النشاط الشفاف في AndroidManifest.xml.");

// 3. ربط الخيار السادس في RadarOverlayView.kt بـ RingtonePickerActivity
const viewPath = "app/src/main/java/com/dzradar/companion/RadarOverlayView.kt";
let view = fs.readFileSync(viewPath, "utf8");

// استبدال استدعاء MainActivity بـ RingtonePickerActivity عند النقر على الخيار السادس
view = view.replace(
  /Intent\(context,\s*MainActivity::class\.java\)\.apply\s*\{\s*putExtra\("action",\s*"pick_ringtone"\)/g,
  `Intent(context, RingtonePickerActivity::class.java).apply {`
);

const vOpen = (view.match(/\{/g) || []).length;
const vClose = (view.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس RadarOverlayView.kt: { = " + vOpen + " | } = " + vClose);
if (vOpen !== vClose) throw new Error("اختلال أقواس في RadarOverlayView.kt!");
fs.writeFileSync(viewPath, view, "utf8");
console.log("✅ 3/3: تم ربط القائمة المنسدلة بـ RingtonePickerActivity بنجاح.");

execSync("git add -A", { stdio: "inherit" });
try {
  execSync('git commit -m "feat(radar-companion): launch system ringtone picker via dedicated RingtonePickerActivity"', { stdio: "inherit" });
} catch (e) {}
execSync("git push origin main", { stdio: "inherit" });
console.log("🚀 تم دفع التحديث إلى GitHub Actions بنجاح!");
