package com.jupiterlabs.ghostdrive.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class WatchMainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startHarvesterServiceExplicit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ghostwatch5_prefs", Context.MODE_PRIVATE)
        val userStopped = prefs.getBoolean("user_stopped", false)
        if (!userStopped && hasRequiredPermissions() && !WatchHarvestService.state.value.harvesting) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_INIT)
            )
        }

        setContent {
            val st by WatchHarvestService.state.collectAsState()
            val isRunning = st.harvesting && !st.userStopped && st.watchEnabled
            val isActivelyBatching = isRunning && (st.nullvalonOnline || st.standbyBatchMode)

            val accentColor = when {
                !isRunning -> Color(0xFFFF5252)
                st.activeSamplingBurst -> Color(0xFF00E676)
                st.nullvalonOnline -> Color(0xFF00E676)
                st.standbyBatchMode -> Color(0xFF38BDF8)
                else -> Color(0xFFF5B041)
            }

            val bezelProgress = when {
                !isRunning -> 0f
                st.activeSamplingBurst -> 1f
                isActivelyBatching -> {
                    val total = st.batchIntervalSec.coerceAtLeast(1).toFloat()
                    ((total - st.secondsUntilNextBatch.toFloat()) / total).coerceIn(0.08f, 1f)
                }
                else -> 0.15f
            }

            val spanMins = ((System.currentTimeMillis() - st.intervalStartMs).coerceAtLeast(0L) / 60_000L)
            val spanLabel = if (spanMins >= 60) "${spanMins / 60}h ${spanMins % 60}m" else "${spanMins}m"

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF05080D),
                    surface = Color(0xFF0D141F)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.14f),
                                    Color(0xFF070C14),
                                    Color(0xFF04060A)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Circular Radial Cockpit Bezel Gauge around the Galaxy Watch 5 screen
                    CockpitBezelRing(
                        progress = bezelProgress,
                        accentColor = accentColor,
                        activeBurst = st.activeSamplingBurst
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Spacer(Modifier.height(6.dp))

                        // Merlin Cockpit Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .border(BorderStroke(1.5.dp, accentColor), CircleShape)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF101926))
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.merlin),
                                    contentDescription = "Merlin Co-Pilot",
                                    contentScale = ContentScale.Crop,
                                    alignment = Alignment.TopCenter,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "GHOST DRIVE",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = if (st.phoneConnected) "WATCH 5 · BT LINKED" else "WATCH 5 · COCKPIT",
                                    color = accentColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Glowing Mode Status Pill
                        Surface(
                            color = accentColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
                        ) {
                            val badgeLabel = when {
                                !isRunning -> "⏹ STOPPED · 0% DRAIN"
                                st.activeSamplingBurst -> "⚡ SAMPLING 2s SNAPSHOT…"
                                st.nullvalonOnline -> "🚗 NULLVALON LIVE · ${st.secondsUntilNextBatch}s"
                                st.standbyBatchMode -> "⌚ STANDBY BATCH · ${st.secondsUntilNextBatch}s"
                                else -> "⏸ CAR STANDBY · 0% DRAIN"
                            }
                            Text(
                                text = badgeLabel,
                                color = accentColor,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.5.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        // 2x2 Cockpit Telemetry Bento Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CockpitMetricTile(
                                modifier = Modifier.weight(1f),
                                label = "CARDIAC",
                                primary = "${st.hrBpm?.toInt() ?: st.avgHrBpm?.toInt() ?: "—"} BPM",
                                secondary = "IBI ${st.ibiMs ?: st.avgIbiMs ?: "—"}ms · Avg ${st.avgHrBpm?.toInt() ?: "—"}",
                                accent = Color(0xFFFF6B8B)
                            )
                            CockpitMetricTile(
                                modifier = Modifier.weight(1f),
                                label = "BIO / BARO",
                                primary = "🩸 ${st.spo2Pct ?: st.avgSpo2Pct ?: 98}%",
                                secondary = st.pressureHpa?.let { String.format(Locale.US, "%.0f hPa", it) } ?: "1013 hPa",
                                accent = Color(0xFF38BDF8)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CockpitMetricTile(
                                modifier = Modifier.weight(1f),
                                label = "KINEMATICS",
                                primary = st.wristG?.let { String.format(Locale.US, "%.2f G", it) } ?: "1.00 G",
                                secondary = "${st.steps} steps · $spanLabel",
                                accent = Color(0xFFF5B041)
                            )
                            CockpitMetricTile(
                                modifier = Modifier.weight(1f),
                                label = "SHA3-512",
                                primary = "${st.blocksHarvested} blk",
                                secondary = "H=${st.lastShannon?.let { String.format(Locale.US, "%.2f", it) } ?: "7.91"} · ${st.blocksQueuedForPhone} Q",
                                accent = Color(0xFF00E676)
                            )
                        }

                        // Primary Cockpit Controls: Stop / Arm + Immediate Batch
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (isRunning) stopHarvesterService() else ensurePermissionsAndStart()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRunning) Color(0xFFFF5252) else Color(0xFF00E676),
                                    contentColor = Color.Black
                                ),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(
                                    text = if (isRunning) "Stop" else "Start",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    if (hasRequiredPermissions()) {
                                        ContextCompat.startForegroundService(
                                            this@WatchMainActivity,
                                            Intent(this@WatchMainActivity, WatchHarvestService::class.java)
                                                .setAction(WatchHarvestService.ACTION_FORCE_BLOCK)
                                        )
                                    } else {
                                        ensurePermissionsAndStart()
                                    }
                                },
                                shape = CircleShape,
                                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(
                                    text = "+Batch",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Standby Mode Switcher (Synced with Phone App)
                        Surface(
                            color = Color(0xFF0D1520),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (st.standbyBatchMode) Color(0xFF38BDF8).copy(alpha = 0.6f)
                                else Color(0xFF1E293B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ContextCompat.startForegroundService(
                                        this@WatchMainActivity,
                                        Intent(this@WatchMainActivity, WatchHarvestService::class.java)
                                            .setAction(WatchHarvestService.ACTION_TOGGLE_STANDBY)
                                    )
                                }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = if (st.standbyBatchMode) "MODE: STANDBY ${st.batchIntervalSec}s BATCHES (TAP TO CHANGE)"
                                    else "MODE: NULLVALON-ONLY SAVER (TAP FOR STANDBY)",
                                    color = if (st.standbyBatchMode) Color(0xFF38BDF8) else Color(0xFF00E676),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = if (st.standbyBatchMode) "2s read-only burst every ${st.batchIntervalSec}s · sensors off 93% of time"
                                    else "0% sensor drain until phone connects to Nullvalon",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 7.5.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        if (st.lastDigestHex.isNotBlank()) {
                            Text(
                                text = "SHA3: ${st.lastDigestHex.take(20)}…",
                                color = Color(0xFF64748B),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needed
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun ensurePermissionsAndStart() {
        val needed = requiredPermissions()
        if (hasRequiredPermissions()) {
            startHarvesterServiceExplicit()
        } else {
            permLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startHarvesterServiceExplicit() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_START)
        )
    }

    private fun stopHarvesterService() {
        startService(Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_STOP))
    }
}

@Composable
private fun CockpitBezelRing(
    progress: Float,
    accentColor: Color,
    activeBurst: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = 4.dp.toPx()
        val inset = strokePx + 3.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)

        // Subtle outer track
        drawArc(
            color = Color(0xFF162234),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // 28 precision bezel ticks
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rOuter = (size.minDimension / 2f) - 2.dp.toPx()
        val rInner = rOuter - 4.dp.toPx()
        for (i in 0..27) {
            val deg = 135f + (270f * i / 27f)
            val rad = Math.toRadians(deg.toDouble())
            val isLit = (i.toFloat() / 27f) <= progress
            drawLine(
                color = if (isLit) accentColor.copy(alpha = 0.85f) else Color(0xFF1E293B),
                start = Offset(cx + (rInner * cos(rad)).toFloat(), cy + (rInner * sin(rad)).toFloat()),
                end = Offset(cx + (rOuter * cos(rad)).toFloat(), cy + (rOuter * sin(rad)).toFloat()),
                strokeWidth = if (i % 7 == 0) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Active radial progress arc
        val sweep = (270f * progress.coerceIn(0f, 1f))
        if (sweep > 1f) {
            drawArc(
                color = if (activeBurst) Color(0xFF00E676) else accentColor,
                startAngle = 135f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun CockpitMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    primary: String,
    secondary: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0C131E),
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = label,
                color = accent,
                fontSize = 7.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = primary,
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Text(
                text = secondary,
                color = Color(0xFF94A3B8),
                fontSize = 7.5.sp,
                maxLines = 1
            )
        }
    }
}
