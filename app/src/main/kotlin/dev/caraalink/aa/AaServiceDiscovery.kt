package dev.caraalink.aa

import android.content.Context
import dev.caraalink.settings.AppSettings

object AaServiceDiscovery {

    enum class AaResolution(val protocolValue: Int, val width: Int, val height: Int) {
        R800X480(1, 800, 480),
        R1280X720(2, 1280, 720),
        R1920X1080(3, 1920, 1080),
        R2560X1440(4, 2560, 1440),
        R3840X2160(5, 3840, 2160),
        R720X1280(6, 720, 1280),
        R1080X1920(7, 1080, 1920),
        R1440X2560(8, 1440, 2560),
        R2160X3840(9, 2160, 3840),
    }

    /** `VideoFrameRateType` */
    const val FPS_60 = 1
    const val FPS_30 = 2

    /** `MediaCodecType` */
    const val CODEC_AUDIO_PCM = 1
    const val CODEC_AUDIO_AAC_LC = 2
    const val CODEC_VIDEO_H264_BP = 3

    /** `AudioStreamType` */
    const val AUDIO_STREAM_SYSTEM = 2
    const val AUDIO_STREAM_MEDIA = 3

    /** `DriverPosition` */
    const val DRIVER_POSITION_LEFT = 0

    /** `SensorType` */
    const val SENSOR_LOCATION = 1
    const val SENSOR_NIGHT_MODE = 10
    const val SENSOR_DRIVING_STATUS_DATA = 13

    /** Control-channel channel ids. */
    const val CHANNEL_MEDIA_SINK_VIDEO = 3
    const val CHANNEL_MEDIA_SINK_MEDIA_AUDIO = 4
    const val CHANNEL_SENSOR = 1
    const val CHANNEL_MEDIA_SOURCE_MICROPHONE = 9
    const val CHANNEL_MEDIA_PLAYBACK_STATUS = 13
    const val CHANNEL_PHONE_STATUS = 14
    const val CHANNEL_MEDIA_BROWSER = 15
    const val CHANNEL_VENDOR_EXTENSION = 16
    const val CHANNEL_GENERIC_NOTIFICATION = 17
    const val CHANNEL_WIFI_PROJECTION = 18
    const val CHANNEL_INPUT_SOURCE = 8

    data class Config(
        val resolution: AaResolution = AaResolution.R1280X720,
        val frameRate: Int = FPS_30,
        val density: Int = 160,
        val touchWidth: Int = 1280,
        val touchHeight: Int = 720,
        val widthMargin: Int = 0,
        val heightMargin: Int = 0,
        val displayName: String = "SagaBridge",
        val driverPosition: Int = DRIVER_POSITION_LEFT,
        val phoneMediaAudio: Boolean = false,
    )

    fun configFromSettings(context: Context, preview: Boolean = false): Config {
        val isCustom = AppSettings.resolutionMode(context) == AppSettings.ResolutionMode.CUSTOM
        val customWh = if (isCustom) AppSettings.customResolution(context) else null
        val metrics = context.resources.displayMetrics
        val portrait = if (preview && customWh == null) {
            metrics.heightPixels >= metrics.widthPixels
        } else if (customWh != null) {
            customWh.second > customWh.first
        } else {
            when (AppSettings.carOrientation(context)) {
                AppSettings.CarOrientation.PORTRAIT -> true
                AppSettings.CarOrientation.LANDSCAPE -> false
                AppSettings.CarOrientation.AUTO -> dev.caraalink.link.CarScreenInfo.isPortrait ?: false
            }
        }
        val candidates = AaResolution.values().filter { (it.height > it.width) == portrait }
        val defaultRes = if (AppSettings.videoDownscale720p(context)) {
            if (portrait) AaResolution.R720X1280 else AaResolution.R1280X720
        } else if (portrait) AaResolution.R1080X1920 else AaResolution.R1920X1080
        val resolution = if (customWh != null) {
            val (w, h) = customWh
            candidates.minByOrNull { kotlin.math.abs(it.width * it.height - w * h) } ?: defaultRes
        } else if (preview) {
            candidates.minByOrNull {
                kotlin.math.abs(it.width * it.height - metrics.widthPixels * metrics.heightPixels)
            } ?: defaultRes
        } else {
            defaultRes
        }
        val frameRate = if (AppSettings.frameRate(context) >= 60) FPS_60 else FPS_30
        val density = if (preview && AppSettings.videoDpiAuto(context)) {
            context.resources.displayMetrics.densityDpi
        } else {
            AppSettings.videoDpi(context, resolution.width, resolution.height)
        }
        val (widthMargin, heightMargin) = if (AppSettings.aaAutoCrop(context) && AppSettings.aaFillMode(context) == AppSettings.AaFillMode.FIT) {
            autoMargin(resolution, dev.caraalink.link.CarScreenInfo.width, dev.caraalink.link.CarScreenInfo.height)
        } else 0 to 0
        return Config(
            resolution = resolution,
            frameRate = frameRate,
            density = density,
            touchWidth = (resolution.width - widthMargin).coerceAtLeast(1),
            touchHeight = (resolution.height - heightMargin).coerceAtLeast(1),
            widthMargin = widthMargin,
            heightMargin = heightMargin,
            phoneMediaAudio = AppSettings.aaAudioRoute(context) == AppSettings.AaAudioRoute.PHONE_DIRECT,
        )
    }

    fun autoMargin(resolution: AaResolution, carWidth: Int, carHeight: Int): Pair<Int, Int> {
        if (carWidth <= 0 || carHeight <= 0) return 0 to 0
        val carAspect = carWidth.toFloat() / carHeight
        val resAspect = resolution.width.toFloat() / resolution.height
        return when {
            resAspect > carAspect + ASPECT_EPSILON -> {
                val desiredWidth = (resolution.height * carAspect).toInt()
                (resolution.width - desiredWidth).coerceAtLeast(0) to 0
            }
            resAspect < carAspect - ASPECT_EPSILON -> {
                val desiredHeight = (resolution.width / carAspect).toInt()
                0 to (resolution.height - desiredHeight).coerceAtLeast(0)
            }
            else -> 0 to 0
        }
    }

    private const val ASPECT_EPSILON = 0.01f

    fun build(config: Config = Config()): ByteArray {
        val videoConfig = AaProtobuf.Writer()
            .int(1, config.resolution.protocolValue)
            .int(2, config.frameRate)
            .int(3, config.widthMargin)
            .int(4, config.heightMargin)
            .int(5, config.density)
            .int(10, CODEC_VIDEO_H264_BP)
            .build()

        val mediaSink = AaProtobuf.Writer()
            .int(1, CODEC_VIDEO_H264_BP)
            .bool(5, true)
            .message(4, videoConfig)
            .build()

        val touchscreen = AaProtobuf.Writer()
            .int(1, config.touchWidth)
            .int(2, config.touchHeight)
            .build()

        val inputSource = AaProtobuf.Writer()
            .message(2, touchscreen)
            .build()

        val audioConfig = AaProtobuf.Writer()
            .int(1, if (config.phoneMediaAudio) 16_000 else 48_000)
            .int(2, 16)
            .int(3, if (config.phoneMediaAudio) 1 else 2)
            .build()

        val audioSink = AaProtobuf.Writer()
            .int(1, if (config.phoneMediaAudio) CODEC_AUDIO_PCM else CODEC_AUDIO_AAC_LC)
            .int(2, if (config.phoneMediaAudio) AUDIO_STREAM_SYSTEM else AUDIO_STREAM_MEDIA)
            .message(3, audioConfig)
            .build()

        val audioService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SINK_MEDIA_AUDIO)
            .message(3, audioSink)
            .build()

        val videoService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SINK_VIDEO)
            .message(3, mediaSink)
            .build()

        val inputService = AaProtobuf.Writer()
            .int(1, CHANNEL_INPUT_SOURCE)
            .message(4, inputSource)
            .build()

        val ping = AaProtobuf.Writer()
            .int(1, 3000)
            .int(2, 1000)
            .int(3, 200)
            .int(4, 5)
            .build()
        val connectionConfig = AaProtobuf.Writer()
            .message(1, ping)
            .build()

        val headUnitInfo = AaProtobuf.Writer()
            .string(1, "Proton")       // make
            .string(2, "Saga 2026")    // model
            .string(3, "2026")         // year
            .string(4, "sagabridge")   // vehicle_id
            .string(5, "Proton")       // head_unit_make
            .string(6, "SagaBridge")   // head_unit_model
            .string(7, "1")            // head_unit_software_build
            .string(8, "1.0.0")        // head_unit_software_version
            .build()

        val sensorService = AaProtobuf.Writer()
            .int(1, CHANNEL_SENSOR)
            .message(
                2,
                AaProtobuf.Writer()
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_DRIVING_STATUS_DATA).build())
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_LOCATION).build())
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_NIGHT_MODE).build())
                    .build(),
            )
            .build()

        val micAudioConfig = AaProtobuf.Writer()
            .int(1, 16_000)
            .int(2, 16)
            .int(3, 1)
            .build()
        val micService = AaProtobuf.Writer()
            .int(1, CODEC_AUDIO_PCM)
            .message(2, micAudioConfig)
            .build()
        val microphoneService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SOURCE_MICROPHONE)
            .message(5, micService)
            .build()

        val emptyServiceExtra = listOf(
            CHANNEL_MEDIA_PLAYBACK_STATUS to 9,
            CHANNEL_PHONE_STATUS to 10,
            CHANNEL_MEDIA_BROWSER to 11,
            CHANNEL_GENERIC_NOTIFICATION to 13,
            CHANNEL_WIFI_PROJECTION to 14,
        ).map { (channel, field) ->
            AaProtobuf.Writer()
                .int(1, channel)
                .message(field, ByteArray(0))
                .build()
        }

        val vendorExtension = AaProtobuf.Writer()
            .string(1, config.displayName)
            .build()

        val vendorService = AaProtobuf.Writer()
            .int(1, CHANNEL_VENDOR_EXTENSION)
            .message(12, vendorExtension)
            .build()

        val builder = AaProtobuf.Writer()
            .message(1, videoService)
            .message(1, audioService)
            .message(1, inputService)
            .message(1, sensorService)
            .message(1, microphoneService)
        emptyServiceExtra.forEach { builder.message(1, it) }
        return builder.message(1, vendorService)
            .int(6, config.driverPosition)
            .string(14, config.displayName)
            .bool(15, false)
            .message(16, connectionConfig)
            .message(17, headUnitInfo)
            .build()
    }

    fun describe(config: Config = Config()): String =
        "video=${config.resolution.width}x${config.resolution.height}" +
            "@${if (config.frameRate == FPS_60) 60 else 30}fps h264bp density=${config.density}, " +
            "touch=${config.touchWidth}x${config.touchHeight}, name='${config.displayName}', " +
            "audio=${if (config.phoneMediaAudio) "phone" else "aa-media"}"
}