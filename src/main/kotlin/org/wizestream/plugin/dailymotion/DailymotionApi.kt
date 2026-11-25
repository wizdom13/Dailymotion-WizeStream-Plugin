/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.util.Locale
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.localization.Localization

/** All metadata traffic uses the host downloader and a fixed public Dailymotion origin. */
internal class DailymotionApi(private val downloader: Downloader, private val localization: Localization) {
    fun get(path: String, parameters: Map<String, String> = emptyMap()): JsonObject {
        val relative = path.removePrefix("/")
        if (!Regex("[A-Za-z0-9_./-]+").matches(relative) || relative.startsWith('/') ||
            relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ParsingException("Invalid Dailymotion API path")
        }
        val query = parameters.entries.joinToString("&") {
            "${DailymotionUrls.encode(it.key)}=${DailymotionUrls.encode(it.value)}"
        }
        return read("${DailymotionUrls.API_URL}/$relative" + if (query.isEmpty()) "" else "?$query")
    }

    fun playerMetadata(id: String): JsonObject = read(
        "${DailymotionUrls.BASE_URL}/player/metadata/video/${DailymotionUrls.requireId(id)}"
    )

    private fun read(url: String): JsonObject {
        val response = downloader.get(url, mapOf("Accept" to listOf("application/json")), localization)
        val status = response.responseCode()
        val body = response.responseBody()
        val json = try { JsonParser.`object`().from(body) } catch (error: Exception) {
            if (status == 429) throw ExtractionException("Dailymotion is rate limiting requests; try again later")
            if (status == 401 || status == 403) throw ContentNotAvailableException("Dailymotion refused public access (HTTP $status)")
            if (status == 404 || status == 410) throw ContentNotAvailableException("Dailymotion content is unavailable")
            if (status !in 200..299) throw ExtractionException("Dailymotion request failed (HTTP $status)")
            throw ParsingException("Dailymotion returned invalid metadata", error)
        }
        val error = json["error"]
        if (error != null && error != false) {
            val details = if (error is JsonObject) {
                listOf(error["code"], error["type"], error["title"], error["message"], error["raw_message"],
                    (error["error_data"] as? JsonObject)?.get("reason")).filterNotNull().joinToString(" ")
            } else error.toString()
            unavailable(details, status)
        }
        if (status !in 200..299) unavailable("", status)
        return json
    }

    private fun unavailable(details: String, status: Int): Nothing {
        val reason = details.lowercase(Locale.ROOT)
        // Return host-recognized errors; do not attempt to evade account, age, or geographic gates.
        if (reason.contains("dm007") || reason.contains("geo") || reason.contains("country") || reason.contains("region")) {
            throw GeographicRestrictionException("This Dailymotion video is not available in your region")
        }
        if (Regex("\\bage(?:[_ -](?:restricted|verification|limit))?\\b").containsMatchIn(reason) || reason.contains("explicit") || reason.contains("adult")) {
            throw AgeRestrictedContentException("This Dailymotion video requires age verification")
        }
        if (reason.contains("private") || reason.contains("password") || reason.contains("login") ||
            reason.contains("authenticated") || reason.contains("authentication") || status == 401) {
            throw PrivateContentException("This Dailymotion content requires an account or is private")
        }
        if (status == 429 || reason.contains("rate_limit")) {
            throw ExtractionException("Dailymotion is rate limiting requests; try again later")
        }
        if (status >= 500) throw ExtractionException("Dailymotion is temporarily unavailable (HTTP $status)")
        throw ContentNotAvailableException("This Dailymotion content is unavailable or cannot be played publicly")
    }
}
