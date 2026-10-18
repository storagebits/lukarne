package app.lukarne.data

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Current weather at a cam, for the fullscreen caption. Open-Meteo: free, no key, CC BY 4.0. */
data class Weather(val celsius: Double, val code: Int, val isDay: Boolean)

class WeatherSource(private val http: OkHttpClient) {
    @Serializable private data class Current(val temperature_2m: Double, val weather_code: Int, val is_day: Int)
    @Serializable private data class Answer(val current: Current)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, Weather>>()

    /** Null when offline or slow: the caption simply goes without. Cached 20 min per ~10 km cell. */
    suspend fun at(lat: Double, lon: Double): Weather? {
        val key = "${(lat * 10).roundToInt()},${(lon * 10).roundToInt()}"
        cache[key]?.let { (at, w) -> if (SystemClock.elapsedRealtime() - at < 20 * 60_000) return w }
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day"
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val c = json.decodeFromString<Answer>(r.body.string()).current
                    Weather(c.temperature_2m, c.weather_code, c.is_day == 1)
                }
            }.onFailure { Log.w("Lukarne.Weather", "weather: $it") }.getOrNull()
                ?.also { cache[key] = SystemClock.elapsedRealtime() to it }
        }
    }

    companion object {
        /** WMO weather code → short label. */
        fun label(code: Int, language: String): String {
            val (en, fr, es) = when (code) {
                0 -> Triple("Clear", "Ciel dégagé", "Despejado")
                1, 2 -> Triple("Partly cloudy", "Partiellement nuageux", "Parcialmente nublado")
                3 -> Triple("Overcast", "Couvert", "Cubierto")
                45, 48 -> Triple("Fog", "Brouillard", "Niebla")
                51, 53, 55, 56, 57 -> Triple("Drizzle", "Bruine", "Llovizna")
                61, 63, 65, 66, 67, 80, 81, 82 -> Triple("Rain", "Pluie", "Lluvia")
                71, 73, 75, 77, 85, 86 -> Triple("Snow", "Neige", "Nieve")
                95, 96, 99 -> Triple("Thunderstorm", "Orage", "Tormenta")
                else -> return ""
            }
            return when (language) { "fr" -> fr; "es" -> es; else -> en }
        }
    }
}
