package ru.iskra.core.tools

/** Fuzzy matching of a spoken/typed app name ("телеграм", "ютуб") to installed launcher labels. */
object AppMatcher {
    private val aliases = mapOf(
        "телега" to "telegram", "тг" to "telegram", "ютуб" to "youtube", "ютьюб" to "youtube",
        "вк" to "vk", "вконтакте" to "vk", "ватсап" to "whatsapp", "вотсап" to "whatsapp", "вацап" to "whatsapp",
        "инста" to "instagram", "хром" to "chrome", "гугл" to "google", "карты" to "maps", "почта" to "gmail",
        "калькулятор" to "calculator", "часы" to "clock", "галерея" to "gallery", "музыка" to "music",
        "плей маркет" to "play store", "плеймаркет" to "play store", "магазин" to "store", "сбер" to "sber",
        "тикток" to "tiktok", "дискорд" to "discord", "спотифай" to "spotify", "яндекс" to "yandex",
    )
    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
    private fun lat(s: String) = buildString { for (c in s) append(translit[c] ?: c.toString()) }
        .replace("ks", "x").replace(" ", "")

    /** Returns the index of the best label, or null if nothing is close enough. */
    fun best(query: String, labels: List<String>): Int? {
        val q = norm(query).removeSuffix(" приложение")
        if (q.isEmpty()) return null
        val qa = aliases[q] ?: q
        val qLat = lat(qa)
        var bestIdx: Int? = null
        var bestScore = 0
        labels.forEachIndexed { i, label ->
            val l = norm(label)
            val lLat = lat(l)
            val score = when {
                l == q || l == qa -> 100
                lLat == qLat -> 95
                l.startsWith(qa) || lLat.startsWith(qLat) -> 80
                qLat.length >= 4 && lLat.contains(qLat) -> 60
                qLat.length >= 5 && levenshtein(lLat, qLat) <= 2 -> 50
                else -> 0
            }
            if (score > bestScore || (score == bestScore && score > 0 && bestIdx != null && label.length < labels[bestIdx!!].length)) {
                bestScore = score; bestIdx = i
            }
        }
        return if (bestScore > 0) bestIdx else null
    }

    internal fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
