const fs = require("fs");
const { execSync } = require("child_process");

console.log("====================================================");
console.log("🛠️ ضبط منتقي رنات الهاتف الصريح لكبسولة الزبون...");
console.log("====================================================");

// 1. ترقية MainActivity.kt
const mainPath = "app/src/main/java/com/dzradar/companion/MainActivity.kt";
let main = fs.readFileSync(mainPath, "utf8");

if (!main.includes("import android.media.RingtoneManager")) {
  main = main.replace(
    "import android.content.Context",
    "import android.content.Context\nimport android.media.RingtoneManager"
  );
}

const pickerMethod = `
    private val RINGTONE_REQ_CODE = 7788

    private fun launchSystemRingtonePicker() {
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
        startActivityForResult(intent, RINGTONE_REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_REQ_CODE && resultCode == Activity.RESULT_OK) {
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
                    handler.postDelayed({ try { ringtone?.stop() } catch (_: Exception) {} }, 1600)
                } catch (_: Exception) {}
            }
            finish()
            return
        }
    }
`;

main = main.replace(
  "class MainActivity : Activity() {",
  "class MainActivity : Activity() {\n" + pickerMethod
);

main = main.replace(
  "override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)",
  `override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.getStringExtra("action") == "pick_ringtone") {
            super.onCreate(savedInstanceState)
            launchSystemRingtonePicker()
            return
        }
        super.onCreate(savedInstanceState)`
);

const mOpen = (main.match(/\{/g) || []).length;
const mClose = (main.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس MainActivity.kt: { = " + mOpen + " | } = " + mClose);
if (mOpen !== mClose) throw new Error("اختلال أقواس في MainActivity.kt!");
fs.writeFileSync(mainPath, main, "utf8");
console.log("✅ 1/3: تم تحديث MainActivity.kt بالاستيرادات الرسمية.");

// 2. ترقية RadarOverlayView.kt بالاستيرادات وزر رنة الهاتف
const viewPath = "app/src/main/java/com/dzradar/companion/RadarOverlayView.kt";
let view = fs.readFileSync(viewPath, "utf8");

if (!view.includes("import android.content.Intent")) {
  view = view.replace(
    "import android.content.Context",
    "import android.content.Context\nimport android.content.Intent\nimport android.media.RingtoneManager"
  );
}

const oldToneNamesBlock = `        val toneNames = listOf(
            "🎵 الكاشير 💰 ▼",
            "🎵 كريستال ✨ ▼",
            "🎵 نبض الرادار 📡 ▼",
            "🎵 الجرس الملكي 👑 ▼",
            "🎵 اهتزاز فقط 📳 ▼"
        )`;

const newToneNamesBlock = `        val toneNames = listOf(
            "🎵 الكاشير 💰 ▼",
            "🎵 كريستال ✨ ▼",
            "🎵 نبض الرادار 📡 ▼",
            "🎵 الجرس الملكي 👑 ▼",
            "🎵 اهتزاز فقط 📳 ▼",
            "📱 نغمة من الهاتف... 🎶"
        )`;

if (view.includes(oldToneNamesBlock)) {
  view = view.replace(oldToneNamesBlock, newToneNamesBlock);
}

const oldToneClick = `            setOnClickListener {
                val cur = radarPrefs.getInt("customer_tone_idx", 0)
                val next = (cur + 1) % toneNames.size
                radarPrefs.edit().putInt("customer_tone_idx", next).apply()
                text = toneNames[next]
                playCustomerTonePreview(next)
                android.widget.Toast.makeText(context, "تم اختيار نغمة: " + toneNames[next].replace(" ▼", ""), android.widget.Toast.LENGTH_SHORT).show()
            }`;

const newToneClick = `            setOnClickListener {
                val cur = radarPrefs.getInt("customer_tone_idx", 0)
                val next = (cur + 1) % toneNames.size
                if (next == 5) {
                    val pIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra("action", "pick_ringtone")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(pIntent)
                } else {
                    radarPrefs.edit().putInt("customer_tone_idx", next).apply()
                    text = toneNames[next]
                    playCustomerTonePreview(next)
                    android.widget.Toast.makeText(context, "تم اختيار: " + toneNames[next].replace(" ▼", ""), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            setOnLongClickListener {
                val pIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("action", "pick_ringtone")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(pIntent)
                true
            }`;

if (view.includes(oldToneClick)) {
  view = view.replace(oldToneClick, newToneClick);
}

// دعم تشغيل رنة الهاتف عند المعاينة
const oldPreviewEnd = `            try {
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)`;

const newPreviewWithPhoneTone = `            if (idx == 5) {
                val uriStr = radarPrefs.getString("customer_custom_tone_uri", null)
                if (uriStr != null) {
                    try {
                        val rt = RingtoneManager.getRingtone(context, android.net.Uri.parse(uriStr))
                        rt?.play()
                        uiHandler.postDelayed({ try { rt?.stop() } catch (_: Exception) {} }, 1800)
                    } catch (_: Exception) {}
                } else {
                    val pIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra("action", "pick_ringtone")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(pIntent)
                }
                return
            }
            try {
                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)`;

if (view.includes(oldPreviewEnd) && !view.includes("if (idx == 5)")) {
  view = view.replace(oldPreviewEnd, newPreviewWithPhoneTone);
}

const vOpen = (view.match(/\{/g) || []).length;
const vClose = (view.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس RadarOverlayView.kt: { = " + vOpen + " | } = " + vClose);
if (vOpen !== vClose) throw new Error("اختلال أقواس في RadarOverlayView.kt!");
fs.writeFileSync(viewPath, view, "utf8");
console.log("✅ 2/3: تم تحديث RadarOverlayView.kt بالاستيرادات وربط Intent.");

// 3. ترقية FloatingBubbleService.kt
const fbsPath = "app/src/main/java/com/dzradar/companion/FloatingBubbleService.kt";
let fbs = fs.readFileSync(fbsPath, "utf8");

if (!fbs.includes("customer_custom_tone_uri")) {
  fbs = fbs.replace(
    'if (toneIdx == 4) return',
    `if (toneIdx == 4) return
        if (toneIdx == 5) {
            val uriStr = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE).getString("customer_custom_tone_uri", null)
            if (uriStr != null) {
                try {
                    val rt = android.media.RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))
                    rt?.play()
                    handler.postDelayed({ try { rt?.stop() } catch (_: Exception) {} }, 2500)
                } catch (_: Exception) {}
            }
            return
        }`
  );

  const fbOpen = (fbs.match(/\{/g) || []).length;
  const fbClose = (fbs.match(/\}/g) || []).length;
  console.log("🔍 توازن أقواس FloatingBubbleService.kt: { = " + fbOpen + " | } = " + fbClose);
  if (fbOpen !== fbClose) throw new Error("اختلال أقواس في FloatingBubbleService.kt!");
  fs.writeFileSync(fbsPath, fbs, "utf8");
  console.log("✅ 3/3: تم تحديث FloatingBubbleService.kt لتشغيل رنة الهاتف عند الانتقال.");
}

execSync("git add -A", { stdio: "inherit" });
try {
  execSync('git commit -m "fix(radar-companion): explicit Intent imports and SDK 34 safe ringtone resolution"', { stdio: "inherit" });
} catch (e) {}
execSync("git push origin main", { stdio: "inherit" });
console.log("🚀 تم دفع التحديث النظيف إلى GitHub Actions بنجاح!");
