package ru.iskra.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.iskra.core.llm.Http

/** The newest published version of Iskra (GitHub Releases). */
data class Release(val version: String, val apkUrl: String?, val pageUrl: String, val notes: String)

/**
 * For copies installed from the site or GitHub (stores and Obtainium update the app themselves):
 * once a day the app asks GitHub which version is the newest. Only that question is sent — no data about
 * the owner or the phone; GitHub sees the request like any web page visit.
 */
object UpdateCheck {
    const val PROJECT = "https://github.com/Expireduser-source/Iskra"
    const val LATEST_API = "https://api.github.com/repos/Expireduser-source/Iskra/releases/latest"
    /** Always the newest APK (the release job publishes it under this constant name). */
    const val LATEST_APK = "$PROJECT/releases/latest/download/Iskra.apk"

    suspend fun latest(client: OkHttpClient = Http.defaultClient, url: String = LATEST_API): Release? {
        val (code, body) = Http.execute(client, Request.Builder().url(url).header("Accept", "application/vnd.github+json").get().build())
        if (code == 404) return null // nothing published yet
        Http.checkStatus(code, body)
        return parse(body)
    }

    fun parse(json: String): Release? {
        val o = Json.parseToJsonElement(json).jsonObject
        fun str(obj: JsonObject, k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        if (str(o, "draft") == "true" || str(o, "prerelease") == "true") return null
        val tag = str(o, "tag_name") ?: return null
        val version = tag.removePrefix("v").trim()
        if (!Regex("""\d+(\.\d+)*""").matches(version)) return null
        val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { str(it, "name") == "Iskra.apk" || str(it, "name")?.endsWith(".apk") == true }
            ?.let { str(it, "browser_download_url") }
        return Release(version, apk, str(o, "html_url") ?: PROJECT, str(o, "body").orEmpty().trim())
    }

    /** "0.10.1" > "0.10.0" > "0.9.3"; suffixes like "-debug" are ignored. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The first lines of the release notes for a short banner. */
    fun shortNotes(notes: String, lines: Int = 3): String =
        notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("Release-") && !it.startsWith("Debug-") }
            .take(lines).joinToString("\n") { "• $it" }
}
