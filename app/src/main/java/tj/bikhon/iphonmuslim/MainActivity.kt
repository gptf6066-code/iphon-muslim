package tj.bikhon.iphonmuslim

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var mainBtn: Button
    private lateinit var fastBtn: Button
    private lateinit var help: TextView
    private lateinit var helpBtn: Button
    private var triedEnable = false

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val i = Intent(this, ScreenFilterService::class.java).apply {
                    action = ScreenFilterService.ACTION_START
                    putExtra(ScreenFilterService.EXTRA_CODE, result.resultCode)
                    putExtra(ScreenFilterService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, i)
                status.postDelayed({ refresh() }, 600)
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val green = Color.parseColor("#0E7A4F")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "iPhon Muslim"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(green)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Дар ҳама ҷо — YouTube, Instagram, Google, Галерея — танҳо ҷои занҳоро тира мекунад."
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
            text = "Иҷозати маҳдудро кушодан"
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
        root.addView(SwitchCompat(this).apply {
            text = "Баданро ҳам тира кунад (на танҳо сар)"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.COVER_BODY, true)
            setPadding(0, pad, 0, 0)
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean(Prefs.COVER_BODY, v).apply()
                Engine.coverBody = v
                Engine.reset()
            }
        })
        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
            text = "Ҳамааш дар худи телефон кор мекунад, интернет лозим нест ва ҳеҷ чиз фиристода намешавад. " +
                "Апп занро аз рӯяш мешиносад; гоҳе хато шуданаш мумкин аст."
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
    }

    private fun refresh() {
        val enabled = FilterAccessibilityService.isEnabled(this)
        val paused = prefs.getBoolean(Prefs.PAUSED, false)
        val modern = Build.VERSION.SDK_INT >= 30
        help.visibility = View.GONE
        helpBtn.visibility = View.GONE
        fastBtn.visibility = View.GONE

        if (!enabled) {
            status.text = "⛔ Хомӯш аст"
            mainBtn.text = "Фаъол кардан"
            help.visibility = View.VISIBLE
            help.text = "Як бор: дар саҳифае, ки кушода мешавад, «iPhon Muslim»-ро ёбед ва фаъол кунед " +
                "(Специальные возможности → Скачанные службы → iPhon Muslim → Вкл)."
            if (triedEnable && Build.VERSION.SDK_INT >= 33) {
                help.text = help.text.toString() + "\n\nАгар фаъол нашуд ва навишт «Ограниченная настройка»: " +
                    "тугмаи поёнро пахш кунед → ⋮ (дар боло) → «Разрешить ограниченные настройки», баъд аз нав."
                helpBtn.visibility = View.VISIBLE
            }
            return
        }
        if (modern) {
            if (paused) {
                status.text = "⏸ Таваққуф"
                mainBtn.text = "Идома додан"
            } else {
                status.text = if (ScreenFilterService.running) "✅ Фаъол — ҳолати тез ⚡" else "✅ Фаъол аст"
                mainBtn.text = "Таваққуф"
            }
            if (!paused) {
                fastBtn.visibility = View.VISIBLE
                fastBtn.text = if (ScreenFilterService.running) "Ҳолати тезро хомӯш кардан"
                else "⚡ Ҳолати тез (боз ҳам тезтар; ҳар бор як тасдиқ мехоҳад)"
            }
        } else {
            // Android 10 ва пасттар: экран танҳо тавассути сабти экран дида мешавад
            status.text = if (ScreenFilterService.running) "✅ Фаъол аст" else "Қадами охир: оғоз"
            mainBtn.text = if (ScreenFilterService.running) "Хомӯш кардан" else "Оғоз"
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
            refresh()
        } else {
            if (ScreenFilterService.running) stopFast() else startCapture()
        }
    }

    private fun onFast() {
        if (ScreenFilterService.running) stopFast() else startCapture()
    }

    private fun stopFast() {
        startService(Intent(this, ScreenFilterService::class.java).setAction(ScreenFilterService.ACTION_STOP))
        status.postDelayed({ refresh() }, 400)
    }

    private fun startCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else mpm.createScreenCaptureIntent()
        captureLauncher.launch(intent)
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
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "«iPhon Muslim»-ро фаъол кунед", Toast.LENGTH_LONG).show()
    }
}

object Prefs {
    const val NAME = "settings"
    const val COVER_BODY = "cover_body"
    const val PAUSED = "paused"
}
