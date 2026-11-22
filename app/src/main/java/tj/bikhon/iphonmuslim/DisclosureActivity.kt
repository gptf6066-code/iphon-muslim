package tj.bikhon.iphonmuslim

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Огоҳии намоён ва розигӣ (талаби Google Play барои AccessibilityService API).
 * Бе «Розӣ ҳастам» барнома экранро намебинад.
 */
class DisclosureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (22 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.disclosure_title); textSize = 22f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#0E7A4F"))
            setPadding(0, 0, 0, pad / 2)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.disclosure_text); textSize = 16f; setLineSpacing(0f, 1.15f)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.privacy_link); textSize = 15f; setTextColor(Color.parseColor("#1565C0"))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.PRIVACY_URL))) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.btn_agree); textSize = 18f
            setOnClickListener {
                getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(Prefs.CONSENT, true).apply()
                setResult(RESULT_OK); finish()
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.btn_decline)
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        })
        setContentView(ScrollView(this).apply { addView(root); Insets.pad(this) })
    }
}
