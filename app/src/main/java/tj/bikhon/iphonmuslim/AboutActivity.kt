package tj.bikhon.iphonmuslim

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Маълумот дар бораи барнома: ҳадаф, сохтаи маркази БИХОН (бо логотип) */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (22 * d).toInt()
        val green = Color.parseColor("#0E7A4F")
        val blue = Color.parseColor("#1565C0")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 28f
            setTypeface(typeface, Typeface.BOLD); setTextColor(green); gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.about_purpose); textSize = 17f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        })
        // логотипи БИХОН: агар файли logo_bikhon дар res/drawable бошад — ҳамон; вагарна нишони матнӣ
        val logoId = resources.getIdentifier("logo_bikhon", "drawable", packageName)
        if (logoId != 0) {
            root.addView(ImageView(this).apply {
                setImageResource(logoId)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad }
            })
        } else {
            root.addView(TextView(this).apply {
                text = "БИХОН"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(blue) }
                setPadding(pad, pad / 2, pad, pad / 2)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad }
            })
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.about_made_by); textSize = 16f; setTextColor(blue)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.about_private); textSize = 14f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.about_credits); textSize = 12f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.privacy_link); textSize = 15f; setTextColor(blue); gravity = Gravity.CENTER
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, pad / 2, 0, pad)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
        })
        // тугмаҳо ҳамеша дар поён намоён
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 3, pad, pad / 3) }
        bottom.addView(Button(this).apply { isAllCaps = false;
            text = getString(R.string.btn_continue); textSize = 18f
            setOnClickListener {
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(Prefs.ABOUT_SEEN, true).apply()
                finish()
            }
        })
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@AboutActivity).apply { addView(root) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom)
        }
        Insets.pad(page)
        setContentView(page)
    }

    companion object {
        const val PRIVACY_URL = "https://github.com/gptf6066-code/iphon-muslim/blob/main/PRIVACY.md"
    }
}
