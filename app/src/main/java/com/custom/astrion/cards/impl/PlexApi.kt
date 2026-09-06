package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared Plex HTTP layer for [PlexCard] and [PlexDetailDialog] — a single
 * `internal` object so both files (same package) can reuse one HTTP client,
 * one poster cache, and one set of JSON helpers instead of each rolling
 * their own. All calls are direct to the Plex server's own HTTP API; none
 * of this touches Home Assistant.
 */
internal object PlexApi {
    val http: OkHttpClient = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    val json = Json { ignoreUnknownKeys = true }

    // Tiny LRU poster/thumb cache — bumped from 40 to 80 now that the library
    // grid can have many more visible posters at once than a single row.
    private val bitmapCache =
        object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
        }

    @Synchronized private fun cacheGet(k: String): ImageBitmap? = bitmapCache[k]

    @Synchronized private fun cachePut(k: String, v: ImageBitmap) {
        bitmapCache[k] = v
    }

    data class PlexItem(
        val key: String,
        val title: String,
        val subtitle: String,
        val thumb: String?,
        val addedAt: Long,
        /** This item's Plex library section — lets a long-press open that whole library. Null if the server didn't report one. */
        val sectionKey: String? = null,
        val sectionTitle: String? = null
    )

    data class PlexRow(val title: String, val items: List<PlexItem>)

    data class PlexSection(val key: String, val type: String, val title: String)

    data class PlexEpisodeSummary(val key: String, val title: String, val index: Int?, val thumb: String?)

    /** One page of a full library section browse, plus the section's total item count for infinite scroll. */
    data class PlexLibraryPage(val items: List<PlexItem>, val totalSize: Int)

    /** Everything the detail dialog needs for one movie or one episode. */
    data class PlexDetail(
        val ratingKey: String,
        val type: String,
        /** Big title at the top of the dialog — the show's name for an episode, the film's name for a movie. */
        val headerTitle: String,
        /** Secondary line under the header — e.g. "S2 · E4 · Example Episode" for an episode, blank for a movie. */
        val subheading: String,
        /** "2024 · 1h 42m · ★7.8" style metadata line. */
        val metaLine: String,
        val summary: String,
        val genres: List<String>,
        val art: String?,
        val thumb: String?,
        /** Episodes of the current season, only set when [type] == "episode". */
        val episodes: List<PlexEpisodeSummary>?
    )

    fun posterUrl(host: String, token: String, thumb: String?, width: Int = 140, height: Int = 210): String? = thumb?.let {
        "$host/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1" +
            "&url=${URLEncoder.encode(it, "UTF-8")}&X-Plex-Token=$token"
    }

    suspend fun loadBitmap(url: String): ImageBitmap? {
        cacheGet(url)?.let { return it }
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                }.getOrNull()?.asImageBitmap()
            }
        if (loaded != null) cachePut(url, loaded)
        return loaded
    }

    private fun apiUrl(host: String, token: String, path: String): String =
        host + path + (if ('?' in path) "&" else "?") + "X-Plex-Token=$token"

    private suspend fun get(u: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http
                .newCall(Request.Builder().url(u).header("Accept", "application/json").build())
                .execute()
                .use { r ->
                    if (!r.isSuccessful) null else json.parseToJsonElement(r.body?.string() ?: return@use null) as? JsonObject
                }
        }.getOrNull()
    }

    suspend fun fetchIdentity(host: String, token: String): String? =
        get(apiUrl(host, token, "/identity"))?.mc()?.strOf("machineIdentifier")

    /** Every library section on the server, with its Plex `type` ("movie", "show", "artist", ...). */
    suspend fun fetchSections(host: String, token: String): List<PlexSection> {
        val dirs = get(apiUrl(host, token, "/library/sections"))?.mc()?.get("Directory") as? JsonArray ?: return emptyList()
        return dirs.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.strOf("key") ?: return@mapNotNull null
            val secType = o.strOf("type") ?: return@mapNotNull null
            val secTitle = o.strOf("title") ?: return@mapNotNull null
            PlexSection(key, secType, secTitle)
        }
    }

    /**
     * Recently-added items across every section of the given Plex library
     * `type` ("movie" or "show"). Multiple matching sections (e.g. a "4K
     * Movies" library alongside "Movies") are merged and re-sorted by
     * addedAt so the row still reads newest-first.
     *
     * Unlike the cross-section On Deck/global Recently Added endpoints,
     * `/library/sections/{key}/recentlyAdded` doesn't reliably echo
     * `librarySectionID` on every item (it's already implied by the
     * endpoint path) — so items here get [section]'s key/title stamped on
     * directly rather than trusting `toPlexItem()`'s parse of it, or a
     * long-press's "browse this library" would silently have nothing to open.
     */
    suspend fun fetchRecentlyAddedForType(
        host: String,
        token: String,
        sections: List<PlexSection>,
        sectionType: String,
        limit: Int
    ): List<PlexItem>? {
        val matching = sections.filter { it.type == sectionType }
        if (matching.isEmpty()) return null
        val merged = mutableListOf<PlexItem>()
        for (section in matching) {
            fetchItems(host, token, "/library/sections/${section.key}/recentlyAdded", limit)
                ?.map { it.copy(sectionKey = section.key, sectionTitle = section.title) }
                ?.let { merged += it }
        }
        return merged.sortedByDescending { it.addedAt }.take(limit)
    }

    suspend fun fetchItems(host: String, token: String, path: String, limit: Int): List<PlexItem>? {
        val meta = get(apiUrl(host, token, path) + "&X-Plex-Container-Size=$limit")?.mc()?.get("Metadata") as? JsonArray
            ?: return null
        return meta.mapNotNull { el -> (el as? JsonObject)?.toPlexItem() }
    }

    /**
     * One page of every item in a library section (alphabetical), for the
     * long-press "browse this library" dialog — unlike the On Deck/Recently
     * Added rows, this isn't capped at [itemsPerRow][fetchItems]'s limit.
     */
    suspend fun fetchLibraryPage(host: String, token: String, sectionKey: String, start: Int, size: Int): PlexLibraryPage? {
        val path = "/library/sections/$sectionKey/all?sort=titleSort"
        val container = get(apiUrl(host, token, path) + "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$size")?.mc()
            ?: return null
        val meta = container["Metadata"] as? JsonArray
        val items = meta?.mapNotNull { el -> (el as? JsonObject)?.toPlexItem() }.orEmpty()
        return PlexLibraryPage(items, container.intOf("totalSize") ?: items.size)
    }

    /** Full details for one item (movie or episode), plus its season's episode list when it's an episode. */
    suspend fun fetchDetail(host: String, token: String, ratingKey: String): PlexDetail? {
        val meta = get(apiUrl(host, token, ratingKey))?.mc()?.get("Metadata") as? JsonArray ?: return null
        val o = meta.firstOrNull() as? JsonObject ?: return null
        val key = o.strOf("key") ?: return null
        return if (o.strOf("type") == "episode") episodeDetail(host, token, o, key) else movieDetail(o, key)
    }

    private fun movieDetail(o: JsonObject, key: String): PlexDetail {
        val year = o.intOf("year")
        val rating = o.doubleOf("rating") ?: o.doubleOf("audienceRating")
        val metaLine =
            listOfNotNull(year?.toString(), o.longOf("duration")?.let(::formatDuration), rating?.let { "★%.1f".format(it) })
                .joinToString(" · ")
        return PlexDetail(
            ratingKey = key,
            type = "movie",
            headerTitle = o.strOf("title") ?: "?",
            subheading = "",
            metaLine = metaLine,
            summary = o.strOf("summary") ?: "",
            genres = o.genreTags(),
            art = o.strOf("art"),
            thumb = o.strOf("thumb"),
            episodes = null
        )
    }

    private suspend fun episodeDetail(host: String, token: String, o: JsonObject, key: String): PlexDetail {
        val season = o.intOf("parentIndex")
        val epIndex = o.intOf("index")
        val year = o.intOf("year")
        val metaLine = listOfNotNull(year?.toString(), o.longOf("duration")?.let(::formatDuration)).joinToString(" · ")
        val parentRatingKey = o.strOf("parentRatingKey")
        val episodes = parentRatingKey?.let { fetchEpisodes(host, token, it) }.orEmpty()
        return PlexDetail(
            ratingKey = key,
            type = "episode",
            headerTitle = o.strOf("grandparentTitle") ?: "?",
            subheading = "S${season ?: "?"} · E${epIndex ?: "?"} · ${o.strOf("title") ?: ""}",
            metaLine = metaLine,
            summary = o.strOf("summary") ?: "",
            genres = o.genreTags(),
            art = o.strOf("art") ?: o.strOf("grandparentArt"),
            thumb = o.strOf("grandparentThumb") ?: o.strOf("thumb"),
            episodes = episodes
        )
    }

    private suspend fun fetchEpisodes(host: String, token: String, seasonRatingKey: String): List<PlexEpisodeSummary> {
        val meta = get(apiUrl(host, token, "/library/metadata/$seasonRatingKey/children"))?.mc()?.get("Metadata") as? JsonArray
            ?: return emptyList()
        return meta.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.strOf("key") ?: return@mapNotNull null
            PlexEpisodeSummary(key, o.strOf("title") ?: "?", o.intOf("index"), o.strOf("thumb"))
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** One Metadata entry -> PlexItem (row-grid tile), or null if it's missing the bare minimum (a `key`). */
    private fun JsonObject.toPlexItem(): PlexItem? {
        val key = strOf("key") ?: return null
        val addedAt = longOf("addedAt") ?: 0L
        return if (strOf("type") == "episode") episodeItem(key, addedAt) else movieOrShowItem(key, addedAt)
    }

    private fun JsonObject.episodeItem(key: String, addedAt: Long): PlexItem {
        val show = strOf("grandparentTitle") ?: strOf("title") ?: "?"
        val season = intOf("parentIndex")
        val episode = intOf("index")
        return PlexItem(
            key = key,
            title = show,
            subtitle = "S${season ?: "?"}E${episode ?: "?"} · ${strOf("title") ?: ""}",
            thumb = strOf("grandparentThumb") ?: strOf("thumb"),
            addedAt = addedAt,
            sectionKey = strOf("librarySectionID"),
            sectionTitle = strOf("librarySectionTitle")
        )
    }

    private fun JsonObject.movieOrShowItem(key: String, addedAt: Long): PlexItem = PlexItem(
        key = key,
        title = strOf("title") ?: "?",
        subtitle = intOf("year")?.toString() ?: strOf("type").orEmpty(),
        thumb = strOf("thumb"),
        addedAt = addedAt,
        sectionKey = strOf("librarySectionID"),
        sectionTitle = strOf("librarySectionTitle")
    )

    private fun JsonObject.genreTags(): List<String> =
        (this["Genre"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.strOf("tag") }.orEmpty()

    private fun JsonObject.mc(): JsonObject? = this["MediaContainer"] as? JsonObject

    private fun JsonObject.strOf(k: String): String? = (this[k] as? JsonPrimitive)?.content

    private fun JsonObject.intOf(k: String): Int? = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.longOf(k: String): Long? = (this[k] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.doubleOf(k: String): Double? = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
}
