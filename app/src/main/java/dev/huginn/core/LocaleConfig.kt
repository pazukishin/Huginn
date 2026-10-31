package dev.huginn.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Idiomas soportados (M6): inglés por defecto y español. La app aplica el idioma
 * elegido envolviendo el contexto base de la activity; al cambiarlo se recrea.
 */
object LocaleConfig {

    const val EN = "en"
    const val ES = "es"
    const val DEFAULT = EN

    fun normalize(tag: String?): String = when (tag?.lowercase()?.take(2)) {
        ES -> ES
        else -> DEFAULT
    }

    fun apply(context: Context, tag: String): Context {
        val locale = Locale.forLanguageTag(normalize(tag))
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
