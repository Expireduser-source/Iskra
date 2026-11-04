package ru.iskra.app.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.iskra.app.BuildConfig
import ru.iskra.app.diag.Diag
import ru.iskra.core.i18n.tr
import ru.iskra.core.update.Release
import ru.iskra.core.update.UpdateCheck

/**
 * New versions for copies installed from GitHub or the site. A store (Google Play, RuStore, F-Droid…) or
 * Obtainium updates the app itself, so then the check is off. The owner can turn it off in «О приложении».
 */
object Updates {
    private const val DAY_MS = 20 * 3_600_000L
    private val stores = setOf(
        "com.android.vending", "ru.vk.store", "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify",
        "dev.imranr.obtainium", "in.sunilpaulmathew.izzyondroid", "com.aurora.store", "com.huawei.appmarket",
        "com.xiaomi.market", "com.xiaomi.mipicks", "com.sec.android.app.samsungapps",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _available = MutableStateFlow<Release?>(null)
    /** A newer release the owner has not dismissed. */
    val available: StateFlow<Release?> = _available

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun installer(ctx: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    }.getOrNull()

    /** Release build, not from a store or an app that keeps it updated. */
    fun applicable(ctx: Context): Boolean = !BuildConfig.DEBUG && installer(ctx) !in stores

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    /** At most once a day, when the app is opened. */
    fun maybeCheck(ctx: Context) {
        if (!applicable(ctx) || !enabled(ctx)) return
        val last = prefs(ctx).getLong("last", 0L)
        if (System.currentTimeMillis() - last < DAY_MS) { restore(ctx); return }
        scope.launch { check(ctx) }
    }

    /** Returns a line for the owner (the «Проверить обновления» button). */
    suspend fun check(ctx: Context): String {
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
        val r = try {
            UpdateCheck.latest()
        } catch (e: Exception) {
            Diag.w("update", tr("Проверка обновлений не удалась", "Update check failed"), e)
            return tr("Не удалось проверить: нет связи с GitHub.", "Couldn't check: no connection to GitHub.")
        }
        val current = BuildConfig.VERSION_NAME
        if (r == null || !UpdateCheck.isNewer(r.version, current)) {
            _available.value = null
            return tr("У тебя последняя версия ($current).", "You have the latest version ($current).")
        }
        prefs(ctx).edit().putString("version", r.version).putString("apk", r.apkUrl).putString("page", r.pageUrl).putString("notes", r.notes).apply()
        Diag.i("update", tr("Доступна версия ", "Version available: ") + r.version)
        if (prefs(ctx).getString("dismissed", null) != r.version) _available.value = r
        return tr("Вышла версия ${r.version}.", "Version ${r.version} is out.")
    }

    /** "Later": this version is not offered again; the next one will be. */
    fun dismiss(ctx: Context) {
        _available.value?.let { prefs(ctx).edit().putString("dismissed", it.version).apply() }
        _available.value = null
    }

    private fun restore(ctx: Context) {
        val p = prefs(ctx)
        val v = p.getString("version", null) ?: return
        if (p.getString("dismissed", null) == v || !UpdateCheck.isNewer(v, BuildConfig.VERSION_NAME)) return
        _available.value = Release(v, p.getString("apk", null), p.getString("page", null) ?: UpdateCheck.PROJECT, p.getString("notes", null).orEmpty())
    }
}
