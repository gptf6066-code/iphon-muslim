package tj.bikhon.iphonmuslim

import android.Manifest
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

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val i = Intent(this, ScreenFilterService::class.java).apply {
                    action = ScreenFilterService.ACTION_START
                    putExtra(ScreenFilterService.EXTRA_CODE, result.resultCode)
                    putExtra(ScreenFilterService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, i)
                statusText.postDelayed({ refresh() }, 600)
            } else {
                Toast.makeText(this, "Иҷозат дода нашуд", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "iPhon Muslim"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0E7A4F"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Аксу видеоҳои одамонро дар экран худкор мепӯшонад — дар YouTube, Instagram, Google, Галерея ва ҳар апп."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(statusText)

        toggleButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { onToggle() }
        }
        root.addView(toggleButton)

        root.addView(SwitchCompat(this).apply {
            text = "Баданро ҳам пӯшонад (на танҳо рӯйро)"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.COVER_BODY, true)
            setPadding(0, pad, 0, 0)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(Prefs.COVER_BODY, v).apply() }
        })
        root.addView(SwitchCompat(this).apply {
            text = "Ҳатто рӯйҳои хурд (сахтгиртар)"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.SMALL_FACES, true)
            setPadding(0, pad / 2, 0, 0)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(Prefs.SMALL_FACES, v).apply() }
        })

        root.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
            text = "Эзоҳ: апп рӯйи одамонро меёбад ва онро бо бадан мепӯшонад. " +
                "Ҳангоми ҳаракати тез (скролл, видео) шояд як лаҳзаи кӯтоҳ намоён шавад. " +
                "Ҳар бор баъд аз хомӯш шудани телефон бояд дубора «Фаъол кардан»-ро пахш кунед."
        })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (ScreenFilterService.running) {
            statusText.text = "✅ Фаъол аст — экран муҳофизат мешавад"
            toggleButton.text = "Хомӯш кардан"
        } else {
            statusText.text = "⛔ Хомӯш аст"
            toggleButton.text = "Фаъол кардан"
        }
    }

    private fun onToggle() {
        if (ScreenFilterService.running) {
            startService(Intent(this, ScreenFilterService::class.java).setAction(ScreenFilterService.ACTION_STOP))
            statusText.postDelayed({ refresh() }, 400)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Иҷозати «Нишон додан болои дигар аппҳо»-ро диҳед", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Тамоми экран — на танҳо як апп
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else mpm.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }
}

object Prefs {
    const val NAME = "settings"
    const val COVER_BODY = "cover_body"
    const val SMALL_FACES = "small_faces"
}
