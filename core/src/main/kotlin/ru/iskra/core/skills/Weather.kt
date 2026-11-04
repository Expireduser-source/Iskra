package ru.iskra.core.skills

import ru.iskra.core.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.iskra.core.llm.Http
import ru.iskra.core.llm.LlmException
import ru.iskra.core.tools.Risk
import ru.iskra.core.tools.Tool
import ru.iskra.core.tools.ToolOutcome
import ru.iskra.core.tools.str
import kotlin.math.roundToInt

data class Place(val name: String, val lat: Double, val lon: Double)
data class DayWeather(val tMin: Double, val tMax: Double, val code: Int, val rainChance: Int?)
data class Forecast(val place: Place, val tempNow: Double?, val codeNow: Int?, val windNow: Double?, val days: List<DayWeather>)

/**
 * Weather from Open-Meteo (open-meteo.com, free, no key, data CC BY 4.0). Only the city's
 * coordinates are sent — no location permission, no account, nothing about the owner.
 */
class WeatherClient(
    private val client: OkHttpClient = Http.defaultClient,
    private val forecastUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val geoUrl: String = "https://geocoding-api.open-meteo.com/v1/search",
) {
    suspend fun geocode(name: String): Place? {
        val url = geoUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", name.trim().take(80)).addQueryParameter("count", "1")
            .addQueryParameter("language", tr("ru", "en")).addQueryParameter("format", "json").build()
        val (code, body) = Http.execute(client, Request.Builder().url(url).get().build())
        Http.checkStatus(code, body)
        val first = (Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val n = (first["name"] as? JsonPrimitive)?.contentOrNull ?: name
        return Place(n, lat, lon)
    }

    suspend fun forecast(p: Place): Forecast {
        val url = forecastUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(java.util.Locale.ROOT, p.lat))
            .addQueryParameter("longitude", "%.4f".format(java.util.Locale.ROOT, p.lon))
            .addQueryParameter("current", "temperature_2m,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
            .addQueryParameter("wind_speed_unit", "ms").addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "2").build()
        val (code, body) = Http.execute(client, Request.Builder().url(url).get().build())
        Http.checkStatus(code, body)
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val cur = root["current"] as? JsonObject
            val d = root["daily"]!!.jsonObject
            fun arr(k: String) = (d[k] as? JsonArray).orEmpty()
            val days = arr("temperature_2m_max").indices.map { i ->
                DayWeather(
                    tMin = (arr("temperature_2m_min")[i] as JsonPrimitive).doubleOrNull ?: 0.0,
                    tMax = (arr("temperature_2m_max")[i] as JsonPrimitive).doubleOrNull ?: 0.0,
                    code = (arr("weather_code").getOrNull(i) as? JsonPrimitive)?.intOrNull ?: -1,
                    rainChance = (arr("precipitation_probability_max").getOrNull(i) as? JsonPrimitive)?.intOrNull,
                )
            }
            return Forecast(p,
                (cur?.get("temperature_2m") as? JsonPrimitive)?.doubleOrNull,
                (cur?.get("weather_code") as? JsonPrimitive)?.intOrNull,
                (cur?.get("wind_speed_10m") as? JsonPrimitive)?.doubleOrNull,
                days)
        } catch (e: Exception) {
            throw LlmException("Weather: unexpected response", LlmException.Kind.BAD_RESPONSE, e)
        }
    }
}

object WeatherText {
    /** WMO weather interpretation codes. */
    fun describe(code: Int): String = when (code) {
        0 -> tr("ясно", "clear")
        1 -> tr("в основном ясно", "mostly clear")
        2 -> tr("переменная облачность", "partly cloudy")
        3 -> tr("пасмурно", "overcast")
        45, 48 -> tr("туман", "fog")
        51, 53, 55 -> tr("морось", "drizzle")
        56, 57 -> tr("ледяная морось", "freezing drizzle")
        61 -> tr("небольшой дождь", "light rain")
        63 -> tr("дождь", "rain")
        65 -> tr("сильный дождь", "heavy rain")
        66, 67 -> tr("ледяной дождь", "freezing rain")
        71 -> tr("небольшой снег", "light snow")
        73 -> tr("снег", "snow")
        75 -> tr("сильный снег", "heavy snow")
        77 -> tr("снежная крупа", "snow grains")
        80, 81 -> tr("ливни", "showers")
        82 -> tr("сильные ливни", "heavy showers")
        85, 86 -> tr("снегопад", "snowfall")
        95 -> tr("гроза", "thunderstorm")
        96, 99 -> tr("гроза с градом", "thunderstorm with hail")
        else -> tr("без подробностей", "no details")
    }

    fun temp(t: Double): String {
        val v = t.roundToInt()
        return when { v > 0 -> "+$v°"; v < 0 -> "−${-v}°"; else -> "0°" }
    }

    private fun wet(code: Int) = code in 51..67 || code in 80..82 || code in 95..99

    fun say(f: Forecast, day: Int): String {
        val d = f.days.getOrNull(day) ?: return tr("Прогноза на этот день нет.", "No forecast for that day.")
        val rain = d.rainChance
        val umbrella = when {
            wet(d.code) || (rain != null && rain >= 60) -> tr(" Зонт пригодится.", " Take an umbrella.")
            rain != null && rain in 30..59 -> tr(" Возможен дождь — зонт на всякий случай.", " Rain is possible — an umbrella just in case.")
            else -> ""
        }
        val chance = if (rain != null && rain >= 20) tr(", вероятность осадков $rain %", ", chance of precipitation $rain %") else ""
        return if (day == 0) {
            val now = if (f.tempNow != null) tr("сейчас ${temp(f.tempNow)}, ${describe(f.codeNow ?: d.code)}", "now ${temp(f.tempNow)}, ${describe(f.codeNow ?: d.code)}") +
                (f.windNow?.let { tr(", ветер ${it.roundToInt()} м/с", ", wind ${it.roundToInt()} m/s") } ?: "") + ". " else ""
            tr("${f.place.name}: $now" + "Сегодня от ${temp(d.tMin)} до ${temp(d.tMax)}$chance.$umbrella", "${f.place.name}: $now" + "Today from ${temp(d.tMin)} to ${temp(d.tMax)}$chance.$umbrella")
        } else {
            tr("${f.place.name}, завтра: от ${temp(d.tMin)} до ${temp(d.tMax)}, ${describe(d.code)}$chance.$umbrella", "${f.place.name}, tomorrow: from ${temp(d.tMin)} to ${temp(d.tMax)}, ${describe(d.code)}$chance.$umbrella")
        }
    }
}

/** Answers "какая погода" from the owner's city (or a named one), cached for 30 minutes. */
class WeatherTool(
    private val weather: WeatherClient,
    private val homePlace: () -> Place?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Tool {
    override val spec get() = SkillSchemas.weather
    override val risk = Risk.SAFE
    override val informational = true
    private val cache = HashMap<String, Pair<Long, Forecast>>()

    override fun describe(args: JsonObject) = tr("погода", "weather") + (args.str("city")?.let { " — $it" } ?: "")

    companion object {
        /** «в Москве» → Москве, Москва; «в Казани» → Казани, Казань; «в Омске» → Омске, Омск. At most 3 tries. */
        fun cityCandidates(city: String): List<String> {
            val c = city.trim().replaceFirstChar { it.uppercase() }
            val out = linkedSetOf(c)
            when {
                c.endsWith("ске") || c.endsWith("цке") || c.endsWith("граде") || c.endsWith("ге") && c.length > 4 -> out += c.dropLast(1)
                c.endsWith("е") -> { out += c.dropLast(1) + "а"; out += c.dropLast(1) }
                c.endsWith("и") -> { out += c.dropLast(1) + "ь"; out += c.dropLast(1) + "а" }
            }
            return out.take(3)
        }
    }

    override suspend fun execute(args: JsonObject): ToolOutcome {
        val day = if (args.str("day") == "tomorrow") 1 else 0
        val city = args.str("city")?.trim()?.takeIf { it.length >= 2 }
        val place = try {
            if (city != null) cityCandidates(city).firstNotNullOfOrNull { weather.geocode(it) } ?: return ToolOutcome(false, tr("не нашла город «$city»", "couldn't find the city “$city”"))
            else homePlace() ?: return ToolOutcome(false, tr("не знаю твой город — укажи его в Настройки → Погода", "I don't know your city — set it in Settings → Weather"))
        } catch (e: LlmException) {
            return ToolOutcome(false, tr("сервис погоды недоступен: нет связи", "the weather service is unavailable: no connection"))
        }
        val key = "%.2f,%.2f".format(java.util.Locale.ROOT, place.lat, place.lon)
        val now = clock()
        val cached = synchronized(cache) { cache[key]?.takeIf { now - it.first < 30 * 60_000 }?.second }
        val f = cached ?: try {
            weather.forecast(place).also { synchronized(cache) { cache[key] = now to it } }
        } catch (e: LlmException) {
            return ToolOutcome(false, if (e.kind == LlmException.Kind.NETWORK) tr("нет интернета — погоду не узнать", "no internet — can't get the weather") else tr("сервис погоды ответил ошибкой", "the weather service returned an error"))
        }
        return ToolOutcome(true, WeatherText.say(f, day))
    }
}
