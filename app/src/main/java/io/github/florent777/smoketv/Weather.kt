package io.github.florent777.smoketv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** Open-Meteo (no API key). Blocking: call from a background thread. */
object Weather {
    class Place(val name: String, val lat: Float, val lon: Float)

    /** "7° Cloudy", or null on any failure. */
    fun current(lat: Float, lon: Float): String? = try {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
            lat, lon,
        )
        val cur = JSONObject(get(url)).getJSONObject("current")
        "${cur.getDouble("temperature_2m").roundToInt()}° ${describe(cur.getInt("weather_code"))}".trim()
    } catch (_: Exception) {
        null
    }

    /** Accepts "52.52, 13.40" or a city name (Open-Meteo geocoding). */
    fun resolve(query: String): Place? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,; ]\s*(-?\d+(?:\.\d+)?)\s*$""").find(query)
        if (m != null) {
            val lat = m.groupValues[1].toFloat()
            val lon = m.groupValues[2].toFloat()
            if (lat in -90f..90f && lon in -180f..180f) {
                return Place(String.format(Locale.US, "%.2f, %.2f", lat, lon), lat, lon)
            }
        }
        return try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=" + URLEncoder.encode(query, "UTF-8")
            val r = JSONObject(get(url)).optJSONArray("results")?.optJSONObject(0) ?: return null
            Place(r.getString("name"), r.getDouble("latitude").toFloat(), r.getDouble("longitude").toFloat())
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "SmokeTV/1.0")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }
}
