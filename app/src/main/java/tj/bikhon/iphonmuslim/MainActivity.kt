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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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

    /** Баъд аз «Розӣ ҳастам» — ба танзимоти Специальные возможности */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) openAccessibility()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val localeJustSet = L10n.ensureDefault()
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val green = Color.parseColor("#0E7A4F")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        // --- сатри боло: ⓘ  номи барнома  🌐 ---
        val bar = FrameLayout(this)
        bar.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 26f
            setTypeface(typeface, Typeface.BOLD); setTextColor(green); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        bar.addView(iconButton("ⓘ") { startActivity(Intent(this, AboutActivity::class.java)) },
            FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(iconButton("🌐") { chooseLanguage() },
            FrameLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = getString(R.string.tagline); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad)
        })

        // --- ман мард/зан ---
        root.addView(title(getString(R.string.i_am), pad))
        val target = prefs.getString(Prefs.TARGET, Prefs.TARGET_WOMEN)
        val rMan = radio(getString(R.string.i_am_man))
        val rWoman = radio(getString(R.string.i_am_woman))
        root.addView(RadioGroup(this).apply {
            addView(rMan); addView(rWoman)
            check(if (target == Prefs.TARGET_MEN) rWoman.id else rMan.id)
            setOnCheckedChangeListener { _, id ->
                val t = if (id == rWoman.id) Prefs.TARGET_MEN else Prefs.TARGET_WOMEN
                prefs.edit().putString(Prefs.TARGET, t).apply()
                Engine.hideWomen = t != Prefs.TARGET_MEN
                Engine.reset()
            }
            setPadding(0, 0, 0, pad / 2)
        })

        status = TextView(this).apply { textSize = 19f; gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        mainBtn = Button(this).apply { isAllCaps = false; textSize = 20f; setOnClickListener { onMain() } }
        root.addView(mainBtn)
        help = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 0) }
        root.addView(help)
        helpBtn = Button(this).apply { isAllCaps = false;
            text = getString(R.string.btn_restricted)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        root.addView(helpBtn)
        fastBtn = Button(this).apply { isAllCaps = false; textSize = 16f; setOnClickListener { onFast() } }
        root.addView(fastBtn)
        fastHint = TextView(this).apply { text = getString(R.string.fast_hint); textSize = 13f; setTextColor(Color.GRAY) }
        root.addView(fastHint)

        // --- намуди хира кардан ---
        root.addView(title(getString(R.string.mode_title), pad))
        val modeNow = prefs.getString(Prefs.MODE, Prefs.MODE_PHOTO)
        val rPhoto = radio(getString(R.string.mode_photo))
        val rPerson = radio(getString(R.string.mode_person))
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
        root.addView(SwitchCompat(this).apply {
            text = getString(R.string.scroll_guard); textSize = 16f
            isChecked = prefs.getBoolean(Prefs.SCROLL_GUARD, true)
            setPadding(0, pad / 2, 0, 0)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(Prefs.SCROLL_GUARD, v).apply() }
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.note); textSize = 13f; setTextColor(Color.GRAY)
            setPadding(0, pad, 0, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.privacy_link); textSize = 14f; setTextColor(Color.parseColor("#1565C0"))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.PRIVACY_URL))) }
        })
        setContentView(ScrollView(this).apply { addView(root); Insets.pad(this) })

        // агар забон ҳозир гузошта шуд, экран аз нав сохта мешавад — «Дар бораи барнома» баъд бо забони дуруст кушода мешавад
        if (!localeJustSet && !prefs.getBoolean(Prefs.ABOUT_SEEN, false) && !aboutOpened) {
            aboutOpened = true   // ҳангоми иваз шудани забон экран аз нав сохта мешавад — як бор кифоя
            startActivity(Intent(this, AboutActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun iconButton(t: String, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = 24f; gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#0E7A4F"))
        setOnClickListener { click() }
    }

    private fun title(t: String, pad: Int) = TextView(this).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 2, 0, pad / 4)
    }

    private fun radio(t: String) = RadioButton(this).apply { isAllCaps = false; id = View.generateViewId(); textSize = 16f; text = t }

    private fun chooseLanguage() {
        val names = L10n.LANGS.map { it.second }.toTypedArray()
        val cur = L10n.LANGS.indexOfFirst { it.first == L10n.current() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_title))
            .setSingleChoiceItems(names, cur) { dlg, which ->
                dlg.dismiss()
                val tag = L10n.LANGS[which].first
                if (tag != L10n.current()) L10n.set(tag)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        status.postDelayed({ refresh() }, 1500)
    }

    private fun refresh() {
        val enabled = FilterAccessibilityService.isEnabled(this) && prefs.getBoolean(Prefs.CONSENT, false)
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
            status.text = getString(if (ScreenFilterService.running) R.string.status_on else R.string.status_last_step)
            mainBtn.text = getString(if (ScreenFilterService.running) R.string.btn_stop else R.string.btn_start)
        }
    }

    private fun onMain() {
        if (!prefs.getBoolean(Prefs.CONSENT, false)) {
            triedEnable = true
            consentLauncher.launch(Intent(this, DisclosureActivity::class.java))
            return
        }
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

    /** Кӯшиш мекунем саҳифаи худи барнома дар Специальные возможности-ро кушоем */
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

private var aboutOpened = false

object Prefs {
    const val NAME = "settings"
    const val PAUSED = "paused"
    const val FAST = "fast"
    const val SCROLL_GUARD = "scroll_guard"
    const val MODE = "mode"
    const val MODE_PHOTO = "photo"
    const val MODE_PERSON = "person"
    const val TARGET = "target"
    const val TARGET_WOMEN = "women"
    const val TARGET_MEN = "men"
    const val CONSENT = "consent"
    const val ABOUT_SEEN = "about_seen"
}
