package dev.caraalink.settings

import android.content.Context
import org.json.JSONObject

/**
 * Named snapshots of the video/AA settings that vary per car.
 * Includes a built-in preset tailored for Proton Saga 2026 (CarbitLink).
 */
object CarProfiles {
    private const val PREFS_FILE = "car_profiles"
    private const val SEEDED_SAGA_FLAG = "car_profiles_proton_saga_seeded_v2"
    const val PROTON_SAGA_PROFILE_NAME = "Proton Saga 2026"

    private val PROFILE_KEYS = listOf(
        "car_orientation",
        "video_resolution_mode",
        "video_resolution_custom_width",
        "video_resolution_custom_height",
        "video_frame_rate",
        "video_bitrate_kbps",
        "video_dpi_auto",
        "video_dpi_manual",
        "video_downscale_720p",
        "aa_auto_crop",
        "aa_fill_mode",
        "aa_audio_route",
    )

    private fun store(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private fun logger(context: Context) = dev.caraalink.diagnostics.DiagnosticLogger(context, "car-profiles")

    private fun ensureBuiltInProfiles(context: Context) {
        val defaults = PreferenceManagerCompat.default(context)
        if (defaults.getBoolean(SEEDED_SAGA_FLAG, false)) return

        val prefs = store(context)
        val sagaSnapshot = JSONObject().apply {
            put("car_orientation", "landscape")
            put("video_resolution_mode", "auto")
            put("video_resolution_custom_width", "1024")
            put("video_resolution_custom_height", "600")
            put("video_frame_rate", "30")
            put("video_bitrate_kbps", "4000")
            put("video_dpi_auto", false)
            put("video_dpi_manual", "160")
            put("video_downscale_720p", true)
            put("aa_auto_crop", true)
            put("aa_fill_mode", "fill")
            put("aa_audio_route", "phone_direct")
        }
        prefs.edit().putString(PROTON_SAGA_PROFILE_NAME, sagaSnapshot.toString()).apply()
        defaults.edit().putBoolean(SEEDED_SAGA_FLAG, true).apply()
        logger(context).log("seeded built-in profile '$PROTON_SAGA_PROFILE_NAME': $sagaSnapshot")
    }

    fun listProfiles(context: Context): List<String> {
        ensureBuiltInProfiles(context)
        return store(context).all.keys.sorted()
    }

    fun saveProfile(context: Context, name: String) {
        if (name.isBlank()) return
        val defaults = PreferenceManagerCompat.default(context)
        val snapshot = JSONObject()
        for (key in PROFILE_KEYS) {
            when (val value = defaults.all[key]) {
                is Boolean -> snapshot.put(key, value)
                is String -> snapshot.put(key, value)
                null -> {}
                else -> snapshot.put(key, value.toString())
            }
        }
        store(context).edit().putString(name.trim(), snapshot.toString()).apply()
        logger(context).log("saved profile '${name.trim()}': $snapshot")
    }

    /** Returns false if [name] isn't a saved profile. */
    fun loadProfile(context: Context, name: String): Boolean {
        ensureBuiltInProfiles(context)
        val json = store(context).getString(name, null) ?: return false
        val snapshot = JSONObject(json)
        val defaults = PreferenceManagerCompat.default(context)
        val editor = defaults.edit()
        for (key in PROFILE_KEYS) {
            if (!snapshot.has(key)) continue
            when (val value = snapshot.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
        logger(context).log("loaded profile '$name': $snapshot")
        return true
    }

    fun deleteProfile(context: Context, name: String) {
        store(context).edit().remove(name).apply()
        logger(context).log("deleted profile '$name'")
    }
}

private object PreferenceManagerCompat {
    fun default(context: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
}