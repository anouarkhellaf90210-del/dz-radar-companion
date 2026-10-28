const fs = require("fs");
const { execSync } = require("child_process");

console.log("====================================================");
console.log("🔽 تفعيل القائمة المنسدلة الحقيقية لنغمات كبسولة الزبون...");
console.log("====================================================");

// 1. ترقية MainActivity.kt
const mPath = "app/src/main/java/com/dzradar/companion/MainActivity.kt";
let mCode = fs.readFileSync(mPath, "utf8");

if (!mCode.includes("import android.media.RingtoneManager")) {
  mCode = mCode.replace(
    "import android.content.Context",
    "import android.content.Context\nimport android.media.RingtoneManager"
  );
}

if (!mCode.includes("RINGTONE_REQ_CODE")) {
  const pickerCode = `
    private val RINGTONE_REQ_CODE = 7788

    private fun launchSystemRingtonePicker() {
        val currentUriStr = getSharedPreferences("dz_radar_prefs", Context.MODE_PRIVATE)
            .getString("customer_custom_tone_uri", null)
        val currentUri = if (currentUriStr != null) Uri.parse(currentUriStr) else Settings.System.DEFAULT_NOTIFICATION_URI

        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة تنبيهات الرادار من الهاتف 📱")
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
                val title = ringtone?.getTitle(this) ?: "نغمة الهاتف"
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

  mCode = mCode.replace(
    "class MainActivity : Activity() {",
    "class MainActivity : Activity() {\n" + pickerCode
  );

  mCode = mCode.replace(
    "override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)",
    `override fun onCreate(savedInstanceState: Bundle?) {
        if (intent?.getStringExtra("action") == "pick_ringtone") {
            super.onCreate(savedInstanceState)
            launchSystemRingtonePicker()
            return
        }
        super.onCreate(savedInstanceState)`
  );
}

const mOpen = (mCode.match(/\{/g) || []).length;
const mClose = (mCode.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس MainActivity.kt: { = " + mOpen + " | } = " + mClose);
if (mOpen !== mClose) throw new Error("اختلال أقواس في MainActivity.kt!");
fs.writeFileSync(mPath, mCode, "utf8");
console.log("✅ 1/3: تم ضبط MainActivity.kt بنجاح.");

// 2. ترقية RadarOverlayView.kt بالقائمة المنسدلة الحقيقية
const vPath = "app/src/main/java/com/dzradar/companion/RadarOverlayView.kt";
let vCode = fs.readFileSync(vPath, "utf8");

if (!vCode.includes("import android.content.Intent")) {
  vCode = vCode.replace(
    "import android.content.Context",
    "import android.content.Context\nimport android.content.Intent\nimport android.media.RingtoneManager"
  );
}

const startMarker = '        actionsRow.addView(openStoreBtn)\n        actionsRow.addView(hideStandbyBtn)\n        cardContainer.addView(actionsRow)';
const endMarker = '        sentinelPixelView = View(context).apply {';

const startIndex = vCode.indexOf(startMarker);
const endIndex = vCode.indexOf(endMarker);

if (startIndex === -1 || endIndex === -1) {
  throw new Error("لم يتم العثور على محددات البداية والنهاية في RadarOverlayView.kt!");
}

const dropdownImplementation = `        actionsRow.addView(openStoreBtn)
        actionsRow.addView(hideStandbyBtn)
        cardContainer.addView(actionsRow)

        val toneOptionTitles = listOf(
            "💰 الكاشير الذهبي (Cha-Ching)",
            "✨ كريستال السكينة (Crystal)",
            "📡 نبض الرادار (Sonar Pulse)",
            "👑 الجرس الملكي (Royal Bell)",
            "📳 اهتزاز ميكانيكي فقط",
            "📱 نغمة مخصصة من هاتفي... 🎶"
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

        var isDropdownOpen = false
        val toneDropdownMenu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121215"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#F59E0B"))
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }

        fun getToneDisplayLabel(idx: Int): String {
            return when (idx) {
                0 -> "🎵 الكاشير 💰"
                1 -> "🎵 كريستال ✨"
                2 -> "🎵 نبض الرادار 📡"
                3 -> "🎵 الجرس الملكي 👑"
                4 -> "📳 اهتزاز فقط"
                5 -> "📱 " + radarPrefs.getString("customer_custom_tone_name", "نغمة الهاتف")
                else -> "🎵 الكاشير 💰"
            }
        }

        val initialIdx = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, 5)
        val toneSelectBtn = TextView(context).apply {
            text = getToneDisplayLabel(initialIdx) + " ▼"
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
                val cur = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, 5)
                playCustomerTonePreview(cur)
            }
        }

        fun buildDropdownItems() {
            toneDropdownMenu.removeAllViews()
            val activeIdx = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, 5)

            val header = TextView(context).apply {
                text = "اختر نغمة التنبيه للرادار:"
                setTextColor(Color.parseColor("#A1A1AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(8), dp(4), dp(8), dp(6))
            }
            toneDropdownMenu.addView(header)

            for (i in 0 until toneOptionTitles.size) {
                val isSelected = i == activeIdx
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(if (isSelected) Color.parseColor("#27272A") else Color.TRANSPARENT)
                    }
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        isDropdownOpen = false
                        toneDropdownMenu.visibility = View.GONE
                        if (i == 5) {
                            toneSelectBtn.text = getToneDisplayLabel(5) + " ▼"
                            val pIntent = Intent(context, MainActivity::class.java).apply {
                                putExtra("action", "pick_ringtone")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(pIntent)
                        } else {
                            radarPrefs.edit().putInt("customer_tone_idx", i).apply()
                            toneSelectBtn.text = getToneDisplayLabel(i) + " ▼"
                            playCustomerTonePreview(i)
                            android.widget.Toast.makeText(context, "تم تفعيل " + getToneDisplayLabel(i), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val titleTv = TextView(context).apply {
                    text = if (i == 5 && radarPrefs.getString("customer_custom_tone_name", null) != null) {
                        "📱 " + radarPrefs.getString("customer_custom_tone_name", "") + " (من الهاتف)"
                    } else {
                        toneOptionTitles[i]
                    }
                    setTextColor(if (isSelected) Color.parseColor("#FBBF24") else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val checkTv = TextView(context).apply {
                    text = if (isSelected) "✓" else ""
                    setTextColor(Color.parseColor("#34D399"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                }

                itemRow.addView(titleTv)
                itemRow.addView(checkTv)
                toneDropdownMenu.addView(itemRow)
            }
        }

        toneSelectBtn.setOnClickListener {
            isDropdownOpen = !isDropdownOpen
            if (isDropdownOpen) {
                buildDropdownItems()
                toneDropdownMenu.visibility = View.VISIBLE
                val curIdx = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, 5)
                toneSelectBtn.text = getToneDisplayLabel(curIdx) + " ▲"
            } else {
                toneDropdownMenu.visibility = View.GONE
                val curIdx = radarPrefs.getInt("customer_tone_idx", 0).coerceIn(0, 5)
                toneSelectBtn.text = getToneDisplayLabel(curIdx) + " ▼"
            }
        }

        toneRow.addView(toneSelectBtn)
        toneRow.addView(testToneBtn)
        cardContainer.addView(toneRow)
        cardContainer.addView(toneDropdownMenu)
\n`;

vCode = vCode.substring(0, startIndex) + dropdownImplementation + vCode.substring(endIndex);

const vOpen = (vCode.match(/\{/g) || []).length;
const vClose = (vCode.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس RadarOverlayView.kt: { = " + vOpen + " | } = " + vClose);
if (vOpen !== vClose) throw new Error("اختلال أقواس في RadarOverlayView.kt!");
fs.writeFileSync(vPath, vCode, "utf8");
console.log("✅ 2/3: تم بناء القائمة المنسدلة الحقيقية في RadarOverlayView.kt.");

// 3. ترقية FloatingBubbleService.kt
const fbPath = "app/src/main/java/com/dzradar/companion/FloatingBubbleService.kt";
let fbCode = fs.readFileSync(fbPath, "utf8");

if (!fbCode.includes("customer_custom_tone_uri")) {
  fbCode = fbCode.replace(
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
  fs.writeFileSync(fbPath, fbCode, "utf8");
}

const fbOpen = (fbCode.match(/\{/g) || []).length;
const fbClose = (fbCode.match(/\}/g) || []).length;
console.log("🔍 توازن أقواس FloatingBubbleService.kt: { = " + fbOpen + " | } = " + fbClose);
if (fbOpen !== fbClose) throw new Error("اختلال أقواس في FloatingBubbleService.kt!");
console.log("✅ 3/3: تم تحديث FloatingBubbleService.kt لتشغيل رنة الهاتف المختارة.");

execSync("git add -A", { stdio: "inherit" });
try {
  execSync('git commit -m "feat(radar-companion): add true dropdown tone selector with visual checkmarks and system ringtone picker"', { stdio: "inherit" });
} catch (e) {}
execSync("git push origin main", { stdio: "inherit" });
console.log("🚀 تم دفع التحديث بالقائمة المنسدلة إلى GitHub بنجاح!");
