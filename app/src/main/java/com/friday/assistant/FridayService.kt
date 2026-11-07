package com.friday.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FridayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var running = false
        const val CH = "friday_ch"

        fun start(c: Context, cmd: String? = null) {
            val i = Intent(c, FridayService::class.java)
            if (cmd != null) i.putExtra("cmd", cmd)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, FridayService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var rec: SpeechRecognizer? = null
    private val pending = AtomicInteger(0)

    @Volatile private var speaking = false
    @Volatile private var busy = false
    @Volatile private var listening = false
    @Volatile private var activeUntil = 0L
    @Volatile private var lastEvent = 0L

    private var cpuLock: PowerManager.WakeLock? = null
    private var started = false
    private lateinit var prefs: SharedPreferences
    private val wakeWords = listOf("friday", "fryday", "fri day", "फ्राइडे", "फ्रायडे", "फ्राईडे")

    private fun now() = System.currentTimeMillis()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun canListen() = running && !speaking && !busy

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!started) {
            started = true
            running = true
            prefs = getSharedPreferences("friday", MODE_PRIVATE)
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "friday:cpu")
            cpuLock?.acquire()
            muteBeep(true)
            lastEvent = now()
            tts = TextToSpeech(this, this)
            main.postDelayed(watchdog, 10000)
        }
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) handle(cmd)
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Friday", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, FridayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("Friday sun raha hai")
            .setContentText("Bas \"Friday\" bolo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stopPi
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------- Speech ----------------
    override fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) return
        tts?.language = Locale("en", "IN")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                afterSpeak()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                afterSpeak()
            }
        })
        ttsReady = true
        speak("Friday online hai boss. Bas Friday bolo.")
    }

    private fun afterSpeak() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            speaking = false
            activeUntil = now() + 7000
            main.post { listenLoop() }
        }
    }

    private fun speak(t: String) {
        main.post {
            rec?.cancel()
            listening = false
        }
        if (!ttsReady) return
        pending.incrementAndGet()
        speaking = true
        val r = tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "u" + now())
        if (r != TextToSpeech.SUCCESS) afterSpeak()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {
            lastEvent = now()
        }

        override fun onBeginningOfSpeech() {
            lastEvent = now()
        }

        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(e: Int) {
            listening = false
            lastEvent = now()
            if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_CLIENT) {
                rec?.destroy()
                rec = null
            }
            val delay = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 150L else 1000L
            main.postDelayed({ listenLoop() }, delay)
        }

        override fun onResults(r: Bundle?) {
            listening = false
            lastEvent = now()
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (t.isNullOrBlank()) {
                listenLoop()
                return
            }
            onHeard(t)
        }

        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun listenLoop() {
        if (!canListen() || listening) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
        if (rec == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) return
            rec = SpeechRecognizer.createSpeechRecognizer(this)
            rec!!.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            listening = true
            lastEvent = now()
            rec!!.startListening(i)
        } catch (e: Exception) {
            listening = false
            rec?.destroy()
            rec = null
            main.postDelayed({ listenLoop() }, 1500)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (canListen() && now() - lastEvent > 25000) {
                rec?.cancel()
                listening = false
                listenLoop()
            }
            main.postDelayed(this, 10000)
        }
    }

    private fun onHeard(t: String) {
        val low = t.lowercase(Locale.ROOT)
        val w = wakeWords.firstOrNull { low.contains(it) }
        when {
            w != null -> {
                val cmd = low.substringAfter(w).trim().trimStart(',', '.', ' ')
                if (cmd.isEmpty()) {
                    speak("Haan boss?")
                } else {
                    handle(cmd)
                }
            }
            now() < activeUntil -> handle(low)
            else -> listenLoop()
        }
    }

    // ---------------- Brain + actions ----------------
    private fun handle(cmd: String) {
        busy = true
        main.post {
            rec?.cancel()
            listening = false
        }
        worker.execute {
            try {
                val r = Brain.ask(this, cmd)
                if (r.reply.isNotEmpty()) speak(r.reply)
                perform(r)
            } catch (e: Exception) {
                speak("Kuch gadbad ho gayi boss.")
            } finally {
                busy = false
                main.post { listenLoop() }
            }
        }
    }

    private fun perform(r: Cmd) {
        when (r.action) {
            "call" -> {
                val num = resolveNumber(r.name)
                if (num == null) {
                    speak("Ye contact mujhe nahi mila boss.")
                    return
                }
                if (!granted(Manifest.permission.CALL_PHONE)) {
                    speak("Call permission do boss.")
                    return
                }
                go(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
            }
            "whatsapp" -> {
                val num = resolveNumber(r.name)
                if (num == null) {
                    speak("Ye contact mujhe nahi mila boss.")
                    return
                }
                if (!ensureUnlocked()) return
                val ok = whatsapp(normalize(num), r.text)
                speak(if (ok) "Message bhej diya." else "Send button nahi mila boss, chat khuli hai, aap bhej do.")
            }
            "youtube" -> {
                if (!ensureUnlocked()) return
                youtube(r.query)
            }
            "open_app" -> {
                if (!ensureUnlocked()) return
                openApp(r.app)
            }
            "unlock" -> ensureUnlocked()
            "lock" -> FridayAccess.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "home" -> FridayAccess.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> FridayAccess.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "flashlight_on" -> torch(true)
            "flashlight_off" -> torch(false)
            "volume_up" -> volume(true)
            "volume_down" -> volume(false)
        }
    }

    private fun go(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    @Suppress("DEPRECATION")
    private fun ensureUnlocked(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!pm.isInteractive || km.isKeyguardLocked) {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "friday:wake"
            ).acquire(40000L)
        }
        if (km.isKeyguardLocked) {
            val acc = FridayAccess.instance
            if (acc == null) {
                speak("Accessibility ON karo boss, tabhi unlock kar paunga.")
                return false
            }
            Thread.sleep(900)
            acc.unlock(prefs.getString("pin", "") ?: "", prefs.getString("pattern", "") ?: "")
            repeat(15) {
                if (!km.isKeyguardLocked) return true
                Thread.sleep(400)
            }
            speak("Unlock nahi ho paya boss.")
            return false
        }
        return true
    }

    private fun installed(p: String): Boolean = try {
        packageManager.getPackageInfo(p, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun whatsapp(num: String, text: String): Boolean {
        val pkg = if (installed("com.whatsapp")) "com.whatsapp" else "com.whatsapp.w4b"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(text)))
        i.setPackage(pkg)
        go(i)
        val acc = FridayAccess.instance ?: return false
        Thread.sleep(3500)
        repeat(12) {
            val n = acc.find {
                it.contentDescription?.toString()?.equals("Send", true) == true ||
                    it.viewIdResourceName?.endsWith(":id/send") == true
            }
            if (n != null) {
                acc.click(n)
                return true
            }
            Thread.sleep(700)
        }
        return false
    }

    private fun youtube(q: String) {
        val i = Intent(Intent.ACTION_SEARCH)
        i.setPackage("com.google.android.youtube")
        i.putExtra("query", q)
        try {
            go(i)
        } catch (e: Exception) {
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        }
        val acc = FridayAccess.instance ?: return
        Thread.sleep(4500)
        val rx = Regex("(second|minute|hour)", RegexOption.IGNORE_CASE)
        repeat(8) {
            val n = acc.find {
                val d = it.contentDescription?.toString() ?: ""
                it.isClickable && d.length > 25 && rx.containsMatchIn(d)
            }
            if (n != null) {
                acc.click(n)
                return
            }
            Thread.sleep(700)
        }
    }

    private fun openApp(name: String) {
        val q = name.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val m = packageManager.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(packageManager).toString().lowercase(Locale.ROOT).contains(q)
        }
        val li = m?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (li != null) go(li) else speak("$name app nahi mila boss.")
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
        } catch (e: Exception) {
        }
    }

    private fun volume(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    // ---------------- Contacts ----------------
    private fun resolveNumber(name: String): String? {
        if (name.count { it.isDigit() } >= 6) return name.filter { it.isDigit() || it == '+' }
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val q = name.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        val cur = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        if (cur != null) {
            while (cur.moveToNext()) {
                val n = (cur.getString(0) ?: "").lowercase(Locale.ROOT)
                val num = cur.getString(1) ?: continue
                val score = when {
                    n == q -> 3
                    n.startsWith(q) -> 2
                    n.contains(q) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = num
                }
            }
            cur.close()
        }
        return best
    }

    // India default: 10 digit number ke aage 91
    private fun normalize(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        if (d.length == 11 && d.startsWith("0")) d = "91" + d.substring(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    // Recognizer ki beep band (sirf notification/system stream)
    private fun muteBeep(m: Boolean) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val d = if (m) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, d, 0)
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, d, 0)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        rec?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        muteBeep(false)
        try {
            cpuLock?.release()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
