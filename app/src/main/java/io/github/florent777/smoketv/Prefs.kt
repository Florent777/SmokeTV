package io.github.florent777.smoketv

import android.content.Context

/** All launcher state lives in one SharedPreferences file. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("smoketv", Context.MODE_PRIVATE)

    private fun list(key: String) = sp.getString(key, "")!!.split(',').filter { it.isNotBlank() }
    private fun putList(key: String, v: List<String>) = sp.edit().putString(key, v.distinct().joinToString(",")).apply()

    val hasPinned get() = sp.contains("pinned")
    var pinned: List<String>
        get() = list("pinned")
        set(v) = putList("pinned", v)

    var recents: List<String>
        get() = list("recents")
        set(v) = putList("recents", v.take(MAX_RECENTS))

    fun recordLaunch(pkg: String) {
        recents = listOf(pkg) + recents.filter { it != pkg }
    }

    /** Seeded with Amazon's system clutter on first run; the user can unhide any of it. */
    var hidden: Set<String>
        get() = if (sp.contains("hidden")) sp.getStringSet("hidden", null)!!.toSet() else AppRepository.DEFAULT_HIDDEN
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    /** Light mode: day sky, dark text. Default is dark mode. */
    var lightMode: Boolean
        get() = sp.getBoolean("light", false)
        set(v) = sp.edit().putBoolean("light", v).apply()

    var monochrome: Boolean
        get() = sp.getBoolean("mono", false)
        set(v) = sp.edit().putBoolean("mono", v).apply()

    /** Edge scrim strength, 0..100 %. */
    var scrim: Int
        get() = sp.getInt("scrim", 60)
        set(v) = sp.edit().putInt("scrim", v.coerceIn(0, 100)).apply()

    var showRecents: Boolean
        get() = sp.getBoolean("recents_row", true)
        set(v) = sp.edit().putBoolean("recents_row", v).apply()

    var weather: Boolean
        get() = sp.getBoolean("weather", false)
        set(v) = sp.edit().putBoolean("weather", v).apply()

    var place: String
        get() = sp.getString("place", "")!!
        set(v) = sp.edit().putString("place", v).apply()
    var lat: Float
        get() = sp.getFloat("lat", 0f)
        set(v) = sp.edit().putFloat("lat", v).apply()
    var lon: Float
        get() = sp.getFloat("lon", 0f)
        set(v) = sp.edit().putFloat("lon", v).apply()

    var weatherText: String?
        get() = sp.getString("weather_text", null)
        set(v) = sp.edit().putString("weather_text", v).apply()
    var weatherAt: Long
        get() = sp.getLong("weather_at", 0L)
        set(v) = sp.edit().putLong("weather_at", v).apply()

    companion object {
        const val MAX_RECENTS = 12
    }
}
