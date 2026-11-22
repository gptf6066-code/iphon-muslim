package tj.bikhon.iphonmuslim

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15+: экран то канор кашида мешавад — ҷой барои сатри ҳолат ва тугмаҳо */
object Insets {
    fun pad(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }
}
