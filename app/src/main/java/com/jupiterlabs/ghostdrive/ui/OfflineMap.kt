package com.jupiterlabs.ghostdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jupiterlabs.ghostdrive.R
import com.jupiterlabs.ghostdrive.protocol.Message
import com.jupiterlabs.ghostdrive.tracking.*
import org.mapsforge.core.model.*
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.common.Observer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/*
 * Map rendering, performance layout (0.2.2):
 *  - mapsforge only draws tiles (CPU, cached, persistent on disk).
 *  - Night / Matrix looks are a GPU color matrix on the map view's hardware layer, not per-frame blend passes.
 *  - Route, places, Merlin puck and radar/Wi-Fi HUD live in a separate hardware-accelerated overlay view,
 *    so live data and puck animation never force a mapsforge re-render.
 *  - Route geometry is projected once into a fixed zoom-20 pixel space and only transformed per frame.
 *  - The puck glides between GPS fixes on vsync instead of jumping once per fix.
 */

/** Zoom-20 map size for 256 px tiles. Route geometry is cached in this pixel space, relative to the first point. */
private const val REF_MAP_SIZE = 268435456L

private fun refX(lon: Double): Double = MercatorProjection.longitudeToPixelX(lon, REF_MAP_SIZE)
private fun refY(lat: Double): Double = MercatorProjection.latitudeToPixelY(lat, REF_MAP_SIZE)

private fun routeTierIndex(f: Fix, mode: String): Int = when (mode) {
    "entropy" -> {
        val h = f.entropyShannon ?: 7.2f
        when {
            h >= 7.7f -> 2
            h >= 6.8f -> 1
            else -> 0
        }
    }
    "rf" -> {
        val rf = max(f.rfScore ?: 0, f.radarEnergy ?: 0)
        when {
            rf >= 68 -> 2
            rf >= 35 -> 1
            else -> 0
        }
    }
    else -> {
        val spd = f.speedMps ?: 0f
        when {
            spd >= 24f -> 2
            spd >= 11f -> 1
            else -> 0
        }
    }
}

private fun routeTierColor(f: Fix, mode: String): Int {
    val idx = routeTierIndex(f, mode)
    return when (mode) {
        "entropy" -> when (idx) {
            2 -> Color.rgb(0, 230, 118)   // Conditioned Peak Neon Green
            1 -> Color.rgb(0, 229, 255)   // High Cyan
            else -> Color.rgb(245, 176, 65) // Warming Amber
        }
        "rf" -> when (idx) {
            2 -> Color.rgb(255, 82, 82)   // High RF/Radar Coral Red
            1 -> Color.rgb(46, 204, 113)  // Moderate Emerald
            else -> Color.rgb(77, 150, 255) // Quiet Blue
        }
        else -> when (idx) {
            2 -> Color.rgb(245, 176, 65)  // Highway Gold
            1 -> Color.rgb(46, 204, 113)  // Cruise Emerald
            else -> Color.rgb(77, 150, 255) // City Blue
        }
    }
}

private fun lerpAngle(a: Float, b: Float, t: Float): Float {
    val d = ((b - a) % 360f + 540f) % 360f - 180f
    return ((a + d * t) % 360f + 360f) % 360f
}

/** Night look: invert against a cool white, soften saturation, then a navy glaze. Runs on the GPU. */
private fun nightTileMatrix(): ColorMatrix = ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 226f,
    0f, -1f, 0f, 0f, 232f,
    0f, 0f, -1f, 0f, 246f,
    0f, 0f, 0f, 1f, 0f
)).apply {
    postConcat(ColorMatrix().apply { setSaturation(0.45f) })
    postConcat(ColorMatrix(floatArrayOf(
        0.796f, 0f, 0f, 0f, 2f,
        0f, 0.796f, 0f, 0f, 3.7f,
        0f, 0f, 0.796f, 0f, 8.6f,
        0f, 0f, 0f, 1f, 0f
    )))
}

/** Matrix look: same math as the old DIFFERENCE + MULTIPLY + glaze passes, folded into one matrix. */
private fun matrixTileMatrix(): ColorMatrix = ColorMatrix(floatArrayOf(
    -1f, 0f, 0f, 0f, 236f,
    0f, -1f, 0f, 0f, 244f,
    0f, 0f, -1f, 0f, 238f,
    0f, 0f, 0f, 1f, 0f
)).apply {
    postConcat(ColorMatrix().apply { setScale(38f / 255f, 240f / 255f, 122f / 255f, 1f) })
    postConcat(ColorMatrix(floatArrayOf(
        0.827f, 0f, 0f, 0f, 0.35f,
        0f, 0.827f, 0f, 0f, 2.4f,
        0f, 0f, 0.827f, 0f, 1.0f,
        0f, 0f, 0f, 1f, 0f
    )))
}

/**
 * Route geometry projected once into REF_MAP_SIZE pixels relative to the first fix.
 * Live sessions append one point at a time instead of rebuilding.
 */
private class RouteGeometry(first: Fix, val colorMode: String, val solidColor: Int) {
    val originLat = first.latitude
    val originLon = first.longitude
    val originX = refX(first.longitude)
    val originY = refY(first.latitude)
    val casing = Path()
    val runs = ArrayList<Pair<Int, Path>>()
    var beaconX = FloatArray(64); private set
    var beaconY = FloatArray(64); private set
    var beaconColor = IntArray(64); private set
    var beaconCount = 0; private set
    var count = 0; private set
    var last: Fix? = null; private set
    var activeColor = solidColor; private set
    var lastX = 0f; private set
    var lastY = 0f; private set
    private var hasCasingMove = false
    private var runPoints = 0

    private fun startRun(color: Int, x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        runs.add(color to p)
        runPoints = 1
    }

    private fun closeRun() {
        if (runs.isNotEmpty() && runPoints < 2) runs.removeAt(runs.size - 1)
        runPoints = 0
    }

    private fun addBeacon(x: Float, y: Float, color: Int) {
        if (beaconCount == beaconX.size) {
            beaconX = beaconX.copyOf(beaconCount * 2)
            beaconY = beaconY.copyOf(beaconCount * 2)
            beaconColor = beaconColor.copyOf(beaconCount * 2)
        }
        beaconX[beaconCount] = x
        beaconY[beaconCount] = y
        beaconColor[beaconCount] = color
        beaconCount++
    }

    fun append(pt: Fix) {
        val x = (refX(pt.longitude) - originX).toFloat()
        val y = (refY(pt.latitude) - originY).toFloat()
        val prior = last

        // Dark casing: breaks on >30 s monotonic gaps (same rule as before).
        val casingGap = prior == null || pt.elapsedMs - prior.elapsedMs > 30_000L || pt.elapsedMs < prior.elapsedMs
        // Colored core: breaks on >10 min AND >1.5 km jumps, splits on tier color changes (same rule as before).
        val colored = colorMode != "solid"
        val c = if (colored) routeTierColor(pt, colorMode) else solidColor
        val gap = prior == null || (
            abs(pt.timeMs - prior.timeMs) > 600_000L &&
                Geo.distance(prior.latitude, prior.longitude, pt.latitude, pt.longitude) > 1500.0
            )
        // Skip sub-decimeter stationary duplicate vertices so traffic-light stops at 2Hz don't bloat the GPU Path.
        val tinyStep = prior != null && !casingGap && !gap && c == activeColor &&
            hasCasingMove && runPoints >= 1 && abs(x - lastX) < 0.15f && abs(y - lastY) < 0.15f

        if (!tinyStep) {
            if (casingGap || !hasCasingMove) {
                casing.moveTo(x, y)
                hasCasingMove = true
            } else {
                casing.lineTo(x, y)
            }

            if (gap) {
                closeRun()
                activeColor = c
                startRun(c, x, y)
            } else if (colored && c != activeColor && runPoints >= 1) {
                runs[runs.size - 1].second.lineTo(x, y)
                runPoints++
                closeRun()
                activeColor = c
                startRun(c, lastX, lastY)
                runs[runs.size - 1].second.lineTo(x, y)
                runPoints = 2
            } else {
                runs[runs.size - 1].second.lineTo(x, y)
                runPoints++
            }
            lastX = x
            lastY = y
        }

        // Entropy / RF milestone beacons (same sampling as before).
        val idx = count
        if (colorMode == "entropy") {
            if (idx % 6 == 0 && (pt.entropyShannon ?: 0f) >= 7.65f) addBeacon(x, y, Color.argb(190, 0, 230, 118))
        } else if (colorMode == "rf") {
            val rf = max(pt.rfScore ?: 0, pt.radarEnergy ?: 0)
            if (idx % 4 == 0 && rf >= 35) {
                val col = if (rf >= 65) Color.rgb(255, 82, 82) else Color.rgb(56, 189, 248)
                addBeacon(x, y, Color.argb(190, Color.red(col), Color.green(col), Color.blue(col)))
            }
        }

        last = pt
        count++
    }
}

class MapController(context: Context, path: String, backgroundColor: Int, private val onPin: (Double, Double) -> Unit) {
    val view = MapView(context)
    private val overlay = HudOverlayView(context)
    private val density = context.resources.displayMetrics.density
    private var userTouchDown = false
    private var userPanHoldUntilMs = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f

    /** Root handed to Compose: tiles underneath, GPU overlay on top, plus non-intrusive pan detection. */
    val root: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userTouchDown = true
                    touchDownX = ev.x
                    touchDownY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - touchDownX) > 10f * density || abs(ev.y - touchDownY) > 10f * density) {
                        userPanHoldUntilMs = SystemClock.uptimeMillis() + 5_000L
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouchDown = false
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    private var downloadLayer: TileDownloadLayer? = null
    private var tileCache: TileCache? = null
    private var offlineMapFile: MapFile? = null
    private var lastSelection: String? = null
    private var lastRouteSize: Int = 0
    private var lastCenterFlag: Boolean = false
    private var disposed = false
    var error: String? = null; private set
    val isOnlineMap: Boolean = path.isBlank() || !File(path).exists()

    private val merlinAvatarSizePx = (52f * density).toInt().coerceAtLeast(64)
    private val merlinCircleBitmap: Bitmap? = createCircularMerlinBitmap(context, merlinAvatarSizePx)

    private var geom: RouteGeometry? = null
    private var routeSize = 0
    private var currentPlaces: List<Place> = emptyList()
    private var sortedPlaces: List<Place> = emptyList()
    private var currentUseMerlin: Boolean = true
    private var currentIsLive: Boolean = false
    private var currentMapFilter: String = "" // "day", "night", "matrix"
    private var currentColorMode: String = "speed" // "speed", "entropy", "rf", "solid"
    private var currentWifiMotion: Message.WifiMotion? = null
    private var currentRadar: Message.RadarSummary? = null

    // ---- Puck animation state (120Hz vsync dead-reckoning + smooth error decay) ----
    private var target: Fix? = null
    private var hasShown = false
    private var shownLat = 0.0
    private var shownLon = 0.0
    private var shownBearing: Float? = null
    private var errLat = 0.0
    private var errLon = 0.0
    private var velLatPerMs = 0.0
    private var velLonPerMs = 0.0
    private var useDeadReckoning = false
    private var fromBearing: Float? = null
    private var animStartMs = 0L
    private var animDurMs = 0L
    private var lastTargetArrivalMs = 0L
    private var intervalEmaMs = 500.0
    private var follow = false
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback { onFrame() }

    /** Any camera change (pan, zoom, fling, animation) only repaints the cheap overlay. */
    private val positionObserver = Observer { overlay.postInvalidateOnAnimation() }

    /** Long-press handling stays inside mapsforge's gesture pipeline; this layer draws nothing. */
    private val gestureLayer = object : Layer() {
        override fun draw(b: BoundingBox, z: Byte, c: org.mapsforge.core.graphics.Canvas, top: Point, rotation: Rotation) {}
        override fun onLongPress(tap: LatLong, layer: Point, screen: Point): Boolean {
            onPin(tap.latitude, tap.longitude)
            return true
        }
    }

    // ---- Per-frame projection (screen = t + (ref - o) * s) ----
    private var sx = 1.0
    private var sy = 1.0
    private var tx = 0.0
    private var ty = 0.0
    private var ox = 0.0
    private var oy = 0.0
    private val routeMatrix = Matrix()
    private val tmpPath = Path()
    private val drawnLabelCenters = ArrayList<Float>()
    private val sweepRect = RectF()
    private val arcRect = RectF()
    private val labelRect = RectF()
    private val arrowPath = Path()

    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(46, 77, 150, 255)
    }
    private val accuracyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(150, 77, 150, 255)
    }
    private val placeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 245, 176, 65)
    }
    private val placeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(218, 154, 48)
    }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 15, 20, 38)
    }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(180, 166, 184, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(85, 77, 150, 255)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 210, 90)
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(46, 204, 113)
    }
    private val whiteBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 0, 0, 0)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 230, 118)
    }
    private val wifiRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val wifiGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val radarSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val radarArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val radarTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.6f * density + 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(205, 8, 12, 24)
    }
    private val routeSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.7f * density + 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private inner class HudOverlayView(ctx: Context) : View(ctx) {
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        override fun onDraw(canvas: Canvas) {
            drawHud(canvas)
        }
    }

    init {
        // Show the parent tile instantly while a sharper child tile renders.
        Parameters.PARENT_TILES_RENDERING = Parameters.ParentTilesRendering.SPEED

        view.setBackgroundColor(backgroundColor)
        view.setBuiltInZoomControls(false)
        view.setZoomLevelMin(3)
        view.setZoomLevelMax(20)
        view.mapScaleBar.isVisible = true
        view.setCenter(LatLong(44.481, -88.064))
        view.setZoomLevel(15)

        val mapFile = if(path.isNotBlank()) File(path) else null
        if(mapFile != null && mapFile.exists()) {
            try {
                val map = MapFile(mapFile)
                // Persistent cache: tiles rendered once stay on disk across launches. Key includes size + mtime so a replaced .map never shows stale tiles.
                val cacheId = "offline-map-${path.hashCode()}-${mapFile.length()}-${mapFile.lastModified()}"
                val cache = AndroidUtil.createTileCache(context, cacheId, view.model.displayModel.tileSize, 1.5f, view.model.frameBufferModel.overdrawFactor, true)
                offlineMapFile = map
                tileCache = cache
                val layer = TileRendererLayer(cache, map, view.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
                layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
                view.layerManager.layers.add(layer)
            } catch(e: Exception) {
                error = "Map file could not be opened. Falling back to OpenStreetMap."
                loadOnlineLayer(context)
            }
        } else {
            loadOnlineLayer(context)
        }

        view.layerManager.layers.add(gestureLayer)
        view.model.mapViewPosition.addObserver(positionObserver)

        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun applyTileFilter() {
        val m = when (currentMapFilter) {
            "night" -> nightTileMatrix()
            "matrix" -> matrixTileMatrix()
            else -> null
        }
        if (m == null) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        }
    }

    private fun isFollowActive(): Boolean =
        follow && !userTouchDown && SystemClock.uptimeMillis() >= userPanHoldUntilMs

    // ---------------------------------------------------------------- Puck animation

    private fun setTarget(f: Fix?, snap: Boolean) {
        if (f == null) {
            target = null
            hasShown = false
            overlay.postInvalidateOnAnimation()
            return
        }
        val prev = target
        if (!snap && hasShown && prev != null && prev.timeMs == f.timeMs && prev.elapsedMs == f.elapsedMs &&
            prev.latitude == f.latitude && prev.longitude == f.longitude) {
            target = f
            return
        }
        val now = SystemClock.uptimeMillis()
        target = f
        val jump = hasShown && Geo.distance(shownLat, shownLon, f.latitude, f.longitude) > 2000.0
        if (snap || !hasShown || jump) {
            shownLat = f.latitude
            shownLon = f.longitude
            shownBearing = usableBearing(f) ?: shownBearing
            errLat = 0.0
            errLon = 0.0
            velLatPerMs = 0.0
            velLonPerMs = 0.0
            useDeadReckoning = false
            fromBearing = shownBearing
            hasShown = true
            animDurMs = 0L
            lastTargetArrivalMs = now
            if (isFollowActive()) {
                view.model.mapViewPosition.setCenter(LatLong(shownLat, shownLon))
            }
            overlay.postInvalidateOnAnimation()
            return
        }
        val dt = (now - lastTargetArrivalMs).coerceIn(100L, 2000L)
        lastTargetArrivalMs = now
        intervalEmaMs = intervalEmaMs * 0.65 + dt * 0.35

        errLat = shownLat - f.latitude
        errLon = shownLon - f.longitude
        fromBearing = shownBearing
        animStartMs = now

        val spd = f.speedMps ?: 0f
        val isRealtimeFix = currentIsLive && abs(System.currentTimeMillis() - f.timeMs) < 6_000L
        val hdg = usableBearing(f) ?: shownBearing
        if (isRealtimeFix && spd >= 1.2f && hdg != null && hdg.isFinite()) {
            // Google Maps-style velocity dead-reckoning: smoothly decay residual error over 250ms while
            // projecting forward along the true velocity vector so the puck never lags 1s behind the car.
            val clampedSpd = spd.coerceAtMost(55f).toDouble()
            val rad = Math.toRadians(hdg.toDouble())
            val cosLat = kotlin.math.cos(Math.toRadians(f.latitude)).coerceAtLeast(0.1)
            velLatPerMs = (kotlin.math.cos(rad) * clampedSpd) / 111_320_000.0
            velLonPerMs = (kotlin.math.sin(rad) * clampedSpd) / (111_320_000.0 * cosLat)
            useDeadReckoning = true
            animDurMs = (intervalEmaMs * 1.15).toLong().coerceIn(450L, 1250L)
        } else {
            velLatPerMs = 0.0
            velLonPerMs = 0.0
            useDeadReckoning = false
            animDurMs = if (isRealtimeFix) 260L else (intervalEmaMs * 0.96).toLong().coerceIn(100L, 1500L)
        }
        scheduleFrame()
    }

    /** GPS bearing is noise below walking pace; hold the last good heading instead of spinning. */
    private fun usableBearing(f: Fix): Float? {
        val b = f.bearingDeg ?: return null
        if (!b.isFinite()) return null
        val spd = f.speedMps
        if (spd != null && spd < 1.0f) return null
        return b
    }

    private fun scheduleFrame() {
        if (!frameScheduled && !disposed) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame() {
        frameScheduled = false
        if (disposed) return
        val f = target ?: return
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - animStartMs).coerceAtLeast(0L)
        val done: Boolean
        if (useDeadReckoning) {
            val u = (elapsed.toFloat() / 250f).coerceIn(0f, 1f)
            val decay = (1f - u) * (1f - u)
            val projMs = elapsed.coerceAtMost(animDurMs).toDouble()
            shownLat = f.latitude + errLat * decay + velLatPerMs * projMs
            shownLon = f.longitude + errLon * decay + velLonPerMs * projMs
            val bu = (elapsed.toFloat() / 200f).coerceIn(0f, 1f)
            val tb = usableBearing(f)
            val fb = fromBearing
            shownBearing = when {
                tb == null -> fb
                fb == null -> tb
                else -> lerpAngle(fb, tb, bu)
            }
            done = elapsed >= animDurMs
        } else {
            val t = if (animDurMs <= 0L) 1f else (elapsed.toFloat() / animDurMs).coerceIn(0f, 1f)
            val ease = 1f - (1f - t) * (1f - t)
            shownLat = f.latitude + errLat * (1f - ease)
            shownLon = f.longitude + errLon * (1f - ease)
            val tb = usableBearing(f)
            val fb = fromBearing
            shownBearing = when {
                tb == null -> fb
                fb == null -> tb
                else -> lerpAngle(fb, tb, ease)
            }
            done = t >= 1f
        }
        // Full 120Hz vsync camera lock so tiles and puck move in unison without 30Hz judder.
        if (isFollowActive()) {
            view.model.mapViewPosition.setCenter(LatLong(shownLat, shownLon))
        }
        overlay.invalidate()
        if (!done) scheduleFrame()
    }

    // ---------------------------------------------------------------- Route cache

    /** Returns true when the drawable route changed. */
    private fun syncRoute(route: List<Fix>): Boolean {
        val solid = if (currentMapFilter == "matrix") Color.rgb(0, 230, 118) else Color.rgb(103, 110, 220)
        if (route.isEmpty()) {
            val changed = geom != null
            geom = null
            routeSize = 0
            return changed
        }
        val g = geom
        val first = route[0]
        val sameBase = g != null && g.colorMode == currentColorMode && g.solidColor == solid &&
            g.originLat == first.latitude && g.originLon == first.longitude && g.count <= route.size &&
            g.count > 0 && route[g.count - 1] == g.last
        if (sameBase) {
            if (g.count == route.size) return false
            for (i in g.count until route.size) g.append(route[i])
            routeSize = route.size
            return true
        }
        val ng = RouteGeometry(first, currentColorMode, solid)
        for (pt in route) ng.append(pt)
        geom = ng
        routeSize = route.size
        return true
    }

    // ---------------------------------------------------------------- Drawing (GPU overlay)

    private fun screenX(lon: Double): Float = (tx + (refX(lon) - ox) * sx).toFloat()
    private fun screenY(lat: Double): Float = (ty + (refY(lat) - oy) * sy).toFloat()
    private fun metersPx(m: Float, lat: Double): Float = (MercatorProjection.metersToPixels(m, lat, REF_MAP_SIZE) * sx).toFloat()

    private fun canDrawLabelAt(lx: Float, ly: Float): Boolean {
        var i = 0
        while (i < drawnLabelCenters.size) {
            if (abs(lx - drawnLabelCenters[i]) < 115f * density && abs(ly - drawnLabelCenters[i + 1]) < 26f * density) return false
            i += 2
        }
        drawnLabelCenters.add(lx)
        drawnLabelCenters.add(ly)
        return true
    }

    private fun drawHud(canvas: Canvas) {
        if (disposed) return
        val w = overlay.width
        val h = overlay.height
        if (w <= 0 || h <= 0) return
        val pos = view.model.mapViewPosition
        val center = pos.center ?: return
        val tileSize = view.model.displayModel.tileSize
        val scaleFactor = pos.scaleFactor
        val s = if (scaleFactor.isFinite() && scaleFactor > 0.0) {
            (scaleFactor * tileSize.toDouble()) / REF_MAP_SIZE.toDouble()
        } else {
            MercatorProjection.getMapSize(pos.zoomLevel, tileSize).toDouble() / REF_MAP_SIZE.toDouble()
        }
        if (!s.isFinite() || s <= 0.0) return
        val g = geom
        val oLat = g?.originLat ?: center.latitude
        val oLon = g?.originLon ?: center.longitude
        val oX = g?.originX ?: refX(oLon)
        val oY = g?.originY ?: refY(oLat)
        val cx = w * 0.5 + view.offsetX
        val cy = h * 0.5 + view.offsetY
        sx = s; sy = s; ox = oX; oy = oY
        tx = cx + (oX - refX(center.longitude)) * s
        ty = cy + (oY - refY(center.latitude)) * s
        val z = pos.zoomLevel.toInt()

        // 0. Route polylines (dark casing + multi-mode colored core + 120Hz live puck connector)
        if (g != null && g.count >= 1) {
            val hasLiveTail = currentIsLive && hasShown && target != null
            val lastSx = (tx + g.lastX * sx).toFloat()
            val lastSy = (ty + g.lastY * sy).toFloat()
            val puckSx = if (hasLiveTail) screenX(shownLon) else lastSx
            val puckSy = if (hasLiveTail) screenY(shownLat) else lastSy
            val tailDistPx = kotlin.math.hypot((puckSx - lastSx).toDouble(), (puckSy - lastSy).toDouble()).toFloat()
            val drawTail = hasLiveTail && tailDistPx in 0.5f..(180f * density)

            if (g.count >= 2) {
                routeMatrix.setScale(sx.toFloat(), sy.toFloat())
                routeMatrix.postTranslate(tx.toFloat(), ty.toFloat())
                g.casing.transform(routeMatrix, tmpPath)
                if (drawTail) tmpPath.lineTo(puckSx, puckSy)
                canvas.drawPath(tmpPath, routeCasingPaint)
                for (i in 0 until g.runs.size) {
                    val (color, p) = g.runs[i]
                    p.transform(routeMatrix, tmpPath)
                    if (drawTail && i == g.runs.size - 1) tmpPath.lineTo(puckSx, puckSy)
                    routeSegmentPaint.color = color
                    canvas.drawPath(tmpPath, routeSegmentPaint)
                }
            } else if (drawTail) {
                canvas.drawLine(lastSx, lastSy, puckSx, puckSy, routeCasingPaint)
                routeSegmentPaint.color = g.activeColor
                canvas.drawLine(lastSx, lastSy, puckSx, puckSy, routeSegmentPaint)
            }
        }

        // 1. Saved places / home bases & decluttered visited stops
        drawnLabelCenters.clear()
        val cullPad = 180f * density
        for (p in sortedPlaces) {
            val px = screenX(p.longitude)
            val py = screenY(p.latitude)
            if (p.userDefined) {
                val radiusPx = metersPx(p.radiusM.toFloat(), p.latitude).coerceAtLeast(10f * density)
                if (px + radiusPx < -cullPad || px - radiusPx > w + cullPad ||
                    py + radiusPx < -cullPad || py - radiusPx > h + cullPad) continue
                canvas.drawCircle(px, py, radiusPx, placeFill)
                canvas.drawCircle(px, py, radiusPx, placeStroke)
                canvas.drawCircle(px, py, 5f * density, placeStroke)
                if (p.name.isNotBlank() && z >= 11) {
                    val labelY = py - radiusPx.coerceAtMost(36f * density) - 10f * density
                    if (canDrawLabelAt(px, labelY)) drawPillLabel(canvas, px, labelY, "★ ${p.name}", Color.rgb(245, 176, 65))
                }
            } else {
                if (px < -cullPad || px > w + cullPad || py < -cullPad || py > h + cullPad) continue
                nodePaint.color = Color.argb(215, 56, 189, 248)
                canvas.drawCircle(px, py, 5.5f * density, nodePaint)
                canvas.drawCircle(px, py, 2.4f * density, whiteBezelPaint)
                if (p.name.isNotBlank() && z >= 15) {
                    val labelY = py - 14f * density
                    if (canDrawLabelAt(px, labelY)) drawPillLabel(canvas, px, labelY, p.name, Color.rgb(56, 189, 248))
                }
            }
        }

        // 2. Entropy / RF milestone beacon nodes along the route
        if (g != null && g.count >= 4 && z >= 13 && g.beaconCount > 0) {
            val bezel = if (g.colorMode == "entropy") 2.5f * density else 2.2f * density
            val bx = g.beaconX; val by = g.beaconY; val bc = g.beaconColor
            val bPad = 16f * density
            for (i in 0 until g.beaconCount) {
                val nx = (tx + bx[i] * sx).toFloat()
                val ny = (ty + by[i] * sy).toFloat()
                if (nx < -bPad || nx > w + bPad || ny < -bPad || ny > h + bPad) continue
                nodePaint.color = bc[i]
                canvas.drawCircle(nx, ny, 5.5f * density, nodePaint)
                canvas.drawCircle(nx, ny, bezel, whiteBezelPaint)
            }
        }

        // 3. Route start marker (the cache origin is the first fix)
        if (g != null && g.count >= 2) {
            val sxp = tx.toFloat()
            val syp = ty.toFloat()
            canvas.drawCircle(sxp, syp + 2f * density, 9f * density, shadowPaint)
            outerRingPaint.color = Color.rgb(46, 204, 113)
            canvas.drawCircle(sxp, syp, 8.5f * density, outerRingPaint)
            canvas.drawCircle(sxp, syp, 4.5f * density, whiteBezelPaint)
            if (z >= 13) drawPillLabel(canvas, sxp, syp - 18f * density, "START", Color.rgb(46, 204, 113))
        }

        // 4. Merlin co-pilot avatar / navigation puck at the animated position
        val f = target ?: return
        if (!hasShown) return
        val fx = screenX(shownLon)
        val fy = screenY(shownLat)
        val accPx = metersPx(f.accuracyM.coerceAtLeast(12f), f.latitude).coerceIn(14f * density, 160f * density)
        canvas.drawCircle(fx, fy, accPx, accuracyFill)
        canvas.drawCircle(fx, fy, accPx, accuracyStroke)

        val tierRingColor = routeTierColor(f, currentColorMode)
        val avatarRadius = merlinAvatarSizePx / 2f
        val bearing = shownBearing

        // A. WiFi spatial motion disturbance field (concentric ripples)
        val wifiScore = currentWifiMotion?.score ?: f.rfScore ?: 0
        if (wifiScore > 8) {
            val intensity = (wifiScore / 100f).coerceIn(0.12f, 1f)
            val rippleColor = when {
                wifiScore >= 65 -> Color.rgb(255, 82, 82)
                wifiScore >= 35 -> Color.rgb(245, 176, 65)
                else -> Color.rgb(56, 189, 248)
            }
            wifiGlowPaint.color = Color.argb((36 * intensity).toInt(), Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor))
            canvas.drawCircle(fx, fy, avatarRadius + 38f * density * intensity, wifiGlowPaint)
            val numRings = if (wifiScore > 50) 3 else 2
            for (i in 1..numRings) {
                val ringRadius = avatarRadius + (11f * i + 4f) * density * intensity
                val alpha = (150 * intensity * (1f - i.toFloat() / (numRings + 1))).toInt().coerceIn(25, 210)
                wifiRipplePaint.color = Color.argb(alpha, Color.red(rippleColor), Color.green(rippleColor), Color.blue(rippleColor))
                canvas.drawCircle(fx, fy, ringRadius, wifiRipplePaint)
            }
        }

        // B. mmWave radar scanning cone, distance arcs & target blip
        val radarEnergy = currentRadar?.movingEnergy ?: f.radarEnergy ?: 0
        val targetDetected = (currentRadar?.target ?: 0) > 0 || radarEnergy >= 15
        val detectionCm = (currentRadar?.movingCm ?: currentRadar?.detectionCm ?: 0).coerceAtLeast(0)
        val radarColor = when {
            radarEnergy >= 65 -> Color.rgb(255, 82, 82)
            radarEnergy >= 30 -> Color.rgb(245, 176, 65)
            else -> Color.rgb(0, 230, 118)
        }

        if (bearing != null && bearing.isFinite()) {
            canvas.save()
            canvas.rotate(bearing, fx, fy)
            val radarMaxRangePx = (avatarRadius * 2.8f).coerceAtLeast(54f * density)
            sweepRect.set(fx - radarMaxRangePx, fy - radarMaxRangePx, fx + radarMaxRangePx, fy + radarMaxRangePx)
            radarSweepPaint.color = Color.argb((40 + radarEnergy * 0.5f).toInt().coerceIn(30, 115), Color.red(radarColor), Color.green(radarColor), Color.blue(radarColor))
            canvas.drawArc(sweepRect, -120f, 60f, true, radarSweepPaint)

            radarArcPaint.color = Color.argb(85, Color.red(radarColor), Color.green(radarColor), Color.blue(radarColor))
            val r1 = radarMaxRangePx * 0.4f
            val r2 = radarMaxRangePx * 0.7f
            arcRect.set(fx - r1, fy - r1, fx + r1, fy + r1)
            canvas.drawArc(arcRect, -120f, 60f, false, radarArcPaint)
            arcRect.set(fx - r2, fy - r2, fx + r2, fy + r2)
            canvas.drawArc(arcRect, -120f, 60f, false, radarArcPaint)
            canvas.drawArc(sweepRect, -120f, 60f, false, radarArcPaint)

            if (targetDetected) {
                val distFraction = if (detectionCm > 0) (detectionCm / 650f).coerceIn(0.28f, 0.92f) else 0.55f
                val targetY = fy - radarMaxRangePx * distFraction
                radarTargetPaint.color = Color.argb(220, Color.red(radarColor), Color.green(radarColor), Color.blue(radarColor))
                canvas.drawCircle(fx, targetY, 6f * density, radarTargetPaint)
                canvas.drawCircle(fx, targetY, 2.5f * density, whiteBezelPaint)
                val targetPulseR = 9f * density + (radarEnergy / 100f) * 6f * density
                radarArcPaint.color = Color.argb(170, Color.red(radarColor), Color.green(radarColor), Color.blue(radarColor))
                canvas.drawCircle(fx, targetY, targetPulseR, radarArcPaint)
            }

            val beamRadius = avatarRadius * 2.35f
            arcRect.set(fx - beamRadius, fy - beamRadius, fx + beamRadius, fy + beamRadius)
            conePaint.color = Color.argb(72, Color.red(tierRingColor), Color.green(tierRingColor), Color.blue(tierRingColor))
            canvas.drawArc(arcRect, -125f, 70f, true, conePaint)

            val tipY = fy - avatarRadius - 13f * density
            val baseRingY = fy - avatarRadius - 2f * density
            arrowPath.rewind()
            arrowPath.moveTo(fx, tipY)
            arrowPath.lineTo(fx + 8.5f * density, baseRingY)
            arrowPath.lineTo(fx, baseRingY - 3.5f * density)
            arrowPath.lineTo(fx - 8.5f * density, baseRingY)
            arrowPath.close()
            arrowPaint.color = tierRingColor
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.restore()
        }

        if (currentUseMerlin && merlinCircleBitmap != null) {
            canvas.drawCircle(fx, fy + 3f * density, avatarRadius + 5.5f * density, shadowPaint)
            outerRingPaint.color = tierRingColor
            canvas.drawCircle(fx, fy, avatarRadius + 5f * density, outerRingPaint)
            canvas.drawCircle(fx, fy, avatarRadius + 2.2f * density, whiteBezelPaint)
            canvas.drawBitmap(merlinCircleBitmap, fx - avatarRadius, fy - avatarRadius, bitmapPaint)
        } else {
            val puckR = 18f * density
            canvas.drawCircle(fx, fy + 2.5f * density, puckR + 3f * density, shadowPaint)
            outerRingPaint.color = tierRingColor
            canvas.drawCircle(fx, fy, puckR + 2.5f * density, outerRingPaint)
            badgeBg.color = Color.rgb(18, 25, 48)
            canvas.drawCircle(fx, fy, puckR, badgeBg)
            canvas.save()
            canvas.rotate(bearing ?: 0f, fx, fy)
            arrowPath.rewind()
            arrowPath.moveTo(fx, fy - puckR * 0.65f)
            arrowPath.lineTo(fx + puckR * 0.5f, fy + puckR * 0.5f)
            arrowPath.lineTo(fx, fy + puckR * 0.2f)
            arrowPath.lineTo(fx - puckR * 0.5f, fy + puckR * 0.5f)
            arrowPath.close()
            canvas.drawPath(arrowPath, whiteBezelPaint)
            canvas.restore()
        }
    }

    private fun drawPillLabel(canvas: Canvas, cx: Float, cy: Float, text: String, borderColor: Int) {
        val padH = 8f * density
        val padV = 4f * density
        val textW = labelPaint.measureText(text)
        labelRect.set(cx - textW / 2f - padH, cy - 11f * density - padV, cx + textW / 2f + padH, cy + 3f * density + padV)
        badgeBg.color = Color.argb(225, 13, 16, 33)
        badgeStroke.color = borderColor
        canvas.drawRoundRect(labelRect, 10f * density, 10f * density, badgeBg)
        canvas.drawRoundRect(labelRect, 10f * density, 10f * density, badgeStroke)
        canvas.drawText(text, cx, cy, labelPaint)
    }

    private fun createCircularMerlinBitmap(context: Context, sizePx: Int): Bitmap? {
        return runCatching {
            val raw = BitmapFactory.decodeResource(context.resources, R.drawable.merlin) ?: return null
            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val r = sizePx / 2f
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.rgb(20, 26, 46)
            }
            c.drawCircle(r, r, r, bgPaint)
            val shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = max(sizePx.toFloat() / raw.width, sizePx.toFloat() / raw.height)
            val dx = (sizePx - raw.width * scale) * 0.5f
            val dy = (sizePx - raw.height * scale) * 0.22f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            shader.setLocalMatrix(matrix)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            c.drawCircle(r, r, r, paint)
            if (raw != out && !raw.isRecycled) raw.recycle()
            out
        }.getOrNull()
    }

    private fun loadOnlineLayer(context: Context) {
        try {
            val tileSource = org.mapsforge.map.layer.download.tilesource.OnlineTileSource(
                arrayOf("tile.openstreetmap.org"), 443
            ).apply {
                name = "OpenStreetMap"
                protocol = "https"
                baseUrl = "/"
                extension = "png"
                zoomLevelMin = 0.toByte()
                zoomLevelMax = 19.toByte()
                parallelRequestsLimit = 6
                userAgent = "GhostDrive-Android/0.3.0 (contact: support@jupiterlabs.com)"
            }
            // Persistent so tiles you've already seen load instantly next drive (and offline).
            val cache = AndroidUtil.createTileCache(context, "map-tiles-osm-v1", view.model.displayModel.tileSize, 1.5f, view.model.frameBufferModel.overdrawFactor, true)
            tileCache = cache
            val dl = TileDownloadLayer(cache, view.model.mapViewPosition, tileSource, AndroidGraphicFactory.INSTANCE)
            downloadLayer = dl
            view.layerManager.layers.add(dl)
            dl.onResume()
        } catch(e: Exception) {
            error = "Could not initialize online map layer: ${e.message}"
        }
    }

    fun zoomIn() {
        view.model.mapViewPosition.zoomIn()
    }

    fun zoomOut() {
        view.model.mapViewPosition.zoomOut()
    }

    fun centerOn(lat: Double, lon: Double) {
        userPanHoldUntilMs = 0L
        val cLat = if (hasShown) shownLat else lat
        val cLon = if (hasShown) shownLon else lon
        view.setCenter(LatLong(cLat, cLon))
    }

    fun fitRoute(route: List<Fix>, fallbackFix: Fix?) {
        userPanHoldUntilMs = SystemClock.uptimeMillis() + 8_000L
        if (route.size < 2) {
            val tgt = route.firstOrNull() ?: fallbackFix ?: return
            view.setCenter(LatLong(tgt.latitude, tgt.longitude))
            view.setZoomLevel(16)
            return
        }
        val minLat = route.minOf { it.latitude }
        val maxLat = route.maxOf { it.latitude }
        val minLon = route.minOf { it.longitude }
        val maxLon = route.maxOf { it.longitude }
        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0
        val span = max(maxLat - minLat, maxLon - minLon).coerceAtLeast(0.0008)
        val zoom = (14.2 - ln(span / 0.01) / ln(2.0)).toInt().coerceIn(5, 18).toByte()
        view.setCenter(LatLong(centerLat, centerLon))
        view.setZoomLevel(zoom)
    }

    fun update(
        route: List<Fix>,
        places: List<Place>,
        last: Fix?,
        selection: String,
        center: Boolean,
        routeColorMode: String = "speed",
        mapFilter: String = "night",
        useMerlinAvatar: Boolean = true,
        isLiveSession: Boolean = false,
        wifiMotion: Message.WifiMotion? = null,
        radar: Message.RadarSummary? = null
    ) {
        if (disposed) return
        var dirty = false
        if (places !== currentPlaces) {
            if (places != currentPlaces) {
                sortedPlaces = places.sortedByDescending { it.userDefined }
                dirty = true
            }
            currentPlaces = places
        }
        if (useMerlinAvatar != currentUseMerlin) { currentUseMerlin = useMerlinAvatar; dirty = true }
        if (isLiveSession != currentIsLive) { currentIsLive = isLiveSession; dirty = true }
        if (mapFilter != currentMapFilter) { currentMapFilter = mapFilter; applyTileFilter(); dirty = true }
        if (routeColorMode != currentColorMode) { currentColorMode = routeColorMode; dirty = true }
        if (wifiMotion != currentWifiMotion) { currentWifiMotion = wifiMotion; dirty = true }
        if (radar != currentRadar) { currentRadar = radar; dirty = true }
        if (syncRoute(route)) dirty = true

        val selectionChanged = lastSelection != selection
        val routeJustLoaded = lastRouteSize < 2 && route.size >= 2
        val centerJustEnabled = center && !lastCenterFlag
        if (centerJustEnabled || selectionChanged) {
            userPanHoldUntilMs = 0L
        }
        follow = center

        setTarget(last, snap = selectionChanged)

        if ((selectionChanged || routeJustLoaded) && route.size >= 2 && !isLiveSession) {
            fitRoute(route, last)
        } else if (last != null && hasShown && (selectionChanged || centerJustEnabled)) {
            view.setCenter(LatLong(shownLat, shownLon))
        } else if (selectionChanged && last == null && places.isNotEmpty()) {
            val firstPlace = places.first()
            view.setCenter(LatLong(firstPlace.latitude, firstPlace.longitude))
        }

        lastSelection = selection
        lastRouteSize = route.size
        lastCenterFlag = center

        // Only the overlay repaints for data changes. Tiles redraw on their own when the camera moves.
        if (dirty) overlay.invalidate()
    }

    fun dispose() {
        disposed = true
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        runCatching { view.model.mapViewPosition.removeObserver(positionObserver) }
        runCatching {
            downloadLayer?.onPause()
            downloadLayer = null
            view.destroyAll()
            offlineMapFile?.close()
            offlineMapFile = null
            tileCache = null
        }
    }
}

fun resolveMapFilter(mapStyle: String, theme: String): String {
    return when (mapStyle) {
        "night", "matrix", "day" -> mapStyle
        else -> when (theme) {
            "matrix" -> "matrix"
            "merlin" -> "day"
            else -> "night"
        }
    }
}

@Composable fun OfflineMap(
    path: String,
    route: List<Fix>,
    places: List<Place>,
    last: Fix?,
    selection: String,
    center: Boolean,
    modifier: Modifier = Modifier,
    routeColorMode: String = "speed",
    mapFilter: String = "night",
    displayUnits: String = "mi",
    useMerlinAvatar: Boolean = true,
    isFullscreen: Boolean = false,
    isLiveSession: Boolean = false,
    wifiMotion: Message.WifiMotion? = null,
    radar: Message.RadarSummary? = null,
    legendBottomPadding: Dp = 10.dp,
    onToggleFullscreen: (() -> Unit)? = null,
    onPin: (Double, Double) -> Unit
) {
    val callback by rememberUpdatedState(onPin)
    var mapError by remember(path) { mutableStateOf<String?>(null) }
    var controller by remember { mutableStateOf<MapController?>(null) }
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    Box(modifier = modifier) {
        key(path, backgroundColor) {
            AndroidView(
                factory = { context ->
                    MapController(context, path, backgroundColor) { a, b -> callback(a, b) }.also {
                        mapError = it.error
                        controller = it
                        it.root.tag = it
                    }.root
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    (it.tag as? MapController)?.dispose()
                    controller = null
                },
                update = {
                    (it.tag as? MapController)?.update(
                        route, places, last, selection, center,
                        routeColorMode, mapFilter, useMerlinAvatar, isLiveSession,
                        wifiMotion, radar
                    )
                }
            )
        }

        // Real-Time Radar & WiFi Motion Telemetry Badge
        val liveWifiScore = wifiMotion?.score ?: last?.rfScore
        val liveRadarEnergy = radar?.movingEnergy ?: last?.radarEnergy
        val liveRadarTarget = (radar?.target ?: 0) > 0 || (liveRadarEnergy ?: 0) >= 15
        val liveRadarDist = radar?.movingCm ?: radar?.detectionCm

        if (liveWifiScore != null || liveRadarEnergy != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📡", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if (liveRadarTarget) "RADAR ${liveRadarDist?.let { "${it}cm" } ?: "TARGET"} (${liveRadarEnergy ?: 0}%)" else "RADAR CLEAR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (liveRadarTarget) androidx.compose.ui.graphics.Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text("·", color = MaterialTheme.colorScheme.outline)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📶", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = if ((liveWifiScore ?: 0) > 15) "WIFI MOTION ${liveWifiScore}%" else "WIFI QUIET",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if ((liveWifiScore ?: 0) > 35) androidx.compose.ui.graphics.Color(0xFFF5B041) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Floating Map Controls on Bottom-End (or Center-End in Fullscreen Mode)
        Column(
            modifier = Modifier
                .align(if (isFullscreen) Alignment.CenterEnd else Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onToggleFullscreen != null) {
                FilledIconButton(
                    onClick = onToggleFullscreen,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen Map"
                    )
                }
            }
            if (route.size >= 2) {
                FilledIconButton(
                    onClick = { controller?.fitRoute(route, last) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(Icons.Outlined.FitScreen, contentDescription = "Fit Full Route")
                }
            }
            last?.let { fix ->
                FilledIconButton(
                    onClick = { controller?.centerOn(fix.latitude, fix.longitude) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Center on Merlin")
                }
            }
            FilledIconButton(
                onClick = { controller?.zoomIn() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Zoom In")
            }
            FilledIconButton(
                onClick = { controller?.zoomOut() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Zoom Out")
            }
        }

        // Dynamic Legend Pill at Bottom-Start (embedded) or Top-Start below banner
        if (routeColorMode != "solid" && route.size >= 2) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(PaddingValues(start = if (isFullscreen) 14.dp else 10.dp, bottom = legendBottomPadding))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (routeColorMode) {
                        "entropy" -> {
                            Text("● <6.8 b/B", color = androidx.compose.ui.graphics.Color(0xFFF5B041), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● 6.8–7.7", color = androidx.compose.ui.graphics.Color(0xFF00E5FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● ≥7.7 SHA3", color = androidx.compose.ui.graphics.Color(0xFF00E676), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        "rf" -> {
                            Text("● Quiet RF", color = androidx.compose.ui.graphics.Color(0xFF4D96FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● Active RF", color = androidx.compose.ui.graphics.Color(0xFF2ECC71), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● Radar/RF Peak", color = androidx.compose.ui.graphics.Color(0xFFFF5252), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        else -> {
                            Text("● City", color = androidx.compose.ui.graphics.Color(0xFF4D96FF), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● Cruise", color = androidx.compose.ui.graphics.Color(0xFF2ECC71), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("● Hwy", color = androidx.compose.ui.graphics.Color(0xFFF5B041), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (!isFullscreen) {
            controller?.let { c ->
                val modeName = when (mapFilter) {
                    "matrix" -> "Matrix Green Map"
                    "night" -> "Night Cockpit Map"
                    else -> "Day Map"
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "$modeName · ${if(c.isOnlineMap) "OSM" else "Offline"}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            last?.let { f ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "${speed(f.speedMps, displayUnits)} · ${Geo.cardinal(f.bearingDeg)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
    mapError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
