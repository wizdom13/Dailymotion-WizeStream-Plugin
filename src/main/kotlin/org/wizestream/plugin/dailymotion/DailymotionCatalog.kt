/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.MultiInfoItemsCollector
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector

/** Shared public API projections and bounded, query-bound pagination. */
internal object DailymotionCatalog {
    const val VIDEO_FIELDS = "id,title,duration,created_time,thumbnail_720_url,views_total,mode,onair,owner.id,owner.screenname,owner.username,owner.avatar_360_url,owner.verified"
    const val USER_FIELDS = "id,username,screenname,description,avatar_360_url,cover_250_url,followers_total,videos_total,verified,created_time"
    const val PLAYLIST_FIELDS = "id,name,description,thumbnail_720_url,videos_total,owner.id,owner.screenname,owner.username,owner.avatar_360_url"
    const val LIMIT = 20
    private const val MAX_PAGE = 1000

    fun required(json: JsonObject, key: String): String = text(json, key).takeIf { it.isNotBlank() }
        ?: throw ParsingException("Dailymotion response is missing $key")

    fun text(json: JsonObject, key: String): String = json[key] as? String ?: ""

    fun count(json: JsonObject, key: String): Long = (json[key] as? Number)?.toLong()?.takeIf { it >= 0 } ?: -1L

    fun image(json: JsonObject, key: String): String {
        val value = text(json, key).let { if (it.startsWith("//")) "https:$it" else it }
        val uri = runCatching { URI(value) }.getOrNull() ?: return ""
        return value.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }.orEmpty()
    }

    fun ownerId(json: JsonObject): String = text(json, "owner.id").ifBlank { text(json, "owner.username") }

    fun ownerName(json: JsonObject): String = text(json, "owner.screenname").ifBlank {
        text(json, "owner.username").ifBlank { text(json, "owner.id") }
    }

    fun ownerUrl(json: JsonObject): String = ownerId(json).takeIf(String::isNotBlank)?.let(DailymotionUrls::channelUrl).orEmpty()

    fun date(json: JsonObject, field: String = "created_time"): DateWrapper? {
        val epoch = count(json, field).takeIf { it > 0 } ?: return null
        return runCatching { DateWrapper(Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC)) }.getOrNull()
    }

    fun scope(path: String, parameters: Map<String, String>): String = "https://api.dailymotion.com/${path.trimStart('/')}?" +
        parameters.toSortedMap().entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    fun pageNumber(page: Page?, scope: String): Int {
        val number = page?.id?.toIntOrNull()
        if (page?.url != scope || number == null || number !in 2..MAX_PAGE) {
            throw ParsingException("Invalid or unrelated Dailymotion page")
        }
        return number
    }

    private fun nextPage(response: JsonObject, number: Int, scope: String): Page? {
        val returnedPage = (response["page"] as? Number)?.toInt()
        if (returnedPage != null && returnedPage != number) throw ParsingException("Dailymotion returned an unexpected page")
        val hasMore = response["has_more"] as? Boolean ?: throw ParsingException("Dailymotion response is missing pagination metadata")
        return if (hasMore && number < MAX_PAGE) Page(scope, (number + 1).toString()) else null
    }

    fun streams(api: DailymotionApi, serviceId: Int, path: String, parameters: Map<String, String>, number: Int): InfoItemsPage<StreamInfoItem> {
        val query = parameters + mapOf("fields" to VIDEO_FIELDS, "limit" to LIMIT.toString())
        val response = api.get(path, query + ("page" to number.toString()))
        val collector = StreamInfoItemsCollector(serviceId)
        objects(response).forEach { collector.commit(DailymotionStreamItemExtractor(it)) }
        return InfoItemsPage(collector, nextPage(response, number, scope(path, query)))
    }

    fun mixed(api: DailymotionApi, serviceId: Int, path: String, parameters: Map<String, String>, number: Int, kind: String): InfoItemsPage<InfoItem> {
        val fields = when (kind) {
            "channels" -> USER_FIELDS
            "playlists" -> PLAYLIST_FIELDS
            else -> VIDEO_FIELDS
        }
        val query = parameters + mapOf("fields" to fields, "limit" to LIMIT.toString())
        val response = api.get(path, query + ("page" to number.toString()))
        val collector = MultiInfoItemsCollector(serviceId)
        objects(response).forEach {
            collector.commit(when (kind) {
                "channels" -> DailymotionChannelItemExtractor(it)
                "playlists" -> DailymotionPlaylistItemExtractor(it)
                else -> DailymotionStreamItemExtractor(it)
            })
        }
        return InfoItemsPage(collector, nextPage(response, number, scope(path, query)))
    }

    fun listScope(path: String, parameters: Map<String, String>, fields: String = VIDEO_FIELDS): String =
        scope(path, parameters + mapOf("fields" to fields, "limit" to LIMIT.toString()))

    private fun objects(response: JsonObject): List<JsonObject> {
        val list = response["list"] as? JsonArray ?: throw ParsingException("Dailymotion response is missing its item list")
        return list.map { it as? JsonObject ?: throw ParsingException("Malformed Dailymotion list item") }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
