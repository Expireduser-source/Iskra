package ru.iskra.app.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import ru.iskra.core.i18n.I18n
import ru.iskra.core.i18n.Lang

/**
 * Iskra's language: the phone's by default (Russian for Russian-reading phones, English for everyone else),
 * or the owner's choice. On Android 13+ the choice is the system per-app language (Settings → Apps → Iskra →
 * Language shows and changes the same value); older versions keep it in the app's preferences.
 */
object AppLanguage {
    private const val PREFS = "language"
    private const val KEY = "lang"

    /** The owner's explicit choice, or null = follow the phone. */
    fun choice(ctx: Context): Lang? =
        if (Build.VERSION.SDK_INT >= 33) {
            val app = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (app == null || app.isEmpty) null else Lang.forLocale(app[0].language)
        } else Lang.of(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    /** The phone's own language, ignoring the per-app choice. */
    private fun phone(): Lang = Lang.forLocale(Resources.getSystem().configuration.locales[0]?.language)

    fun resolve(ctx: Context): Lang = choice(ctx) ?: phone()

    /** Call at start and after a configuration change. */
    fun apply(ctx: Context) {
        I18n.lang = resolve(ctx)
    }

    /** null = follow the phone. Recreates [activity] so every screen redraws in the new language. */
    fun set(ctx: Context, lang: Lang?, activity: Activity?) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activities itself after this call.
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (lang == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang.tag)
            I18n.lang = lang ?: phone()
        } else {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (lang == null) remove(KEY) else putString(KEY, lang.tag)
            }.apply()
            I18n.lang = lang ?: phone()
            activity?.recreate()
        }
    }
}
