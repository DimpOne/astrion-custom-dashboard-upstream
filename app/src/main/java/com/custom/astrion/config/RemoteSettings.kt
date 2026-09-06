package com.custom.astrion.config

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A single Harmony Hub the app can talk to directly, bypassing Home
 * Assistant. [localId] is a stable app-generated key (independent of the
 * hub's own numeric [hubId]) used to reference this hub from dashboard.json
 * (`hub` field on hotkeys / scene actions) — so renaming a hub or fixing a
 * typo'd IP later doesn't break existing references.
 */
data class HarmonyHubConfig(
    val localId: String,
    val name: String,
    val ip: String,
    /** Mutable: HarmonyHubRegistry.connectAll() fills this in via auto-discovery
     * when it's blank, updating this same instance in place. */
    var hubId: String
)

/**
 * Runtime-editable connection settings (Home Assistant + Harmony Hub(s)),
 * stored in SharedPreferences. Deliberately has NO compile-time fallback —
 * release builds published for the community must never bundle anyone's
 * personal Home Assistant URL or access token. Every install starts
 * unconfigured and is set up afterward through the local web configurator
 * on port 8080 (see ConfigServer / SettingsMenu, which shows the address).
 */
object RemoteSettings {
    private const val PREFS = "astrion_settings"
    private const val KEY_HA_URL = "ha_url"
    private const val KEY_HA_TOKEN = "ha_token"
    private const val KEY_HA_WEBHOOK_ID = "ha_webhook_id"
    private const val KEY_HARMONY_HUBS = "harmony_hubs" // JSON array, see HarmonyHubConfig

    // Legacy single-hub keys (pre-multi-hub). Read once for migration, never written again.
    private const val LEGACY_KEY_HARMONY_IP = "harmony_hub_ip"
    private const val LEGACY_KEY_HARMONY_ID = "harmony_hub_id"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun haUrl(context: Context): String = prefs(context).getString(KEY_HA_URL, "") ?: ""

    fun haToken(context: Context): String = prefs(context).getString(KEY_HA_TOKEN, "") ?: ""

    /**
     * Optional HA webhook id (just the id — `POST <ha_url>/api/webhook/<id>`),
     * for instant push (current page, active Activity per room) instead of
     * making the companion HA integration poll ConfigServer's /activities/active
     * on a timer. Webhooks need no auth token (the id itself is the secret),
     * so this is intentionally separate from [haToken]. Blank = push disabled;
     * everything keeps working via polling either way.
     */
    fun haWebhookId(context: Context): String = prefs(context).getString(KEY_HA_WEBHOOK_ID, "") ?: ""

    /** True once a Home Assistant URL and token have been entered via the web configurator. */
    @Suppress("unused")
    fun isConfigured(context: Context): Boolean = haUrl(context).isNotBlank() && haToken(context).isNotBlank()

    fun saveHaConnection(context: Context, haUrl: String, haToken: String, haWebhookId: String = "") {
        prefs(context).edit {
            putString(KEY_HA_URL, haUrl)
            putString(KEY_HA_TOKEN, haToken)
            putString(KEY_HA_WEBHOOK_ID, haWebhookId)
        }
    }

    /**
     * All configured Harmony hubs, in the order they were added. Transparently
     * migrates the old single-hub `harmony_hub_ip`/`harmony_hub_id` prefs into
     * a one-item list the first time this is called, so existing installations
     * keep working without any user action.
     */
    fun harmonyHubs(context: Context): List<HarmonyHubConfig> {
        val raw = prefs(context).getString(KEY_HARMONY_HUBS, null)
        if (raw != null) return parseHubs(raw)

        // Nothing saved yet under the new key — migrate the legacy single hub, if any.
        val legacyIp = prefs(context).getString(LEGACY_KEY_HARMONY_IP, "") ?: ""
        val legacyId = prefs(context).getString(LEGACY_KEY_HARMONY_ID, "") ?: ""
        if (legacyIp.isBlank() && legacyId.isBlank()) return emptyList()

        val migrated =
            listOf(
                HarmonyHubConfig(localId = UUID.randomUUID().toString(), name = "Harmony Hub", ip = legacyIp, hubId = legacyId)
            )
        saveHarmonyHubs(context, migrated)
        return migrated
    }

    fun saveHarmonyHubs(context: Context, hubs: List<HarmonyHubConfig>) {
        val array =
            buildJsonArray {
                hubs.forEach { hub ->
                    addJsonObject {
                        put("localId", hub.localId)
                        put("name", hub.name)
                        put("ip", hub.ip)
                        put("hubId", hub.hubId)
                    }
                }
            }

        prefs(context).edit {
            putString(KEY_HARMONY_HUBS, array.toString())
        }
    }

    /** Convenience lookup used to resolve a `hub` field from dashboard.json. */
    @Suppress("unused")
    fun harmonyHub(context: Context, localId: String?): HarmonyHubConfig? {
        val hubs = harmonyHubs(context)
        if (hubs.isEmpty()) return null
        return hubs.firstOrNull { it.localId == localId } ?: hubs.first()
    }

    private fun parseHubs(raw: String): List<HarmonyHubConfig> = try {
        json.parseToJsonElement(raw).jsonArray.map { el ->
            val obj = el.jsonObject
            HarmonyHubConfig(
                localId = obj["localId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                name = obj["name"]?.jsonPrimitive?.content ?: "Harmony Hub",
                ip = obj["ip"]?.jsonPrimitive?.content ?: "",
                hubId = obj["hubId"]?.jsonPrimitive?.content ?: ""
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
