package tj.bikhon.iphonmuslim

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Забони апп: форсӣ асосӣ; тоҷикӣ, русӣ, англисӣ ихтиёрӣ */
object L10n {
    val LANGS = listOf("fa" to "فارسی", "tg" to "Тоҷикӣ", "ru" to "Русский", "en" to "English")

    fun current(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "fa" else (l[0]?.language ?: "fa")
    }

    /** Бори аввал — форсӣ */
    fun ensureDefault() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set("fa")
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Контекст бо забони апп (барои хизматҳо ва огоҳиномаҳо) */
    fun ctx(base: Context): Context {
        val conf = Configuration(base.resources.configuration)
        conf.setLocale(Locale.forLanguageTag(current()))
        return base.createConfigurationContext(conf)
    }
}
