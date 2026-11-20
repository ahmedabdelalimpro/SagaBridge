package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

class AaCarPresentation(
    context: Context,
    display: Display,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val widthMargin: Int = 0,
    private val heightMargin: Int = 0,
    private val onSurface: (Surface?) -> Unit,
    private val onTouch: (pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit,
) : Presentation(context, display) {

    private var surface: Surface? = null
    private val touchLogger = dev.caraalink.diagnostics.DiagnosticLogger(context, "aa-touch")

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val area = FrameLayout(context)
        val texture = TextureView(context)
        val starting = TextView(context).apply {
            setText(dev.caraalink.R.string.car_starting)
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(videoWidth, videoHeight)
                surface = Surface(st).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                onSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (starting.visibility != View.GONE) starting.visibility = View.GONE
            }
        }
        var lastMoveAt = 0L
        texture.setOnTouchListener { v, ev ->
            if (v.width <= 0 || v.height <= 0) return@setOnTouchListener true
            val action = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
                MotionEvent.ACTION_MOVE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastMoveAt < 30) return@setOnTouchListener true
                    lastMoveAt = now
                    2
                }
                else -> return@setOnTouchListener true
            }
            val contentWidth = (videoWidth - widthMargin).coerceAtLeast(1)
            val contentHeight = (videoHeight - heightMargin).coerceAtLeast(1)
            val pointers = (0 until ev.pointerCount).map { i ->
                val fullX = (ev.getX(i) / v.width * videoWidth).toInt()
                val fullY = (ev.getY(i) / v.height * videoHeight).toInt()
                val x = (fullX - widthMargin / 2).coerceIn(0, contentWidth - 1)
                val y = (fullY - heightMargin / 2).coerceIn(0, contentHeight - 1)
                Triple(x, y, ev.getPointerId(i))
            }
            if (pointers.isEmpty()) return@setOnTouchListener true
            if (action == 0) {
                touchLogger.log(
                    "DOWN rawX=${ev.getX(0)} rawY=${ev.getY(0)} viewW=${v.width} viewH=${v.height} " +
                        "videoW=$videoWidth videoH=$videoHeight widthMargin=$widthMargin heightMargin=$heightMargin " +
                        "-> mappedX=${pointers[0].first} mappedY=${pointers[0].second}",
                )
            }
            onTouch(pointers, ev.actionIndex, action)
            true
        }
        area.clipChildren = true
        area.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val aw = r - l
            val ah = b - t
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val contentWidth = (videoWidth - widthMargin).coerceAtLeast(1)
            val contentHeight = (videoHeight - heightMargin).coerceAtLeast(1)
            val scale = if (dev.caraalink.settings.AppSettings.aaFillMode(context) == dev.caraalink.settings.AppSettings.AaFillMode.FILL) {
                maxOf(aw.toFloat() / contentWidth, ah.toFloat() / contentHeight)
            } else {
                minOf(aw.toFloat() / contentWidth, ah.toFloat() / contentHeight)
            }
            val w = (videoWidth * scale).toInt()
            val h = (videoHeight * scale).toInt()
            val lp = texture.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                texture.post { texture.layoutParams = lp }
            }
        }
        root.addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(starting, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dev.caraalink.diagnostics.PerfStats.attachHud(context, root)
        setContentView(root)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()
}