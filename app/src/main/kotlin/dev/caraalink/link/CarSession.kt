package dev.caraalink.link

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.util.Log
import dev.caraalink.aa.AndroidAutoServer
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import dev.caraalink.video.CarDisplay
import dev.caraalink.video.CarScreen
import dev.caraalink.video.CarVideoEncoder
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * One connection to the head unit: ADB transport, PXC control, mirror
 * control/data, H.264 encoder and what's on the car screen.
 */
class CarSession(
    private val context: Context,
    private val accessory: UsbAccessory?,
    /** Wireless sessions hand the running Android Auto session to the next car link instead of closing it. */
    private val keepAndroidAuto: Boolean = false,
    private val onEnded: (String) -> Unit,
) : MirrorHost {

    private val logger = DiagnosticLogger(context, "car-session")
    private var transport: CarTransport? = null
    private var config: CaptureConfig? = null
    private var lastBitRate: Int = -1
    private var encoder: CarVideoEncoder? = null
    private var pxc: PxcChannel? = null
    private val features = CarFeatures(context)
    private val display = CarDisplay(context, onOpenAa = { openAndroidAuto() })

    private fun closeAa() {
        aaWatchdog.removeCallbacksAndMessages(null)
        pendingSettingsReconnect = null
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        display.showStatus(CarScreen.CLOSED)
    }

    @Volatile private var released = false

    private val frameLock = Any()
    @Volatile private var dataStream: MirrorStream? = null
    private var codecConfig: ByteArray? = null
    private var configSent = false
    private val pullQueue = ArrayDeque<ByteArray>()
    private var framesSent = 0L
    private var framesEncoded = 0L
    private var frameRequests = 0L
    private var pendingRequests = 0
    private var waitingForKey = true

    fun start(): Boolean {
        val accessory = accessory ?: return false
        val conn = UsbAccessoryConnection.open(context, accessory) ?: run {
            logger.log("openAccessory() returned null")
            return false
        }
        logger.log("Opened accessory ${accessory.manufacturer}/${accessory.model}")
        ConnectionStateHolder.update {
            it.copy(
                phase = ConnectionPhase.ATTACHED,
                accessoryManufacturer = accessory.manufacturer,
                accessoryModel = accessory.model,
                lastError = null,
            )
        }
        features.start()
        transport = if (accessory.manufacturer == QdTransport.MANUFACTURER) {
            QdTransport(conn, logger, this, ::onTransportClosed).also { it.start() }
        } else {
            AdbTransport(conn, logger, channels(), ::onTransportClosed).also { it.start() }
        }
        return true
    }

    fun startWireless() {
        features.start()
        transport = SocketTransport(logger, channels(), ::onTransportClosed).also { it.start() }
    }

    private fun channels(): (String) -> AdbTransport.StreamHandler? {
        val pxc = PxcChannel(logger, { info -> onClientInfo(info) }, features::replyBody).also { this.pxc = it }
        val ctrl = MirrorControlChannel(logger, this, context)
        val data = MirrorDataChannel(logger, this)
        return { service ->
            when (service) {
                "tcp:10922" -> pxc
                "tcp:10921" -> ctrl
                "tcp:10920" -> data
                else -> null
            }
        }
    }

    private fun onTransportClosed(reason: String) {
        Log.i(TAG, "transport closed: $reason")
        release()
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = reason) }
        onEnded(reason)
    }

    fun stop() {
        transport?.stop("stopped by user") ?: release()
    }

    private fun release() {
        released = true
        aaWatchdog.removeCallbacksAndMessages(null)
        watchdog.removeCallbacksAndMessages(null)
        pendingSettingsReconnect = null
        pollThread?.interrupt()
        pollThread = null
        AndroidAutoServer.cancelNeedsTap(context)
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged = null
        if (!keepAndroidAuto) dev.caraalink.aa.AaSessionProbe.active?.stop()
        aaDecoder?.stop()
        aaDecoder = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { features.stop() }
        encoder?.release()
        encoder = null
        display.release()
        synchronized(frameLock) { dataStream = null; pullQueue.clear() }
    }

    private fun onClientInfo(info: JSONObject) {
        logger.log(
            "Head unit: ${info.optString("HUName")} screenType=${info.optInt("screenType")} " +
                "mirror=${info.optBoolean("supportScreenMirroring")} touch=${info.optBoolean("supportScreenTouch")}",
        )
        ConnectionStateHolder.update {
            val attached = if (it.phase == ConnectionPhase.DISCONNECTED || it.phase == ConnectionPhase.ERROR) ConnectionPhase.ATTACHED else it.phase
            it.copy(carName = info.optString("carBrand").ifBlank { null }, phase = attached, lastError = null)
        }
    }

    private var aaDecoder: dev.caraalink.aa.AaVideoDecoder? = null
    @Volatile private var openingAa = false

    private fun openAndroidAuto() {
        if (released) return
        if (dev.caraalink.aa.AaSessionProbe.active != null) {
            showAaSurface()
            return
        }
        synchronized(this) {
            if (openingAa) {
                logger.log("openAndroidAuto: already opening -- ignoring concurrent call")
                return
            }
            openingAa = true
        }
        display.showStatus(CarScreen.STARTING)
        logger.log("openAndroidAuto: checking 127.0.0.1:5277 before starting")
        Thread({
            if (!AndroidAutoServer.isRunning()) {
                logger.log("openAndroidAuto: server not listening -- waiting for the driver to start it")
                openingAa = false
                main.post {
                    if (released) return@post
                    display.showStatus(CarScreen.WAITING)
                    AndroidAutoServer.notifyNeedsTap(context)
                    startServerPoll(SERVER_POLL_MS)
                }
                return@Thread
            }
            Thread.sleep(2000)
            if (!mayAutoOpenAa(released, display.screen)) {
                logger.log("openAndroidAuto: unplugged or closed while starting -- not opening")
                openingAa = false
                return@Thread
            }
            logger.log("openAndroidAuto: starting AA session probe")
            val probe = dev.caraalink.aa.AaSessionProbe(context)
            probe.onEstablished = { AndroidAutoServer.cancelNeedsTap(context) }
            probe.onGaveUp = { main.post { onAaNotResponding() } }
            probe.start()
            openingAa = false
            main.post { if (!released) showAaSurface() }
        }, "AA-server-check").start()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pollThread: Thread? = null

    private fun onAaNotResponding() {
        if (!mayAutoOpenAa(released, display.screen)) return
        logger.log("AA handshake retries used up -- server not responding")
        display.showStatus(CarScreen.NOT_RESPONDING)
        AndroidAutoServer.notifyNeedsTap(context, notResponding = true)
        startServerPoll(NOT_RESPONDING_RETRY_MS)
    }

    private fun startServerPoll(intervalMs: Long) {
        if (released || pollThread?.isAlive == true) return
        pollThread = Thread({
            try {
                while (true) {
                    Thread.sleep(intervalMs)
                    val screen = display.screen
                    val waiting = screen == CarScreen.WAITING || screen == CarScreen.NOT_RESPONDING
                    if (display.mode != CarDisplay.Mode.STATUS || !waiting) return@Thread
                    if (AndroidAutoServer.shouldAutoOpen(screen, AndroidAutoServer.isRunning())) {
                        main.post {
                            if (!released && display.mode == CarDisplay.Mode.STATUS &&
                                AndroidAutoServer.shouldAutoOpen(display.screen, true)
                            ) openAndroidAuto()
                        }
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "AA-server-poll").also { it.isDaemon = true; it.start() }
    }

    private val aaWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSettingsReconnect: Runnable? = null
    private var watchdogRestartStreak = 0
    private var inactiveSince = 0L
    private var statsAt = Triple(0L, 0L, 0L)

    private fun logStats() {
        val (req, sent, enc) = statsAt
        logger.log(
            "stats 5s: requests=${frameRequests - req} sent=${framesSent - sent} encoded=${framesEncoded - enc} " +
                "queued=${pullQueue.size} pending=$pendingRequests | ${dev.caraalink.diagnostics.PerfStats.summary()}",
        )
        statsAt = Triple(frameRequests, framesSent, framesEncoded)
    }

    private val aaWatchdogTick = object : Runnable {
        override fun run() {
            logStats()
            val probe = dev.caraalink.aa.AaSessionProbe.active
            if (display.mode == CarDisplay.Mode.AA) {
                if (probe == null) {
                    logger.log("AA watchdog: session ended on its own -- showing Closed")
                    watchdogRestartStreak = 0
                    display.showStatus(CarScreen.CLOSED)
                } else {
                    val staleMs = System.currentTimeMillis() - probe.lastVideoFrameAt
                    if (probe.isConnectionActive) inactiveSince = 0L
                    if (probe.hasReceivedVideo && !probe.isConnectionActive) {
                        val now = System.currentTimeMillis()
                        if (inactiveSince == 0L) inactiveSince = now
                        if (lostServer(connectionActive = false, inactiveForMs = now - inactiveSince)) {
                            logger.log("AA watchdog: no connection for ${now - inactiveSince}ms -- server lost")
                            inactiveSince = 0L
                            probe.stop()
                            if (mayAutoOpenAa(released, display.screen)) openAndroidAuto()
                            return
                        }
                        aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
                        return
                    }
                    if (!probe.hasReceivedVideo) {
                        val startupMs = staleMs
                        if (startupMs > AA_WATCHDOG_STARTUP_MS) {
                            logger.log("AA watchdog: no video after ${startupMs}ms startup grace -- not responding")
                            dev.caraalink.diagnostics.PerfStats.aaReconnects++
                            probe.stop()
                            onAaNotResponding()
                            return
                        }
                        aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
                        return
                    }
                    val stallThreshold = (AA_WATCHDOG_STALL_MS * (1 shl watchdogRestartStreak.coerceAtMost(3)))
                        .coerceAtMost(AA_WATCHDOG_STALL_MAX_MS)
                    if (staleMs > stallThreshold) {
                        logger.log(
                            "AA watchdog: no video frame in ${staleMs}ms (threshold ${stallThreshold}ms, " +
                                "streak $watchdogRestartStreak) -- restarting session",
                        )
                        dev.caraalink.diagnostics.PerfStats.aaReconnects++
                        watchdogRestartStreak++
                        probe.stop()
                        if (mayAutoOpenAa(released, display.screen)) openAndroidAuto()
                    } else if (staleMs < AA_WATCHDOG_CHECK_MS) {
                        watchdogRestartStreak = 0
                    }
                }
            }
            aaWatchdog.postDelayed(this, AA_WATCHDOG_CHECK_MS)
        }
    }

    private fun showAaSurface() {
        aaWatchdog.removeCallbacks(aaWatchdogTick)
        aaWatchdog.postDelayed(aaWatchdogTick, AA_WATCHDOG_CHECK_MS)
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged = {
            logger.log("AA video settings changed -- reconnecting session")
            pendingSettingsReconnect?.let { aaWatchdog.removeCallbacks(it) }
            dev.caraalink.aa.AaSessionProbe.active?.stop()
            val task = Runnable {
                pendingSettingsReconnect = null
                if (mayAutoOpenAa(released, display.screen)) openAndroidAuto()
            }
            pendingSettingsReconnect = task
            aaWatchdog.postDelayed(task, 1500)
        }
        val config = dev.caraalink.aa.AaServiceDiscovery.configFromSettings(context)
        val fillMode = dev.caraalink.settings.AppSettings.aaFillMode(context)
        logger.log(
            "AA config: resolution=${config.resolution.width}x${config.resolution.height} " +
                "fps=${config.frameRate} density=${config.density} " +
                "widthMargin=${config.widthMargin} heightMargin=${config.heightMargin} " +
                "autoCrop=${dev.caraalink.settings.AppSettings.aaAutoCrop(context)} fillMode=$fillMode " +
                "carScreen=${dev.caraalink.link.CarScreenInfo.width}x${dev.caraalink.link.CarScreenInfo.height}",
        )
        val myProbe = dev.caraalink.aa.AaSessionProbe.active
        display.showAa(
            videoWidth = config.resolution.width,
            videoHeight = config.resolution.height,
            widthMargin = config.widthMargin,
            heightMargin = config.heightMargin,
            onSurface = { surface ->
                if (surface != null) {
                    val dec = dev.caraalink.aa.AaVideoDecoder(config.resolution.width, config.resolution.height)
                    dec.start(surface)
                    aaDecoder = dec
                    myProbe?.attachVideoSink { ts, nal -> aaDecoder?.feed(ts, nal) }
                } else {
                    if (display.mode != dev.caraalink.video.CarDisplay.Mode.AA &&
                        dev.caraalink.aa.AaSessionProbe.active === myProbe
                    ) {
                        myProbe?.stop()
                    }
                    aaDecoder?.stop()
                    aaDecoder = null
                }
            },
            onTouch = { pointers, actionIndex, action ->
                dev.caraalink.aa.AaSessionProbe.active?.sendTouch(pointers, actionIndex, action)
            },
        )
    }

    // --- MirrorHost ---

    override fun onCaptureConfig(config: CaptureConfig) {
        val previous = this.config
        this.config = config
        CarScreenInfo.update(config.width, config.height)
        val fps = if (config.wantFps > 0) config.wantFps else 30
        val bitRate = if (config.bitRate > 0) config.bitRate else dev.caraalink.settings.AppSettings.videoBitrateKbps(context) * 1000
        if (encoder != null && previous == config && bitRate == lastBitRate) {
            logger.log("CONFIG_CAPTURE repeated with no change -- not rebuilding encoder")
            return
        }
        lastBitRate = bitRate
        encoder?.release()
        val enc = CarVideoEncoder(config.encodeWidth, config.encodeHeight, fps, bitRate, ::onEncodedFrame)
        try {
            enc.start()
        } catch (e: Exception) {
            logger.log("encoder start failed: $e")
            return
        }
        synchronized(frameLock) {
            codecConfig = null
            configSent = false
            pullQueue.clear()
        }
        encoder = enc
        display.attach(enc.inputSurface, config.encodeWidth, config.encodeHeight)
        if (!autoLaunchedAa) {
            autoLaunchedAa = true
            openAndroidAuto()
        }
    }

    @Volatile private var autoLaunchedAa = false

    override fun onStartVideo(): Boolean {
        encoder?.requestKeyFrame()
        return encoder != null
    }

    override fun onTouch(action: Int, x: Int, y: Int) {
        val c = config ?: return
        val enc = encoder ?: return
        val fx = x * enc.width.toFloat() / c.width
        val fy = y * enc.height.toFloat() / c.height
        display.touch(action, fx, fy)
    }

    override fun onKey(key: Int) {
        logger.log("car key $key")
        if (key == MirrorControlChannel.KEY_HOME || key == MirrorControlChannel.KEY_BACK) closeAa()
    }

    override fun onDataStreamOpen(stream: MirrorStream) {
        synchronized(frameLock) {
            dataStream = stream
            configSent = false
            waitingForKey = true
            pendingRequests = 0
            pullQueue.clear()
        }
        logger.log("data stream open; encoded so far=$framesEncoded, requesting keyframe")
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.STREAMING) }
        encoder?.requestKeyFrame()
        display.redraw()
    }

    override fun onDataStreamClosed(stream: MirrorStream) {
        synchronized(frameLock) { if (dataStream === stream) dataStream = null }
        logger.log("data stream closed; requests=$frameRequests sent=$framesSent encoded=$framesEncoded")
    }

    override fun onFrameRequested(stream: MirrorStream) {
        frameRequests++
        if (frameRequests <= 3 || frameRequests % 100 == 0L) logger.log("frame request #$frameRequests (queued=${pullQueue.size})")
        synchronized(frameLock) {
            val next = pullQueue.poll()
            if (next == null) {
                pendingRequests++
                scheduleStarvationCheck(stream)
                return
            }
            sendFrameLocked(stream, next)
        }
    }

    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleStarvationCheck(stream: MirrorStream) {
        val sentBefore = framesSent
        watchdog.postDelayed({
            synchronized(frameLock) {
                if (framesSent == sentBefore && pendingRequests > 0 && dataStream === stream) {
                    pendingRequests--
                    stream.write(MirrorDataChannel.NO_FRAME)
                    display.redraw()
                }
            }
        }, ENCODER_STALL_MS)
    }

    private fun sendFrameLocked(stream: MirrorStream, frame: ByteArray) {
        val cfg = codecConfig
        if (!configSent && cfg != null) {
            stream.write(MirrorDataChannel.frame(cfg) + MirrorDataChannel.frame(frame))
            configSent = true
        } else {
            stream.write(MirrorDataChannel.frame(frame))
        }
        framesSent++
        if (framesSent <= 3 || framesSent % 300 == 0L) logger.log("frame sent #$framesSent (${frame.size} bytes)")
    }

    private fun onEncodedFrame(data: ByteArray, isConfig: Boolean, isKey: Boolean) {
        framesEncoded++
        if (!isConfig) dev.caraalink.diagnostics.PerfStats.recordMirrorFrame(data.size)
        if (framesEncoded <= 2) logger.log("encoder output #$framesEncoded config=$isConfig key=$isKey ${data.size} bytes")
        val pull = (config?.wantFps ?: 0) == 0
        synchronized(frameLock) {
            if (isConfig) {
                codecConfig = data
                configSent = false
                return
            }
            val stream = dataStream ?: return
            if (waitingForKey) {
                if (!isKey) {
                    dev.caraalink.diagnostics.PerfStats.mirrorFramesDropped++
                    encoder?.requestKeyFrame()
                    return
                }
                waitingForKey = false
            }
            if (pull) {
                if (pendingRequests > 0) {
                    pendingRequests--
                    sendFrameLocked(stream, data)
                    return
                }
                if (pullQueue.size >= MAX_PULL_QUEUE) {
                    if (!isKey) { waitingForKey = true; encoder?.requestKeyFrame(); return }
                    dev.caraalink.diagnostics.PerfStats.mirrorFramesDropped += pullQueue.size
                    pullQueue.clear()
                }
                pullQueue.add(data)
                return
            }
            if (stream.backlogBytes() > MAX_PUSH_BACKLOG) {
                if (!isKey) { waitingForKey = true; encoder?.requestKeyFrame(); return }
                stream.clearBacklog()
            }
            sendFrameLocked(stream, data)
        }
    }

    companion object {
        private const val TAG = "CarSession"
        private const val APPSTATUS_BACKGROUND = 0x20030
        private const val MAX_PULL_QUEUE = 2
        private const val ENCODER_STALL_MS = 200L
        private const val MAX_PUSH_BACKLOG = 2 * 1024 * 1024
        private const val AA_WATCHDOG_CHECK_MS = 5_000L
        private const val AA_WATCHDOG_STALL_MS = 10_000L
        private const val AA_WATCHDOG_STARTUP_MS = 60_000L
        private const val AA_WATCHDOG_STALL_MAX_MS = 60_000L
        private const val SERVER_POLL_MS = 2_000L
        private const val NOT_RESPONDING_RETRY_MS = 5_000L
        private const val LOST_SERVER_MS = 30_000L

        internal fun mayAutoOpenAa(released: Boolean, screen: CarScreen): Boolean =
            !released && screen != CarScreen.CLOSED

        internal fun lostServer(connectionActive: Boolean, inactiveForMs: Long): Boolean =
            !connectionActive && inactiveForMs >= LOST_SERVER_MS

        internal fun isListening(port: Int): Boolean = runCatching {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
            false
        }.getOrElse { it is java.net.BindException }
    }
}