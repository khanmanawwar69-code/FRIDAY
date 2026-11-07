package com.friday.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var orb: TextView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var torchOn = false
    private var listening = false
    private val cyan = Color.parseColor("#00E5FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#050B14")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(40), dp(20), dp(16))
        }

        val title = TextView(this).apply {
            text = "F.R.I.D.A.Y."
            setTextColor(cyan)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        orb = TextView(this).apply {
            text = "\uD83C\uDF99"
            textSize = 48f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0A2A3A"))
                setStroke(dp(3), cyan)
            }
            setOnClickListener { toggleListening() }
        }
        root.addView(orb, LinearLayout.LayoutParams(dp(150), dp(150)).apply {
            topMargin = dp(28)
            bottomMargin = dp(12)
        })

        status = TextView(this).apply {
            text = "Tap karo aur bolo"
            setTextColor(Color.parseColor("#7FDBFF"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(status)

        log = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12) })

        input = EditText(this).apply {
            hint = "Type command..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        val send = Button(this).apply {
            text = "SEND"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    input.setText("")
                    handle(t)
                }
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send)
        root.addView(row)

        setContentView(root)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "IN")
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val g = when {
                h < 12 -> "Good morning"
                h < 17 -> "Good afternoon"
                else -> "Good evening"
            }
            say("$g boss. Friday online. Kya karna hai?")
        }
    }

    // ---------- Voice ----------
    private fun toggleListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (listening) {
            recognizer?.stopListening()
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            say("Mic permission chahiye boss.")
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Speech recognition is phone me available nahi hai.")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    listening = true
                    status.text = "Listening..."
                    orb.alpha = 1f
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rms: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listening = false
                    status.text = "Processing..."
                }
                override fun onError(e: Int) {
                    listening = false
                    status.text = "Tap karo aur bolo"
                }
                override fun onResults(r: Bundle?) {
                    listening = false
                    status.text = "Tap karo aur bolo"
                    val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!list.isNullOrEmpty()) handle(list[0])
                }
                override fun onPartialResults(p: Bundle?) {
                    val l = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!l.isNullOrEmpty()) status.text = l[0]
                }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer!!.startListening(i)
    }

    // ---------- Brain ----------
    private fun has(c: String, vararg k: String) = k.any { c.contains(it) }

    private fun handle(raw: String) {
        addLog("YOU: $raw")
        var c = raw.lowercase(Locale.ROOT).trim()
        c = c.removePrefix("friday").trim().trimStart(',').trim()

        when {
            c.isEmpty() -> say("Yes boss?")
            has(c, "volume up", "awaaz badhao", "volume badhao") -> volume(true)
            has(c, "volume down", "awaaz kam", "volume kam") -> volume(false)
            has(c, "flashlight", "torch") -> torch(!has(c, "off", "band"))
            has(c, "time", "samay", "kitne baje") ->
                say("Abhi " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()) + " ho rahe hain.")
            has(c, "date", "tarikh", "aaj kya din") ->
                say("Aaj " + SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date()) + " hai.")
            has(c, "battery") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("Battery $p percent hai.")
            }
            c.startsWith("call ") || c.startsWith("dial ") -> {
                val num = c.filter { it.isDigit() || it == '+' }
                if (num.length >= 3) {
                    say("Dialing $num")
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                } else {
                    say("Number bolo boss, contact name abhi support nahi hai.")
                }
            }
            c.startsWith("open ") || has(c, "kholo", "khol do") -> {
                val name = c.removePrefix("open ").replace("kholo", "").replace("khol do", "").trim()
                openApp(name)
            }
            c.startsWith("youtube ") || c.startsWith("play ") -> {
                val q = c.removePrefix("youtube ").removePrefix("play ").trim()
                say("YouTube par $q chala raha hoon.")
                web("https://www.youtube.com/results?search_query=" + Uri.encode(q))
            }
            c.startsWith("search ") || c.startsWith("google ") -> {
                val q = c.removePrefix("search ").removePrefix("google ").trim()
                say("$q search kar raha hoon.")
                web("https://www.google.com/search?q=" + Uri.encode(q))
            }
            has(c, "joke", "mazak", "chutkula") -> say(
                listOf(
                    "Programmer ko doctor ne kaha: aap bahut bug me hain. Usne kaha: feature hai.",
                    "Mera WiFi aur meri motivation, dono weak signal par hain.",
                    "Boss, main AI hoon, mujhe chai ki zaroorat nahi, lekin aapko hai."
                ).random()
            )
            has(c, "who are you", "tum kaun", "tera naam", "aap kaun") ->
                say("Main Friday hoon, aapka personal AI assistant.")
            c == "hi" || has(c, "hello", "namaste", "hey") -> say("Hello boss, kaise help karun?")
            has(c, "thank", "shukriya", "dhanyavaad") -> say("Anytime boss.")
            else -> {
                say("Google par dhoondh raha hoon: $c")
                web("https://www.google.com/search?q=" + Uri.encode(c))
            }
        }
    }

    private fun openApp(name: String) {
        if (name.isEmpty()) {
            say("Kaunsa app kholu boss?")
            return
        }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(name)
        }
        val launch = match?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch != null) {
            say("$name khol raha hoon.")
            startActivity(launch)
        } else {
            say("$name app nahi mila.")
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
            torchOn = on
            say(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            say("Flashlight control nahi ho paya.")
        }
    }

    private fun volume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        say(if (up) "Volume badha diya." else "Volume kam kar diya.")
    }

    private fun web(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Browser nahi mila", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Output ----------
    private fun say(t: String) {
        addLog("FRIDAY: $t")
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "friday")
    }

    private fun addLog(t: String) {
        log.append(t + "\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts.shutdown()
        super.onDestroy()
    }
}
