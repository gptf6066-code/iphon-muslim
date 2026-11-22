package tj.bikhon.iphonmuslim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var mainBtn: Button
    private lateinit var fastBtn: Button
    private lateinit var fastHint: TextView
    private lateinit var help: TextView
    private lateinit var helpBtn: Button
    private var triedEnable = false

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.ensureDefault()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val green = Color.parseColor("#0E7A4F")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(green)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.tagline)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })
        status = TextView(this).apply {
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(status)
        mainBtn = Button(this).apply {
            textSize = 20f
            setOnClickListener { onMain() }
        }
        root.addView(mainBtn)
        help = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad / 2, 0, 0)
        }
        root.addView(help)
        helpBtn = Button(this).apply {
            text = getString(R.string.btn_restricted)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        root.addView(helpBtn)
        fastBtn = Button(this).apply {
            textSize = 16f
            setOnClickListener { onFast() }
        }
        root.addView(fastBtn)
        fastHint = TextView(this).apply {
            text = getString(R.string.fast_hint)
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        root.addView(fastHint)

        // --- намуди хира кардан ---
        root.addView(TextView(this).apply {
            text = getString(R.string.mode_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        val modeNow = prefs.getString(Prefs.MODE, Prefs.MODE_PHOTO)
        val rPhoto = RadioButton(this).apply {
            id = View.generateViewId(); textSize = 16f; text = getString(R.string.mode_photo)
        }
        val rPerson = RadioButton(this).apply {
            id = View.generateViewId(); textSize = 16f; text = getString(R.string.mode_person)
        }
        root.addView(RadioGroup(this).apply {
            addView(rPhoto); addView(rPerson)
            check(if (modeNow == Prefs.MODE_PERSON) rPerson.id else rPhoto.id)
            setOnCheckedChangeListener { _, id ->
                val m = if (id == rPerson.id) Prefs.MODE_PERSON else Prefs.MODE_PHOTO
                prefs.edit().putString(Prefs.MODE, m).apply()
                Engine.wholePhoto = m != Prefs.MODE_PERSON
                Engine.reset()
            }
        })

        // --- забон ---
        root.addView(TextView(this).apply {
            text = getString(R.string.lang_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, pad / 4)
        })
        val cur = L10n.current()
        root.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for ((tag, name) in L10n.LANGS) {
                val rb = RadioButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = name; textSize = 15f
                    setPadding(0, 0, pad / 2, 0)
                }
                addView(rb)
                if (tag == cur) check(rb.id)
                rb.setOnClickListener { if (tag != L10n.current()) L10n.set(tag) }
            }
        })

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
            text = getString(R.string.note)
        })
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        status.postDelayed({ refresh() }, 1500)
    }

    private fun refresh() {
        val enabled = FilterAccessibilityService.isEnabled(this)
        val paused = prefs.getBoolean(Prefs.PAUSED, false)
        val modern = Build.VERSION.SDK_INT >= 30
        help.visibility = View.GONE
        helpBtn.visibility = View.GONE
        fastBtn.visibility = View.GONE
        fastHint.visibility = View.GONE

        if (!enabled) {
            status.text = getString(R.string.status_off)
            mainBtn.text = getString(R.string.btn_enable)
            help.visibility = View.VISIBLE
            help.text = getString(R.string.help_enable)
            if (triedEnable && Build.VERSION.SDK_INT >= 33) {
                help.text = help.text.toString() + "\n\n" + getString(R.string.help_restricted)
                helpBtn.visibility = View.VISIBLE
            }
            return
        }
        if (modern) {
            if (paused) {
                status.text = getString(R.string.status_paused)
                mainBtn.text = getString(R.string.btn_resume)
                return
            }
            status.text = getString(if (ScreenFilterService.running) R.string.status_on_fast else R.string.status_on)
            mainBtn.text = getString(R.string.btn_pause)
            fastBtn.visibility = View.VISIBLE
            fastBtn.text = getString(if (ScreenFilterService.running) R.string.btn_fast_off else R.string.btn_fast_on)
            if (!ScreenFilterService.running) fastHint.visibility = View.VISIBLE
        } else {
            // Android 10 ва пасттар: экран танҳо тавассути сабти экран дида мешавад
            status.text = getString(if (ScreenFilterService.running) R.string.status_on else R.string.status_last_step)
            mainBtn.text = getString(if (ScreenFilterService.running) R.string.btn_stop else R.string.btn_start)
        }
    }

    private fun onMain() {
        if (!FilterAccessibilityService.isEnabled(this)) {
            triedEnable = true
            openAccessibility()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val paused = !prefs.getBoolean(Prefs.PAUSED, false)
            prefs.edit().putBoolean(Prefs.PAUSED, paused).apply()
            if (paused && ScreenFilterService.running) stopFast()
            if (!paused && prefs.getBoolean(Prefs.FAST, true) && !ScreenFilterService.running) CaptureActivity.start(this)
            refresh()
        } else {
            if (ScreenFilterService.running) stopFast() else CaptureActivity.start(this)
        }
    }

    private fun onFast() {
        if (ScreenFilterService.running) {
            prefs.edit().putBoolean(Prefs.FAST, false).apply()
            stopFast()
        } else {
            prefs.edit().putBoolean(Prefs.FAST, true).apply()
            CaptureActivity.start(this)
        }
    }

    private fun stopFast() {
        startService(Intent(this, ScreenFilterService::class.java).setAction(ScreenFilterService.ACTION_STOP))
        status.postDelayed({ refresh() }, 400)
    }

    /** Кӯшиш мекунем саҳифаи худи апп дар Специальные возможности-ро кушоем */
    private fun openAccessibility() {
        val comp = ComponentName(this, FilterAccessibilityService::class.java).flattenToString()
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
        Toast.makeText(this, getString(R.string.toast_enable), Toast.LENGTH_LONG).show()
    }
}

object Prefs {
    const val NAME = "settings"
    const val PAUSED = "paused"
    const val FAST = "fast"
    const val MODE = "mode"
    const val MODE_PHOTO = "photo"
    const val MODE_PERSON = "person"
}
