package dev.caraalink.settings

import android.content.Context
import androidx.preference.PreferenceManager
import dev.caraalink.wireless.RememberedCar

/**
 * Typed reads over the preferences declared in res/xml/root_preferences.xml.
 * Defaults are tuned for Proton Saga 2026 (landscape, 720p, 30fps, 4000kbps, 160 DPI, fill, phone_direct).
 */
object AppSettings {

    enum class ResolutionMode { AUTO, CUSTOM }
    enum class UsbRoleOverride { AUTO, FORCE_ACCESSORY, FORCE_HOST }
    enum class AaAudioRoute { BT_PREFERRED, DEVICE_ONLY, PHONE_DIRECT }
    enum class CarOrientation { AUTO, LANDSCAPE, PORTRAIT }
    enum class AaFillMode { FIT, FILL }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun setupComplete(context: Context): Boolean = prefs(context).getBoolean("setup_complete", false)
    fun setSetupComplete(context: Context, value: Boolean) = prefs(context).edit().putBoolean("setup_complete", value).apply()
    fun devModeConfirmed(context: Context): Boolean = prefs(context).getBoolean("dev_mode_confirmed", false)
    fun setDevModeConfirmed(context: Context, value: Boolean) = prefs(context).edit().putBoolean("dev_mode_confirmed", value).apply()
    fun carConnectedOnce(context: Context): Boolean = prefs(context).getBoolean("car_connected_once", false)
    fun setCarConnectedOnce(context: Context) = prefs(context).edit().putBoolean("car_connected_once", true).apply()

    fun wirelessCar(context: Context): RememberedCar? {
        val p = prefs(context)
        val address = p.getString("wireless_car_address", null) ?: return null
        return RememberedCar(p.getString("wireless_car_name", null) ?: address, address)
    }

    fun setWirelessCar(context: Context, car: RememberedCar?) = prefs(context).edit()
        .putString("wireless_car_name", car?.name).putString("wireless_car_address", car?.address).apply()

    fun resolutionMode(context: Context): ResolutionMode =
        when (prefs(context).getString("video_resolution_mode", "auto")) {
            "custom" -> ResolutionMode.CUSTOM
            else -> ResolutionMode.AUTO
        }

    fun customResolution(context: Context): Pair<Int, Int>? {
        val w = prefs(context).getString("video_resolution_custom_width", null)?.toIntOrNull()
        val h = prefs(context).getString("video_resolution_custom_height", null)?.toIntOrNull()
        return if (w != null && h != null) w to h else null
    }

    fun carOrientation(context: Context): CarOrientation =
        when (prefs(context).getString("car_orientation", "landscape")) {
            "landscape" -> CarOrientation.LANDSCAPE
            "portrait" -> CarOrientation.PORTRAIT
            else -> CarOrientation.AUTO
        }

    fun frameRate(context: Context): Int =
        prefs(context).getString("video_frame_rate", "30")?.toIntOrNull() ?: 30

    fun videoBitrateKbps(context: Context): Int =
        prefs(context).getString("video_bitrate_kbps", "4000")?.toIntOrNull() ?: 4000

    fun videoDpi(context: Context, width: Int, height: Int): Int {
        if (videoDpiAuto(context)) {
            return (minOf(width, height) / 720f * 213).toInt().coerceIn(120, 320)
        }
        return manualVideoDpi(context)
    }

    fun videoDpiAuto(context: Context): Boolean = prefs(context).getBoolean("video_dpi_auto", false)

    fun manualVideoDpi(context: Context): Int =
        prefs(context).getString("video_dpi_manual", "160")?.toIntOrNull() ?: 160

    fun audioSampleRateHz(context: Context): Int =
        prefs(context).getString("audio_sample_rate", "48000")?.toIntOrNull() ?: 48000

    fun audioStereo(context: Context): Boolean =
        prefs(context).getBoolean("audio_stereo", true)

    fun autoReconnectUsb(context: Context): Boolean =
        prefs(context).getBoolean("auto_reconnect_usb", true)

    fun usbRoleOverride(context: Context): UsbRoleOverride =
        when (prefs(context).getString("usb_role_override", "auto")) {
            "accessory" -> UsbRoleOverride.FORCE_ACCESSORY
            "host" -> UsbRoleOverride.FORCE_HOST
            else -> UsbRoleOverride.AUTO
        }

    fun verboseLogging(context: Context): Boolean =
        prefs(context).getBoolean("debug_verbose_logging", false)

    fun debugOverlay(context: Context): Boolean =
        prefs(context).getBoolean("debug_overlay", false)

    fun autoSaveCrashLog(context: Context): Boolean =
        prefs(context).getBoolean("auto_save_crash_log", true)

    fun aaAutoCrop(context: Context): Boolean =
        prefs(context).getBoolean("aa_auto_crop", true)

    fun videoDownscale720p(context: Context): Boolean =
        prefs(context).getBoolean("video_downscale_720p", true)

    fun aaFillMode(context: Context): AaFillMode =
        when (prefs(context).getString("aa_fill_mode", "fill")) {
            "fill" -> AaFillMode.FILL
            else -> AaFillMode.FIT
        }

    fun aaAudioRoute(context: Context): AaAudioRoute =
        when (prefs(context).getString("aa_audio_route", "phone_direct")) {
            "device_only" -> AaAudioRoute.DEVICE_ONLY
            "bt_preferred" -> AaAudioRoute.BT_PREFERRED
            else -> AaAudioRoute.PHONE_DIRECT
        }

    fun updateAutoCheck(context: Context): Boolean =
        prefs(context).getBoolean("update_auto_check", true)
}