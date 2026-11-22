package tj.bikhon.iphonmuslim

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Равзанаи шаффоф: танҳо тасдиқи «сабти экран»-ро мепурсад ва ҳолати тезро оғоз мекунад.
 * Онро ҳам аз апп ва ҳам худкор баъд аз фурӯзон шудани телефон кушодан мумкин аст.
 */
class CaptureActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val i = Intent(this, ScreenFilterService::class.java).apply {
                    action = ScreenFilterService.ACTION_START
                    putExtra(ScreenFilterService.EXTRA_CODE, result.resultCode)
                    putExtra(ScreenFilterService.EXTRA_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, i)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenFilterService.running) { finish(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else mpm.createScreenCaptureIntent()
        try { launcher.launch(intent) } catch (e: Exception) { finish() }
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
