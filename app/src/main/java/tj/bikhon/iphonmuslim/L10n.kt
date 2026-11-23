package tj.bikhon.iphonmuslim

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Забони барнома: тоҷикӣ асосӣ; форсӣ, русӣ, англисӣ ихтиёрӣ */
object L10n {
    val LANGS = listOf("tg" to "Тоҷикӣ", "fa" to "فارسی", "ru" to "Русский", "en" to "English")

    fun current(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "tg" else (l[0]?.language ?: "tg")
    }

    /** Бори аввал — тоҷикӣ */
    fun ensureDefault() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set("tg")
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
