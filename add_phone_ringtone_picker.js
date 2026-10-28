const fs = require("fs");
const { execSync } = require("child_process");

console.log("====================================================");
console.log("📱 دمج منتقي رنات الهاتف الرسمي لكبسولة الزبون...");
console.log("====================================================");

// 1. ترقية MainActivity.kt لاستدعاء منتقي رنات أندرويد واستقبال الرنة المختارة
const mainActPath = "app/src/main/java/com/dzradar/companion/MainActivity.kt";
let mainAct = fs.readFileSync(mainActPath, "utf8");

if (!mainAct.includes("RINGTONE_REQ_CODE")) {
  const ringtonePickerHandler = `
    private val RINGTONE_REQ_CODE = 7788

    private fun launchSystemRingtonePicker() {
        val currentUriStr = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("customer_custom_tone_uri", null)
        val currentUri = if (currentUriStr != null) android.net.Uri.parse(currentUriStr) else android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_RINGTONE)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة التنبيه للرادار من هاتفك 📱")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        startActivityForResult(intent, RINGTONE_REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_REQ_CODE && resultCode == Activity.RESULT_OK) {
            val uri = data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val ringtone = android.media.RingtoneManager.getRingtone(this, uri)
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

  mainAct = mainAct.replace(
    "class MainActivity : Activity() {",
    "class MainActivity : Activity() {\n" + ringtonePickerHandler
  );

  mainAct = mainAct.replace(
    "override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)",
    `override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.getStringExtra("action") == "pick_ringtone") {
            super.onCreate(savedInstanceState)
            launchSystemRingtonePicker()
            return
        }
        super.onCreate(savedInstanceState)`
  );

  const mOpen = (mainAct.match(/\{/g) || []).length;
  const mClose = (mainAct.match(/\}/g) || []).length;
  console.log("🔍 توازن أقواس MainActivity.kt: { = " + mOpen + " | } = " + mClose);
  if (mOpen !== mClose) throw new Error("اختلال أقواس في MainActivity.kt!");
  fs.writeFileSync(mainActPath, mainAct, "utf8");
  console.log("✅ 1/3: تم تحديث MainActivity.kt بمنتقي رنات الهاتف.");
}

// 2. ترقية RadarOverlayView.kt لعرض خيار رنة الهاتف وزر التجربة والفتح بنقرة واحدة
const rViewPath = "app/src/main/java/com/dzradar/companion/RadarOverlayView.kt";
let rView = fs.readFileSync(rViewPath, "utf8");

// استبدال خيارات النغمات لتدعم الخيار السادس (نغمة الهاتف) وتشغيلها عند التجربة
const oldToneBlockMatch = /val toneNames = listOf\([\s\S]*?toneRow\.addView\(testToneBtn\)\s*cardContainer\.addView\(toneRow\)/;
if (oldToneBlockMatch.test(rView)) {
  const newToneBlock = `val toneNames = listOf(
            "🎵 الكاشير 💰 ▼",
            "🎵 كريستال ✨ ▼",
            "🎵 نبض الرادار 📡 ▼",
            "🎵 الجرس الملكي 👑 ▼",
            "🎵 اهتزاز فقط 📳 ▼",
            "📱 نغمة من الهاتف... 🎶"
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
            if (idx == 5) {
                val uriStr = radarPrefs.getString("customer_custom_tone_uri", null)
                if (uriStr != null) {
                    try {
                        val rt = android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uriStr))
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

        val savedToneIdx = radarPrefs.getInt("customer_tone_idx", 0)
        val initialLabel = if (savedToneIdx == 5) {
            "📱 " + radarPrefs.getString("customer_custom_tone_name", "نغمة الهاتف") + " ▼"
        } else {
            toneNames.getOrElse(savedToneIdx) { toneNames[0] }
        }

        val toneSelectBtn = TextView(context).apply {
            text = initialLabel
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.25f).apply {
                leftMargin = dp(6)
            }
            setOnClickListener {
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
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.95f)
            setOnClickListener {
                val cur = radarPrefs.getInt("customer_tone_idx", 0)
                playCustomerTonePreview(cur)
            }
        }

        toneRow.addView(toneSelectBtn)
        toneRow.addView(testToneBtn)
        cardContainer.addView(toneRow)`;

  rView = rView.replace(oldToneBlockMatch, newToneBlock);
  const rvOpen = (rView.match(/\{/g) || []).length;
  const rvClose = (rView.match(/\}/g) || []).length;
  console.log("🔍 توازن أقواس RadarOverlayView.kt: { = " + rvOpen + " | } = " + rvClose);
  if (rvOpen !== rvClose) throw new Error("اختلال أقواس في RadarOverlayView.kt!");
  fs.writeFileSync(rViewPath, rView, "utf8");
  console.log("✅ 2/3: تم تحديث RadarOverlayView.kt بزر رنة الهاتف وزر التجربة.");
}

// 3. ترقية FloatingBubbleService.kt لتشغيل رنة الهاتف المختارة عند انتقال المحطة
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
  console.log("✅ 3/3: تم تحديث FloatingBubbleService.kt لتشغيل رنة الهاتف المختارة عند انتقال المحطات.");
}

execSync("git add -A", { stdio: "inherit" });
try {
  execSync('git commit -m "feat(radar-companion): add system ringtone picker support from device storage with live preview"', { stdio: "inherit" });
} catch (e) {}
execSync("git push origin main", { stdio: "inherit" });
console.log("🚀 تم دفع التحديث إلى GitHub Actions لبناء نسخة الزبون الجديدة بنجاح!");
