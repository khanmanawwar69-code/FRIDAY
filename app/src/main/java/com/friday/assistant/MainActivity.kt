package com.friday.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var pinField: EditText
    private lateinit var patternField: EditText
    private lateinit var testField: EditText
    private lateinit var prefs: SharedPreferences
    private val cyan = Color.parseColor("#00E5FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("friday", MODE_PRIVATE)
        val bg = Color.parseColor("#050B14")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "F.R.I.D.A.Y."
            setTextColor(cyan)
            textSize = 28f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(status)

        fun btn(t: String, f: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            return b
        }

        root.addView(btn("1. SETUP (agla step)") { nextStep() })

        keyField = EditText(this).apply {
            hint = "Claude API key (sk-ant-...)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        root.addView(keyField)

        pinField = EditText(this).apply {
            hint = "Phone PIN (unlock ke liye, optional)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setText(prefs.getString("pin", ""))
        }
        root.addView(pinField)

        patternField = EditText(this).apply {
            hint = "Pattern (1-9 numbers, jaise 14789) - pattern lock ho to"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("pattern", ""))
        }
        root.addView(patternField)

        root.addView(btn("2. SAVE") {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(btn("3. START FRIDAY") {
            save()
            if (missingPerms().isNotEmpty()) {
                nextStep()
            } else {
                FridayService.start(this)
                status.postDelayed({ refresh() }, 800)
            }
        })

        root.addView(btn("STOP FRIDAY") {
            FridayService.stop(this)
            status.postDelayed({ refresh() }, 500)
        })

        testField = EditText(this).apply {
            hint = "Test command likho..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        root.addView(testField)
        root.addView(btn("SEND TEST") {
            val t = testField.text.toString().trim()
            if (t.isNotEmpty()) {
                save()
                testField.setText("")
                FridayService.start(this, t)
            }
        })

        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun save() {
        prefs.edit()
            .putString("key", keyField.text.toString().trim())
            .putString("pin", pinField.text.toString().trim())
            .putString("pattern", patternField.text.toString().trim())
            .apply()
    }

    private fun missingPerms(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.filter { !granted(it) }
    }

    private fun refresh() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val rows = listOf(
            "Mic / Call / Contacts" to missingPerms().isEmpty(),
            "Display over other apps" to Settings.canDrawOverlays(this),
            "Battery unrestricted" to pm.isIgnoringBatteryOptimizations(packageName),
            "Accessibility ON" to (FridayAccess.instance != null),
            "Friday sun raha hai" to FridayService.running
        )
        status.text = rows.joinToString("\n") { (if (it.second) "[OK] " else "[ ] ") + it.first }
    }

    private fun nextStep() {
        val miss = missingPerms()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        when {
            miss.isNotEmpty() -> requestPermissions(miss.toTypedArray(), 1)
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "Friday ke saamne switch ON karo", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
            !pm.isIgnoringBatteryOptimizations(packageName) ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            FridayAccess.instance == null -> {
                Toast.makeText(this, "Installed apps me Friday dhoondho aur ON karo", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            else -> Toast.makeText(this, "Sab ready! Ab START FRIDAY dabao", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
    }
}
