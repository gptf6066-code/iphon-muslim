package tj.bikhon.iphonmuslim

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Пардаҳо тавассути Accessibility кашида мешаванд — Android ба онҳо
 * иҷозат медиҳад, ки пурра торик бошанд (пардаи оддӣ ҳамеша нимшаффоф мешавад).
 */
class FilterAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: FilterAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.contains(ctx.packageName + "/", ignoreCase = true) &&
                list.contains("FilterAccessibilityService", ignoreCase = true)
        }
    }

    var overlay: OverlayView? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = OverlayView(this)
        try {
            wm.addView(v, lp)
            overlay = v
        } catch (_: Exception) {}
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        overlay?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        super.onDestroy()
    }
}
