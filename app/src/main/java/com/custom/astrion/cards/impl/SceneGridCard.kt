package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Scene, activity, or navigation grid tile.
 *
 * Each tile triggers an action based on its fields:
 * - "entity_id": activates a scene or script via Home Assistant.
 * - "activityId" / "harmonyDevice"+"harmonyCommand": Harmony hub actions,
 *   routed through an optional "hub" field (HarmonyHubConfig.localId);
 *   falls back to the first configured hub when absent.
 * - "activityId": triggers a Harmony activity directly on the hub.
 * - "irDevice"+"irCommand": sends one named IR command locally through the
 *   device's own IR blaster (see AppConfig.irDevices) — works fully
 *   offline, no Harmony hub, Home Assistant, or cloud needed.
 * - "activity": starts a *composed* Activity (see AppConfig.ActivityConfig)
 *   — Astrion itself orchestrates every device involved, diffed against
 *   whatever was active in the same room before.
 * - "page": navigates to a specific dashboard page (ctx.navigateToPage).
 * - "track"+"room": marks a tile with any of the single-action fields above
 *   as a trackable Activity — see ActivityRuntime. Not needed alongside
 *   "activity": a composed Activity is always implicitly tracked.
 *
 * Config shape:
 * ```json
 * {
 *   "type": "scene_grid",
 *   "options": {
 *     "layout": "row",
 *     "show_labels": true,
 *     "scenes": [
 *       { "page": "Apple TV", "name": "Apple TV", "color": "#66009688",
 *         "icon": "/sdcard/astrion/icons/apple-tv_dark_icon.png" },
 *       { "entity_id": "scene.night", "name": "Night" },
 *       { "activity": "salon_appletv", "page": "Apple TV" }
 *     ]
 *   }
 * }
 * ```
 *
 * If any scene in the grid has an "icon", every tile in that grid uses the
 * taller icon layout (uniform height) — set "show_labels": false to show
 * icons only, no text underneath.
 */
class SceneGridCard : CardRenderer {
    override val type = "scene_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = remember(config) { config.int("columns", 2).coerceAtLeast(1) }
        val scenes = remember(config) { (config.options["scenes"] as? List<Map<String, Any?>>) ?: emptyList() }
        val row = remember(config) { config.string("layout") == "row" }

        fun activate(entityId: String) {
            val domain = entityId.substringBefore('.')
            ctx.client.callService(ServiceCall(domain = domain, service = "turn_on", entityId = entityId))
        }

        fun onTap(scene: Map<String, Any?>) {
            (scene["entity_id"] as? String)?.let(::activate)
            val hub = scene["hub"] as? String
            (scene["activityId"] as? String)?.let { ctx.startHarmonyActivity(it, hub) }
            val harmonyDevice = scene["harmonyDevice"] as? String
            val harmonyCommand = scene["harmonyCommand"] as? String
            if (harmonyDevice != null && harmonyCommand != null) {
                ctx.sendHarmonyCommand(harmonyDevice, harmonyCommand, hub)
            }
            val irDevice = scene["irDevice"] as? String
            val irCommand = scene["irCommand"] as? String
            if (irDevice != null && irCommand != null) {
                ctx.sendIrCommand(irDevice, irCommand)
            }
            (scene["activity"] as? String)?.let(ctx.startActivity)
            (scene["page"] as? String)?.let(ctx.navigateToPage)
            // If this tile is `"track": true`, records it as the active
            // Activity for its `"room"` — see ActivityRuntime. No-op for
            // ordinary (untracked) tiles, and for "activity" tiles (already
            // marked active by ctx.startActivity itself).
            ctx.activityRuntime?.trackTap(scene)
        }

        fun nameOf(scene: Map<String, Any?>): String {
            (scene["name"] as? String)?.let { return it }
            val entityId = scene["entity_id"] as? String
            if (entityId != null) return ctx.entities[entityId]?.friendlyName ?: entityId
            return scene["page"] as? String ?: "?"
        }

        fun colorOf(scene: Map<String, Any?>): Color = (scene["color"] as? String)?.let(::parseHexColor) ?: Color(0xFF2A4954)

        fun iconOf(scene: Map<String, Any?>): String? = scene["icon"] as? String

        // Decided once for the whole grid (not per-tile) so every tile in a
        // row/grid shares the same height — a mix of icon (74dp) and
        // text-only (58dp) tiles side by side looked uneven.
        val hasIcon = remember(scenes) { scenes.any { !iconOf(it).isNullOrBlank() } }
        val showLabels = remember(config) { config.options["show_labels"] as? Boolean ?: true }
        val iconFill = remember(config) { config.options["icon_fill"] as? Boolean ?: false }
        val tileHeight = remember(config) { config.int("tile_height", if (iconFill) 120 else 74) }

        if (row) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                scenes.forEach { scene ->
                    SceneButton(
                        state = SceneButtonState(
                            name = nameOf(scene),
                            color = colorOf(scene),
                            iconPath = iconOf(scene),
                            hasIcon = hasIcon,
                            showLabel = showLabels
                        ),
                        layout = TileLayout(iconFill, tileHeight),
                        modifier = Modifier.width(104.dp)
                    ) { onTap(scene) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                scenes.chunked(columns).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        chunk.forEach { scene ->
                            SceneButton(
                                state = SceneButtonState(
                                    name = nameOf(scene),
                                    color = colorOf(scene),
                                    iconPath = iconOf(scene),
                                    hasIcon = hasIcon,
                                    showLabel = showLabels
                                ),
                                layout = TileLayout(iconFill, tileHeight),
                                modifier = Modifier.weight(1f)
                            ) { onTap(scene) }
                        }
                        repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    private fun parseHexColor(s: String): Color? = runCatching {
        val hex = if (s.startsWith("#")) s else "#$s"
        Color(hex.toColorInt())
    }.getOrNull()

    private fun luminance(c: Color): Float = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** [SceneButton]'s icon-fill layout knobs, bundled into one parameter so
     * adding this feature didn't push the function over detekt's parameter-
     * count threshold. */
    private data class TileLayout(val iconFill: Boolean, val tileHeight: Int)

    private data class SceneButtonState(
        val name: String,
        val color: Color,
        val iconPath: String?,
        val hasIcon: Boolean,
        val showLabel: Boolean
    )

    @Composable
    private fun SceneButton(state: SceneButtonState, layout: TileLayout, modifier: Modifier, onClick: () -> Unit) {
        val textColor = if (luminance(state.color) > 0.75f) Color(0xFF141414) else Color(0xFFF0F2F6)
        // iconFill tiles render the bitmap at the full tile height (ContentScale.
        // FillHeight), otherwise it's a 28dp square — pick the larger of the two
        // as the downsample target so the same bitmap stays sharp in either mode
        // without decoding at the source's full (often 2000+px) resolution.
        val targetPx =
            with(LocalDensity.current) {
                (if (layout.iconFill) layout.tileHeight else 28).dp.toPx()
            }.toInt()
        val bitmap = remember(state.iconPath, targetPx) {
            state.iconPath?.let { decodeIconSampled(it, targetPx) }
        }

        if (state.hasIcon) {
            if (layout.iconFill && bitmap != null && !state.showLabel) {
                FillIconTile(bitmap, state.name, layout.tileHeight, state.color, modifier, onClick)
            } else {
                // Every tile in the grid uses this branch once any one of them has
                // an icon, even tiles with no icon of their own — a blank 28dp
                // spacer keeps their label lined up with the others instead of
                // sitting lower.
                Column(
                    modifier = modifier
                        .height(layout.tileHeight.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(state.color)
                        .tapClickable(onClick = onClick)
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (bitmap != null) {
                        Image(bitmap = bitmap, contentDescription = state.name, modifier = Modifier.size(28.dp))
                    } else {
                        Spacer(Modifier.size(28.dp))
                    }
                    if (state.showLabel) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.name,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = modifier
                    .height(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(state.color)
                    .tapClickable(onClick = onClick)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.name,
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    /** The `layout.iconFill` branch of [SceneButton], split out purely to
     * keep that function under detekt's line-count threshold — behavior
     * unchanged. Only reached when there's a real [bitmap] and no label
     * (see the caller), so both are non-null/false by the time this runs. */
    @Composable
    private fun FillIconTile(bitmap: ImageBitmap, name: String, tileHeight: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .height(tileHeight.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color)
                .tapClickable(onClick = onClick)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillHeight
            )
        }
    }
}
