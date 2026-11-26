package io.novadeck.control.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleController {
    fun currentLanguageTag(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (!appLocales.isEmpty) {
            val tag = appLocales[0]?.language ?: "en"
            return if (tag.startsWith("ru")) "ru" else "en"
        }
        val sysLang = Locale.getDefault().language
        return if (sysLang.startsWith("ru")) "ru" else "en"
    }

    fun setLanguage(tag: String) {
        val clean = if (tag.startsWith("ru")) "ru" else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(clean))
    }
}
