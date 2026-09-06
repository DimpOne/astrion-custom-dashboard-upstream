package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Full app configuration: swipeable pages of cards plus hardware-button
 * bindings. Loaded from /sdcard/astrion/dashboard.json by DashboardLoader,
 * with `DashboardConfig.default` as the compiled-in fallback.
 */
@Suppress("Unused")
data class AppConfig(
    /** Left-to-right page order; swipe between them. */
    val pages: List<PageConfig>,
    /** Index of the page shown at launch (the "home" page). */
    val startPage: Int = 0,
    /** Short-press button bindings. */
    val hotkeys: List<HotkeyConfig> = emptyList(),
    /** Long-press (~500ms hold) button bindings — same shape as hotkeys. */
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Local IR devices — a registry of named commands per physical device,
     * sent directly through the device's own IR blaster (ConsumerIrManager).
     * No hub, no Home Assistant, no cloud — the resilience baseline: works
     * even if every cloud service involved (Harmony's included) disappears.
     * Referenced by id from a scene_grid item's `irDevice`+`irCommand`
     * fields, or as one of an ActivityConfig's `devices`. */
    val irDevices: List<IrDeviceConfig> = emptyList(),
    /** Composed AV Activities ("Watch Apple TV", "Listen to Music"...) that
     * orchestrate more than one device — the multi-device case Harmony's own
     * Activity engine handles internally, reimplemented here so it also
     * works for IR-only and mixed-source setups (see ActivityConfig doc).
     * Single-device/single-action Activities don't need an entry here at
     * all — see the NOTE further down for that lighter-weight path. */
    val activities: List<ActivityConfig> = emptyList(),
    /** Global color theme. Every UI color that was once a hardcoded literal
     * reads from here (via ThemeColors / LocalTheme in the Compose layer).
     * Missing fields fall back to the built-in defaults, so an empty `theme`
     * block renders identically to the original look. */
    val theme: ThemeConfig = ThemeConfig()
)

/**
 * One swipeable page: a name (used by hotkey `page` navigation), its cards,
 * and optional page-scoped hotkeys that override the global ones while this
 * page is on screen (e.g. the D-pad targets the Apple TV only on its page,
 * while VOLUME_UP/DOWN keep pointing at the soundbar everywhere).
 */
@Suppress("Unused")
data class PageConfig(
    val name: String,
    val cards: List<CardConfig>,
    val hotkeys: List<HotkeyConfig> = emptyList(),
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Optional parent page name — makes this page a child in a navigation
     * tree (e.g. "Apple TV" with parent "Vidéo"), rather than a flat
     * top-level page. Entering a child is unchanged: any card's own
     * `navigateToPage`, exactly like today. Leaving is new: the physical
     * button named by [parentKey] (previously a no-op unless a page-specific
     * hotkey bound it — see MainActivity's `dispatchKeyEvent`) now jumps to
     * this page's parent when nothing else claims that key first, so an AV
     * page that binds its own hotkey on the same key is never affected.
     * null (default) = today's flat top-level page, behavior unchanged. */
    val parent: String? = null,
    /** Which physical button triggers the jump to [parent]. A HardwareKey
     * name (case-insensitive) — same vocabulary as [HotkeyConfig.key], e.g.
     * "HOME" or "PAGE_DOWN" instead of the hardware BACK button. Defaults to
     * "BACK", matching the original, non-configurable behavior. Only
     * consulted when [parent] is set; an unrecognized name is treated as if
     * this were left at the default. Note this only changes what triggers
     * the *parent-navigation fallback* — if [parentKey] is set to something
     * other than "BACK", the hardware BACK button itself goes back to doing
     * nothing on this page (today's behavior for a page with no parent at
     * all), since it's no longer the configured "leave" button. */
    val parentKey: String = "BACK"
)

/**
 * One physical-button binding. `key` is a HardwareKey name — the HA100 has:
 * UP DOWN LEFT RIGHT CENTER, PAGE_UP PAGE_DOWN, VOLUME_UP VOLUME_DOWN MUTE,
 * BACK HOME POWER VOICE, LIGHT CURTAIN SCENE AC, CUSTOM_1...CUSTOM_4.
 *
 * Exactly one action per binding:
 *  - `page`: navigate to the page with that name (case-insensitive), or
 *  - `service` ("domain.service") + optional `entityId` + flat `data` map
 *    (routed through Home Assistant), or
 *  - `harmonyDevice` + `harmonyCommand`: IR command sent DIRECTLY to the
 *    Harmony hub (HarmonyHubClient), bypass HA — same ids as checklist_codes_ir.csv, or
 *  - `harmonyActivity`: starts a Harmony Activity by its id (from
 *    harmony_config.json), PowerOff = "-1".
 */
@Suppress("Unused")
data class HotkeyConfig(
    val key: String,
    val page: String? = null,
    val service: String? = null,
    val entityId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val harmonyDevice: String? = null,
    val harmonyCommand: String? = null,
    val harmonyActivity: String? = null,
    /** Which configured Harmony hub (HarmonyHubConfig.localId) this action
     * targets. Null/blank falls back to the first configured hub — see
     * HarmonyHubRegistry.client(). Only meaningful alongside
     * harmonyDevice/harmonyCommand or harmonyActivity. */
    val hub: String? = null,
    /** Local IR command sent directly through the device's own blaster —
     * `irDevice` is an [IrDeviceConfig].id, `irCommand` a key in its
     * `commands` map. Independent of Harmony/HA, checked after
     * harmonyDevice+harmonyCommand and before a plain `service` call. */
    val irDevice: String? = null,
    val irCommand: String? = null,
    /** Marks this binding as a trackable AV Activity — see AppConfig-level
     * doc on "Activities" below. When true, `room` is required: this is what
     * makes it show up in ActivityRuntime and in an "active activities" card,
     * and what makes starting it replace whatever Activity was previously
     * tracked as active in the same room. The actual action fired on press
     * is still whichever of `page`/`service`/`harmonyDevice+harmonyCommand`/
     * `harmonyActivity`/`irDevice+irCommand` above is set — `track` adds
     * bookkeeping, it doesn't change what gets executed. */
    val track: Boolean = false,
    val room: String? = null,
    /** Physical devices this binding's Activity is known to involve —
     * purely a bookkeeping hint for [ActivityRuntime]'s switchActivity diff,
     * never dispatched to directly. Matters most for a Harmony-backed
     * tracked Activity: the hub handles the actual devices internally, so
     * without this hint a *composed* Activity that later takes over the
     * same room has no way to know a device (e.g. a shared soundbar) was
     * already on, and may needlessly power-cycle it — a real problem for a
     * device with only a `PowerToggle` command and no discrete on/off.
     * Plain device-catalog ids (same ones used in an ActivityConfig's
     * `devices[].deviceId`), regardless of source. */
    val devices: List<String> = emptyList(),
    /** Opens a full-screen overlay instead of dispatching any device/page
     * action: `"settings"` (same overlay as swiping down from the top
     * status bar) or `"activities"` (same as swiping up from the page
     * indicator — the Active Activities picker). Case-insensitive; any
     * other value is treated as unset. Checked first in `runHotkey`'s
     * priority chain, before page navigation, so it always wins over the
     * rest of this binding if both happen to be set. */
    val openOverlay: String? = null,
    /** One-tap "return to the AV Activity that's actually running" — the
     * page-navigation equivalent of tapping an entry in the Active
     * Activities overlay, but for whichever [TrackedActivity] is currently
     * active in *this* room specifically, resolved live at press time via
     * `ActivityRuntime.activeActivity(room)?.page`. A no-op if this room has
     * no active Activity right now (e.g. everything's off) — deliberately
     * doesn't fall through to the rest of this binding's action chain in
     * that case, same as a parent-navigation press on a root page. Distinct
     * from [openOverlay]\="activities": this jumps straight to the one
     * Activity's page with no picker, so it only makes sense on a
     * remote/page that's already dedicated to a single room. Checked right
     * after [openOverlay], before page navigation. */
    val openCurrentActivityRoom: String? = null
) {
    /** Helper flags to quickly check hotkey action type. */
    val isPageNavigation: Boolean get() = !page.isNullOrBlank()
    val isServiceCall: Boolean get() = !service.isNullOrBlank()
    val isHarmonyCommand: Boolean get() = !harmonyDevice.isNullOrBlank() && !harmonyCommand.isNullOrBlank()
    val isHarmonyActivity: Boolean get() = !harmonyActivity.isNullOrBlank()
    val isOpenOverlay: Boolean
        get() = openOverlay.equals("settings", ignoreCase = true) || openOverlay.equals("activities", ignoreCase = true)
}

/**
 * A local IR device: a stable id/name plus a [source] telling the app
 * where its named commands come from. The offline, no-hub, no-cloud
 * equivalent of a Harmony device: works even if every cloud service
 * disappears overnight.
 */
@Suppress("Unused")
data class IrDeviceConfig(
    val id: String,
    val name: String = id,
    val source: IrDeviceSource
)

/**
 * Where an [IrDeviceConfig]'s commands come from.
 *
 * - [Inline]: freq+pattern already resolved from a hand-pasted Pronto Hex
 *   code, embedded directly in dashboard.json. For one-off buttons not
 *   (yet) in any curated database — e.g. straight out of the sniffer's
 *   Learning Mode.
 * - [SdCardRef]: a pointer into `/sdcard/astrion/ir-database/<category>.json`
 *   (see IrDatabaseRuntime.kt) — the curated files the ir-database picker
 *   (a separate static site, not bundled with this app) generates. Pronto
 *   is resolved here at runtime, on first use, and cached — dashboard.json
 *   itself only ever carries the *reference*, never the raw codes, so a
 *   community database update doesn't require re-touching every dashboard
 *   built against it.
 */
@Suppress("Unused")
sealed class IrDeviceSource {
    data class Inline(
        /** commandId (freeform, e.g. "power", "volume_up", "hdmi1") -> resolved IR frame. */
        val commands: Map<String, IrStepConfig>
    ) : IrDeviceSource()

    data class SdCardRef(
        /** Matches an ir-database category id, e.g. "tv", "ac" — also the filename stem. */
        val category: String,
        /** Matches a `brand_name` in that category's file, case-insensitively. */
        val brand: String,
        /** Matches a `model_name` under that brand, case-insensitively. */
        val model: String
    ) : IrDeviceSource()
}

/** One IR transmission: `freq` (Hz) + `pattern` (alternating on/off
 * durations in µs) map straight onto `ConsumerIrManager.transmit()`. */
@Suppress("Unused")
data class IrStepConfig(val freq: Int, val pattern: List<Int>)

// NOTE: a *single-action* Activity (one HA script, one existing Harmony
// Activity — the hub already orchestrates everything for that one — or one
// direct command) doesn't need an ActivityConfig entry at all: just mark a
// scene_grid item or HotkeyConfig `track = true` with a `room`, same as
// before. Its action is whichever field was already there: `entityId`,
// `activityId`+`hub`, `harmonyDevice`+`harmonyCommand`, or `irDevice`+
// `irCommand`. `track` just makes it visible to ActivityRuntime and to the
// "active activities" overlay.
//
// A *composed* Activity — more than one device, where Astrion itself (not a
// hub) has to decide what to power on/off when switching — needs the real
// thing below.

/**
 * One user-facing AV Activity that orchestrates more than one device
 * ("Watch Apple TV" = TV on HDMI1 + receiver on HDMI2 + lights off). Astrion
 * itself runs the start sequence and, when switching to a *different*
 * Activity in the same `room`, diffs `devices` against the incoming
 * Activity's so a device used by both is left alone (no needless off/on
 * flicker, just a possible input change) while a device only in the
 * outgoing one gets powered off — see ActivityRuntime.switchActivity().
 *
 * Referenced by id from a scene_grid item's `activity` field. Always
 * implicitly tracked (no separate `track` flag needed) — the whole point of
 * defining one is room exclusivity.
 */
@Suppress("Unused")
data class ActivityConfig(
    val id: String,
    val name: String,
    val room: String,
    val icon: String? = null,
    /** Page to open when this Activity becomes active — optional; a
     * composed Activity can exist purely for room-exclusivity/orchestration
     * without navigating anywhere. */
    val page: String? = null,
    val devices: List<ActivityDeviceConfig>,
    /** Which of `devices` (by `deviceId`) VOLUME_UP/DOWN/MUTE hotkeys should
     * target while this Activity is active. Paired with the three commands
     * below — the builder writes them out as page-scoped hotkeys on `page`
     * when this Activity is saved (see docs/js/activities.js), not read
     * directly by the app at runtime; PageConfig.hotkeys already overrides
     * global bindings while its page is on screen, so no separate "which
     * room is the panel in right now" runtime logic is needed. */
    val volumeDeviceId: String? = null,
    val volumeUpCommand: String? = null,
    val volumeDownCommand: String? = null,
    val muteCommand: String? = null
)

/**
 * One device's role within an [ActivityConfig].
 *
 * `source` selects which registry `deviceId` resolves against:
 *  - `"ir"` — an [IrDeviceConfig].id; `powerOnCommand`/`powerOffCommand`/
 *    `inputCommand`, if set, are commandIds in that device's `commands` map.
 *  - `"harmony"` — a Harmony device id (via `hub`); same three fields, but
 *    Harmony command names (e.g. "PowerOn"/"PowerOff"/"InputHdmi1").
 *  - `"ha"` — a Home Assistant entity id. Power is `turn_on`/`turn_off`
 *    (gated by `powerOnFirst`/`powerOffOnExit`, `powerOnCommand`/
 *    `powerOffCommand` are ignored); `inputCommand`, if set, is passed as
 *    `media_player.select_source`'s `source`.
 *
 * On Activity start: if this device wasn't already on for the *previous*
 * Activity in the same room (or `powerOnFirst` is true regardless),
 * `powerOnCommand` fires, then — after `delayAfterMs` — `inputCommand`. On
 * losing the room to a *different* Activity: if this device isn't also used
 * by the incoming one (or `powerOffOnExit` is true regardless),
 * `powerOffCommand` fires; a device shared by both is left alone entirely
 * (no power cycle, no re-sent input) unless the incoming Activity gives it a
 * different `inputCommand`.
 */
@Suppress("Unused")
data class ActivityDeviceConfig(
    val deviceId: String,
    val source: String,
    val hub: String? = null,
    val powerOnCommand: String? = null,
    val powerOffCommand: String? = null,
    val inputCommand: String? = null,
    val powerOnFirst: Boolean = true,
    val powerOffOnExit: Boolean = true,
    /** Wait this long after this device's start commands before starting the
     * *next* device's — e.g. TV on, wait 2s, then the receiver. Ignored on
     * the last device and on stop (power-off runs with no delays between
     * devices — nothing downstream needs to wait for it). */
    val delayAfterMs: Int = 0
)

/**
 * Global color theme — 12 semantic tokens. Values are ARGB/RGB hex strings
 * (e.g. "#1B343D"). Each defaults to the app's original hardcoded color, so a
 * ThemeConfig() with no overrides reproduces the built-in look exactly.
 * Parsed from the `theme` block of dashboard.json by DashboardLoader.
 */
@Suppress("Unused")
data class ThemeConfig(
    val background: String = "#0E2229",
    val cardSurface: String = "#1B343D",
    val insetSurface: String = "#152B33",
    val controlBackground: String = "#2C4C58",
    val primaryText: String = "#E6F0F1",
    val mutedText: String = "#93AFB6",
    val iconTint: String = "#CBDCE0",
    val accent: String = "#6EA8FE",
    val accentSecondary: String = "#4C6EF5",
    val amber: String = "#FFC24B",
    val danger: String = "#E06767",
    val success: String = "#4CAF50"
)
