package com.jupiterlabs.ghostdrive.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

class GhostWatchTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val lastClickId = requestParams.currentState.lastClickableId
        val cur = WatchHarvestService.state.value

        // Only start/stop or trigger a batch when the user explicitly taps a Tile button!
        when (lastClickId) {
            "toggle_harvest" -> {
                runCatching {
                    if (cur.harvesting && !cur.userStopped) {
                        startService(Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_STOP))
                    } else {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_START)
                        )
                    }
                }
            }
            "hash_now" -> {
                runCatching {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, WatchHarvestService::class.java).setAction(WatchHarvestService.ACTION_FORCE_BLOCK)
                    )
                }
            }
        }

        val st = WatchHarvestService.state.value
        val isRunning = st.harvesting && !st.userStopped && st.watchEnabled
        val isActivelyBatching = isRunning && (st.nullvalonOnline || st.standbyBatchMode)

        val modeText = when {
            !isRunning -> "STOPPED · 0% DRAIN"
            st.nullvalonOnline -> "🚗 NULLVALON LIVE · ${st.batchIntervalSec}s"
            st.standbyBatchMode -> "⌚ STANDBY BATCH · ${st.batchIntervalSec}s"
            else -> "CAR STANDBY · 0% DRAIN"
        }
        val modeColor = when {
            !isRunning -> 0xFFFF5252.toInt()
            st.nullvalonOnline -> 0xFF00E676.toInt()
            st.standbyBatchMode -> 0xFF38BDF8.toInt()
            else -> 0xFFF5B041.toInt()
        }

        val hrText = "❤️ ${st.hrBpm?.toInt() ?: st.avgHrBpm?.toInt() ?: "—"} BPM  ·  IBI ${st.ibiMs ?: st.avgIbiMs ?: "—"}ms"
        val kinText = String.format(
            Locale.US,
            "🩸 %d%%  ·  ⚡ %.2fG  ·  %d stp",
            st.spo2Pct ?: st.avgSpo2Pct ?: 98,
            st.wristG ?: st.avgWristG ?: 1.00f,
            st.steps
        )
        val entText = String.format(
            Locale.US,
            "SHA3-512 · H=%s · Min=%s",
            st.lastShannon?.let { String.format(Locale.US, "%.2f", it) } ?: "7.91",
            st.lastMinEntropy?.let { String.format(Locale.US, "%.2f", it) } ?: "7.12"
        )
        val queueText = if (isActivelyBatching) {
            "Blocks: ${st.blocksHarvested} (${st.blocksQueuedForPhone} Q) · Next ${st.secondsUntilNextBatch}s"
        } else {
            "Blocks: ${st.blocksHarvested} (${st.blocksQueuedForPhone} BT queued)"
        }

        val openAppClickable = ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WatchMainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val toggleClickable = ModifiersBuilders.Clickable.Builder()
            .setId("toggle_harvest")
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val hashNowClickable = ModifiersBuilders.Clickable.Builder()
            .setId("hash_now")
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val rootLayout = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(210f))
            .setHeight(dp(210f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(openAppClickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF060A0F.toInt()))
                            .build()
                    )
                    .setBorder(
                        ModifiersBuilders.Border.Builder()
                            .setWidth(dp(2f))
                            .setColor(argb(modeColor))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(
                        textLine("GHOST DRIVE · WATCH 5", 10.5f, 0xFF00E676.toInt(), bold = true)
                    )
                    .addContent(spacer(2f))
                    .addContent(
                        pillBadge(modeText, 8.5f, modeColor, 0xFF101923.toInt())
                    )
                    .addContent(spacer(4f))
                    .addContent(
                        textLine(hrText, 10.5f, 0xFFFFFFFF.toInt(), bold = true)
                    )
                    .addContent(
                        textLine(kinText, 9.5f, 0xFF94A3B8.toInt(), bold = false)
                    )
                    .addContent(spacer(3f))
                    .addContent(
                        textLine(entText, 9.5f, 0xFF00E676.toInt(), bold = true)
                    )
                    .addContent(
                        textLine(queueText, 8.5f, 0xFF38BDF8.toInt(), bold = true)
                    )
                    .addContent(spacer(5f))
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(
                                actionPill(
                                    text = if (isRunning) "Stop" else "Start",
                                    bgColor = if (isRunning) 0xFFFF5252.toInt() else 0xFF00E676.toInt(),
                                    textColor = 0xFF000000.toInt(),
                                    clickable = toggleClickable
                                )
                            )
                            .addContent(
                                LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build()
                            )
                            .addContent(
                                actionPill(
                                    text = "+Batch",
                                    bgColor = 0xFF1E293B.toInt(),
                                    textColor = 0xFF38BDF8.toInt(),
                                    clickable = hashNowClickable
                                )
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(rootLayout)
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("3")
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(timeline)
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion("3")
                .build()
        )
    }

    private fun spacer(heightDp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    private fun pillBadge(text: String, sizeSp: Float, textColor: Int, bgColor: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(8f))
                            .setEnd(dp(8f))
                            .setTop(dp(2f))
                            .setBottom(dp(2f))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(8f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(textLine(text, sizeSp, textColor, bold = true))
            .build()

    private fun actionPill(
        text: String,
        bgColor: Int,
        textColor: Int,
        clickable: ModifiersBuilders.Clickable
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable)
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(11f))
                            .setEnd(dp(11f))
                            .setTop(dp(4f))
                            .setBottom(dp(4f))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(12f))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(textLine(text, 9.5f, textColor, bold = true))
            .build()

    private fun textLine(text: String, sizeSp: Float, colorArgb: Int, bold: Boolean): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(colorArgb))
                    .setWeight(
                        if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                        else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                    )
                    .build()
            )
            .build()
}
