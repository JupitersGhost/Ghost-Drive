@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jupiterlabs.ghostdrive.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jupiterlabs.ghostdrive.R
import com.jupiterlabs.ghostdrive.data.GraphSample
import com.jupiterlabs.ghostdrive.data.LiveState
import com.jupiterlabs.ghostdrive.data.Settings
import com.jupiterlabs.ghostdrive.tracking.Geo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

fun duration(ms: Long): String {
    val sec = ms.coerceAtLeast(0) / 1000; val hours = sec / 3600; val mins = sec % 3600 / 60
    return if(hours > 0) "${hours}h ${mins}m" else if(mins > 0) "${mins}m ${sec % 60}s" else "${sec}s"
}
fun timestamp(ms: Long?) = ms?.let { DateTimeFormatter.ofPattern("MMM d · h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "—"
fun number(v: Float?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: "—"
fun distance(m: Double, units: String) = if(units == "mi") String.format(Locale.US, "%.2f mi", m / 1609.344) else String.format(Locale.US, "%.2f km", m / 1000)
fun speed(mps: Float?, units: String): String {
    if (mps == null || !mps.isFinite()) return if (units == "mi") "0 mph" else "0 km/h"
    return if (units == "mi") String.format(Locale.US, "%.1f mph", mps * 2.2369363f)
    else String.format(Locale.US, "%.1f km/h", mps * 3.6f)
}
fun speedValue(mps: Float?, units: String): Float {
    if (mps == null || !mps.isFinite()) return 0f
    return if (units == "mi") mps * 2.2369363f else mps * 3.6f
}
fun speedNumber(mps: Float?, units: String): String {
    return String.format(Locale.US, "%.0f", speedValue(mps, units))
}
fun speedTierColor(mps: Float?): Color {
    val s = mps ?: 0f
    return when {
        s >= 24f -> Color(0xFFF5B041) // Highway Gold
        s >= 11f -> Color(0xFF2ECC71) // Arterial Emerald
        else -> Color(0xFF4D96FF)     // City Cyan/Blue
    }
}

@Composable
fun SpeedometerGauge(
    speedMps: Float?,
    maxSpeedMps: Float? = null,
    units: String = "mi",
    size: Dp = 148.dp,
    compact: Boolean = false
) {
    val maxScale = if (units == "mi") 100f else 160f
    val rawDisplaySpeed = speedValue(speedMps, units)
    val animatedSpeed by animateFloatAsState(
        targetValue = rawDisplaySpeed.coerceIn(0f, maxScale),
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "speedSweep"
    )
    val peakDisplaySpeed = speedValue(maxSpeedMps, units).coerceIn(0f, maxScale)
    val tierColor = speedTierColor(speedMps)
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val unitLabel = if (units == "mi") "MPH" else "KM/H"

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val strokeWidth = if (compact) 9.dp.toPx() else 11.dp.toPx()
            val arcDiameter = this.size.minDimension - strokeWidth
            val topLeft = Offset((this.size.width - arcDiameter) / 2f, (this.size.height - arcDiameter) / 2f)
            val arcSize = Size(arcDiameter, arcDiameter)

            // Background 240-degree sweep track
            drawArc(
                color = trackColor,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Tick marks around the gauge
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val outerRadius = arcDiameter / 2f - strokeWidth * 0.75f
            val totalTicks = 20
            for (i in 0..totalTicks) {
                val angleDeg = 150f + (240f * i / totalTicks)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val isMajor = i % 5 == 0
                val innerR = outerRadius - if (isMajor) 7.dp.toPx() else 4.dp.toPx()
                val startPt = Offset(
                    center.x + (innerR * cos(angleRad)).toFloat(),
                    center.y + (innerR * sin(angleRad)).toFloat()
                )
                val endPt = Offset(
                    center.x + (outerRadius * cos(angleRad)).toFloat(),
                    center.y + (outerRadius * sin(angleRad)).toFloat()
                )
                drawLine(
                    color = if (isMajor) tickColor else tickColor.copy(alpha = 0.3f),
                    start = startPt,
                    end = endPt,
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Active speed sweep arc (rotated 140° so 10°..250° avoids the 0°/360° sweepGradient seam)
            val sweep = (animatedSpeed / maxScale) * 240f
            if (sweep > 0.5f) {
                rotate(degrees = 140f, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.00f to Color(0xFF4D96FF),
                            0.28f to Color(0xFF2ECC71),
                            0.52f to Color(0xFFF5B041),
                            0.72f to Color(0xFFFF6B6B),
                            1.00f to Color(0xFF4D96FF),
                            center = center
                        ),
                        startAngle = 10f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // Peak speed marker dot on rim
            if (peakDisplaySpeed > 1f) {
                val peakAngleRad = Math.toRadians((150f + (peakDisplaySpeed / maxScale) * 240f).toDouble())
                val rimR = arcDiameter / 2f
                val peakPt = Offset(
                    center.x + (rimR * cos(peakAngleRad)).toFloat(),
                    center.y + (rimR * sin(peakAngleRad)).toFloat()
                )
                drawCircle(Color(0xFFF5B041), radius = 4.dp.toPx(), center = peakPt)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format(Locale.US, "%.0f", rawDisplaySpeed),
                fontSize = if (compact) 30.sp else 38.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = if (compact) 32.sp else 40.sp
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tierColor
            )
        }
    }
}

@Composable
fun CompassDial(bearingDeg: Float?, size: Dp = 84.dp, showSubtitle: Boolean = size >= 68.dp) {
    val normalizedTarget = if (bearingDeg != null && bearingDeg.isFinite()) ((bearingDeg % 360f + 360f) % 360f) else 0f
    var continuousBearing by remember { mutableFloatStateOf(normalizedTarget) }
    LaunchedEffect(normalizedTarget) {
        val currentMod = (continuousBearing % 360f + 360f) % 360f
        val delta = ((normalizedTarget - currentMod + 540f) % 360f) - 180f
        continuousBearing += delta
    }
    val animatedBearing by animateFloatAsState(
        targetValue = continuousBearing,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "compassRotation"
    )
    val primary = MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val radius = this.size.minDimension / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(color = ringColor, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
                // Cardinal ticks
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val isMain = i % 2 == 0
                    val r1 = radius - if (isMain) 7.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color = if (i == 0) Color(0xFFFF6B6B) else ringColor,
                        start = Offset(center.x + (r1 * sin(a)).toFloat(), center.y - (r1 * cos(a)).toFloat()),
                        end = Offset(center.x + (radius * sin(a)).toFloat(), center.y - (radius * cos(a)).toFloat()),
                        strokeWidth = if (isMain) 2.dp.toPx() else 1.2.dp.toPx()
                    )
                }
                // Rotated navigation arrow
                rotate(degrees = animatedBearing, pivot = center) {
                    val path = Path().apply {
                        moveTo(center.x, center.y - radius * 0.65f)
                        lineTo(center.x + radius * 0.24f, center.y + radius * 0.38f)
                        lineTo(center.x, center.y + radius * 0.18f)
                        lineTo(center.x - radius * 0.24f, center.y + radius * 0.38f)
                        close()
                    }
                    drawPath(path, color = primary)
                }
            }
            Text(
                text = Geo.cardinalShort(bearingDeg),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (showSubtitle) {
            Text(
                text = if (bearingDeg != null && bearingDeg.isFinite()) Geo.cardinal(bearingDeg) else "Heading —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MerlinAvatarBadge(
    showMerlin: Boolean = true,
    active: Boolean = false,
    size: Dp = 44.dp,
    onClick: (() -> Unit)? = null
) {
    val borderColor = if (active) Color(0xFF2ECC71) else MaterialTheme.colorScheme.primary
    val mod = Modifier
        .size(size)
        .clip(CircleShape)
        .border(BorderStroke(2.dp, borderColor), CircleShape)
    if (showMerlin) {
        Surface(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = mod
        ) {
            Image(
                painter = painterResource(R.drawable.merlin),
                contentDescription = "Merlin Avatar",
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Surface(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = mod
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Navigation, contentDescription = "Navigation Avatar", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun CockpitHud(
    live: LiveState,
    settings: Settings,
    onOpenFullscreenMap: (() -> Unit)? = null
) {
    val t = live.trip
    val fix = live.lastFix
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MerlinAvatarBadge(showMerlin = settings.showMerlin, active = live.authenticated || live.demo, size = 38.dp)
                    Column {
                        Tag(
                            when {
                                live.demo -> "COCKPIT · SIMULATOR"
                                live.gpsActive -> "COCKPIT · LIVE GPS"
                                live.authenticated -> "COCKPIT · ESP32 LINKED"
                                else -> "COCKPIT · STANDBY"
                            },
                            live.authenticated || live.demo
                        )
                    }
                }
                if (onOpenFullscreenMap != null) {
                    FilledTonalButton(
                        onClick = onOpenFullscreenMap,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fullscreen Map", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeedometerGauge(
                    speedMps = fix?.speedMps,
                    maxSpeedMps = t?.maxSpeedMps,
                    units = settings.displayUnits,
                    size = 144.dp
                )
                CompassDial(bearingDeg = fix?.bearingDeg, size = 82.dp)
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) {
                    Text(
                        text = distance(t?.distanceM ?: 0.0, settings.displayUnits),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "Peak · ${speed(t?.maxSpeedMps, settings.displayUnits)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF5B041),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "Avg · ${speed(t?.avgMovingSpeedMps, settings.displayUnits)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = "Move · ${duration(t?.movingMs ?: 0)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = t?.let { "${it.startName} → ${it.endName}" } ?: "Merlin co-pilot ready · waiting for active route fix",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pool H: ${number(live.pool?.shannon)} b/B", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Vault: ${live.entropyVault.blocks} blk (${live.entropyVault.totalBytes} B)", style = MaterialTheme.typography.labelMedium)
                Text("RF / Radar: ${live.wifiMotion?.score ?: "—"} / ${live.radar?.detectionCm?.let { "${it}cm" } ?: "—"}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable fun Panel(title: String, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if(subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
@Composable fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
@Composable fun Tag(text: String, good: Boolean = true) {
    Surface(color = if(good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Fixed, honest 0–8 bits/byte scale; null observations break paths rather than filling zeros. */
@Composable fun EntropyGraph(samples: List<GraphSample>, conditioned: Boolean = true, modifier: Modifier = Modifier) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary, Color(0xFFB89556))
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = .22f)
    if(samples.isEmpty()) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Waiting for a completed source window", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else Canvas(modifier.fillMaxWidth().height(140.dp)) {
        for(i in 0..4) { val y = size.height * i / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        val getters: List<(GraphSample) -> Float?> = listOf({it.shannon}, {it.minimum}, {it.conditionedShannon}, {it.conditionedMinimum})
        val first = samples.first().timeMs; val span = (samples.last().timeMs - first).coerceAtLeast(1).toFloat()
        for(series in 0 until if(conditioned) 4 else 2) {
            val path = Path(); var prior = false
            samples.forEach { s ->
                val v = getters[series](s)
                if(v == null || !v.isFinite()) { prior = false; return@forEach }
                val x = if(samples.size == 1) size.width / 2 else (s.timeMs - first).toFloat() / span * size.width
                val y = size.height * (1 - v.coerceIn(0f, 8f) / 8)
                if(prior) path.lineTo(x, y) else path.moveTo(x, y)
                prior = true; drawCircle(colors[series], 2.5.dp.toPx(), Offset(x, y))
            }
            drawPath(path, colors[series], style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0 → 8 bits/byte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(samples.size < 2) "${samples.size} sample" else "${duration(samples.last().timeMs - samples.first().timeMs)} window", style = MaterialTheme.typography.labelSmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Shannon", color = colors[0], style = MaterialTheme.typography.labelSmall)
        Text("Minimum", color = colors[1], style = MaterialTheme.typography.labelSmall)
        if(conditioned) { Text("Cond. H", color = colors[2], style = MaterialTheme.typography.labelSmall); Text("Cond. min", color = colors[3], style = MaterialTheme.typography.labelSmall) }
    }
}
