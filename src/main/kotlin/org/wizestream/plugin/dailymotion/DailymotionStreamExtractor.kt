/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

/** Uses the anonymous player's advertised sources; no partner keys or login are required. */
class DailymotionStreamExtractor(service: StreamingService, linkHandler: LinkHandler) :
    StreamExtractor(service, linkHandler) {
    private val requestedId = linkHandler.id
    private lateinit var api: DailymotionApi
    private var metadata = JsonObject()
    private var statistics = JsonObject()
    private var videos = emptyList<VideoStream>()
    private var subtitles = emptyList<SubtitlesStream>()
    private var type = StreamType.VIDEO_STREAM
    private val playbackHeaders = mapOf(
        "Referer" to DailymotionUrls.videoUrl(requestedId),
        "Origin" to DailymotionUrls.BASE_URL
    )

    override fun onFetchPage(downloader: Downloader) {
        api = DailymotionApi(downloader, extractorLocalization)
        // Reset results before fetching so a failed refresh cannot retain expired signed URLs.
        metadata = JsonObject()
        statistics = JsonObject()
        videos = emptyList()
        subtitles = emptyList()
        type = StreamType.VIDEO_STREAM
        metadata = api.playerMetadata(requestedId)
        val returnedId = metadata.getString("id", "")
        if (returnedId.isBlank() || (!requestedId.startsWith("k") && returnedId != requestedId)) {
            throw ParsingException("Dailymotion player metadata belongs to a different video")
        }
        if (metadata.getBoolean("is_password_protected", false)) {
            throw PrivateContentException("This Dailymotion video requires a password")
        }
        if (hasDrm(metadata)) {
            throw ContentNotSupportedException("This Dailymotion video requires DRM playback")
        }
        getName() // A valid response must describe the requested video, not a placeholder.
        type = when (metadata.getString("stream_type", "")) {
            "live" -> StreamType.LIVE_STREAM
            "recorded" -> StreamType.VIDEO_STREAM
            else -> if (metadata.getString("mode", "") == "live") {
                StreamType.LIVE_STREAM
            } else {
                StreamType.VIDEO_STREAM
            }
        }
        if (type == StreamType.LIVE_STREAM && metadata["onair"] == false) {
            throw ContentNotAvailableException("This Dailymotion live stream is currently off air")
        }
        videos = parseVideos()
        if (videos.isEmpty()) {
            throw ContentNotAvailableException("Dailymotion did not provide a supported public video source")
        }
        subtitles = parseSubtitles()
        // Optional statistics are not a prerequisite for playing an otherwise accessible video.
        statistics = try {
            api.get("video/$returnedId", mapOf("fields" to STATISTICS_FIELDS))
        } catch (_: IOException) {
            JsonObject()
        } catch (_: ExtractionException) {
            JsonObject()
        }
    }

    private fun parseVideos(): List<VideoStream> {
        val qualities = metadata["qualities"] as? JsonObject ?: return emptyList()
        val result = mutableListOf<VideoStream>()
        val seen = mutableSetOf<String>()
        for ((quality, entries) in qualities) {
            for (entry in (entries as? List<*>).orEmpty().filterIsInstance<JsonObject>()) {
                if (hasDrm(entry)) continue
                val url = mediaUrl(entry.getString("url")) ?: continue
                val mime = entry.getString("type", "").substringBefore(';').trim().lowercase(Locale.ROOT)
                val delivery = when (mime) {
                    "video/mp4" -> DeliveryMethod.PROGRESSIVE_HTTP
                    "application/x-mpegurl", "application/vnd.apple.mpegurl" -> DeliveryMethod.HLS
                    "application/dash+xml" -> DeliveryMethod.DASH
                    // Dailymotion's proprietary Lumberjack manifest is not an HLS/DASH source.
                    else -> continue
                }
                if (!seen.add("${delivery.name}:$url")) continue
                val numericQuality = quality.removeSuffix("p").substringBefore('@').toIntOrNull()
                val resolution = numericQuality?.takeIf { it > 0 }?.let { "${it}p" }
                    ?: VideoStream.RESOLUTION_UNKNOWN
                result += VideoStream.Builder()
                    .setId("dailymotion-${delivery.name.lowercase(Locale.ROOT)}-$quality-${result.size}")
                    .setContent(url, true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(delivery)
                    .setManifestUrl(if (delivery == DeliveryMethod.PROGRESSIVE_HTTP) null else url)
                    .setIsVideoOnly(false)
                    .setResolution(resolution)
                    .setRequestHeaders(playbackHeaders)
                    .setCacheable(false)
                    .build()
            }
        }
        return result
    }

    private fun parseSubtitles(): List<SubtitlesStream> {
        val captions = metadata["subtitles"] as? JsonObject ?: return emptyList()
        // Dailymotion returns [] when empty and a language-keyed object when tracks exist.
        val tracks = captions["data"] as? JsonObject ?: return emptyList()
        val result = mutableListOf<SubtitlesStream>()
        val seen = mutableSetOf<String>()
        for ((language, rawTrack) in tracks) {
            val languageCode = language.replace('_', '-')
            if (!LANGUAGE.matches(languageCode)) continue
            val track = rawTrack as? JsonObject ?: continue
            for (rawUrl in (track["urls"] as? List<*>).orEmpty()) {
                val url = mediaUrl(rawUrl as? String) ?: continue
                val format = subtitleFormat(url, track.getString("format", "")) ?: continue
                if (!seen.add("$languageCode:$url")) continue
                result += SubtitlesStream.Builder()
                    .setId("dailymotion-$languageCode-${result.size}")
                    .setContent(url, true)
                    .setMediaFormat(format)
                    .setLanguageCode(languageCode)
                    .setAutoGenerated(track.getBoolean("auto_generated", false) || track.getBoolean("is_auto_generated", false))
                    .setRequestHeaders(playbackHeaders)
                    .setCacheable(false)
                    .build()
            }
        }
        return result
    }

    override fun getName(): String = metadata.getString("title", "").takeIf { it.isNotBlank() }
        ?: throw ParsingException("Dailymotion did not provide a video title")

    override fun getThumbnailUrl(): String = largestImage(metadata["posters"] as? JsonObject)
        .ifBlank { largestImage(metadata["thumbnails"] as? JsonObject) }

    override fun getDescription(): Description = Description(metadata.getString("description", ""), Description.HTML)

    override fun getLength(): Long = if (type == StreamType.LIVE_STREAM) 0L else metadata.getLong("duration", -1L)

    override fun getAgeLimit(): Int = if (metadata.getBoolean("explicit", false)) 18 else 0

    override fun getViewCount(): Long = if (type == StreamType.LIVE_STREAM) {
        statistics.getLong("audience", -1L)
    } else {
        statistics.getLong("views_total", metadata.getLong("views_total", -1L))
    }

    override fun getLikeCount(): Long = statistics.getLong("likes_total", -1L)

    override fun getUploaderName(): String = owner().getString("screenname", "")
        .ifBlank { owner().getString("username", "") }

    override fun getUploaderUrl(): String {
        val owner = owner()
        val id = owner.getString("id", "").ifBlank { owner.getString("username", "") }
        return if (id.isBlank()) "" else try {
            DailymotionUrls.channelUrl(id)
        } catch (_: ParsingException) {
            ""
        }
    }

    override fun getUploaderAvatarUrl(): String = largestImage(owner()["avatars"] as? JsonObject)

    override fun isUploaderVerified(): Boolean = statistics.getBoolean("owner.verified", metadata.getBoolean("verified", false))

    override fun getTextualUploadDate(): String? = uploadTime()?.toString()

    override fun getUploadDate(): DateWrapper? = uploadTime()?.let(::DateWrapper)

    override fun getHost(): String = "dailymotion.com"

    override fun getPrivacy(): Privacy = if (metadata.getBoolean("private", false) || requestedId.startsWith("k")) {
        Privacy.UNLISTED
    } else {
        Privacy.PUBLIC
    }

    override fun getCategory(): String = metadata.getString("channel", "")

    override fun getLanguageInfo(): Locale? = metadata.getString("language", "").takeIf { LANGUAGE.matches(it) }
        ?.let(Locale::forLanguageTag)

    override fun getTags(): List<String> = (metadata["tags"] as? List<*>).orEmpty().filterIsInstance<String>().distinct()

    override fun getTimeStamp(): Long {
        val uri = try { URI(originalUrl) } catch (_: Exception) { return 0L }
        val values = try {
            listOfNotNull(uri.rawQuery, uri.rawFragment).flatMap { it.split('&') }.mapNotNull { part ->
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                if (name !in setOf("start", "t")) return@mapNotNull null
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            return 0L
        }
        return values.singleOrNull()?.toLongOrNull()?.takeIf { it in 0L..(Long.MAX_VALUE / 1000L) } ?: 0L
    }

    override fun getStreamType(): StreamType = type
    override fun getVideoStreams(): List<VideoStream> = videos
    override fun getVideoOnlyStreams(): List<VideoStream> = emptyList()
    override fun getAudioStreams(): List<AudioStream> = emptyList()
    override fun getHlsUrl(): String = videos.firstOrNull { it.deliveryMethod == DeliveryMethod.HLS }?.content.orEmpty()
    override fun getDashMpdUrl(): String = videos.firstOrNull { it.deliveryMethod == DeliveryMethod.DASH }?.content.orEmpty()
    override fun getManifestRequestHeaders(): Map<String, String> = playbackHeaders
    override fun getSubtitlesDefault(): List<SubtitlesStream> = subtitles
    override fun getSubtitles(format: MediaFormat): List<SubtitlesStream> = subtitles.filter { it.format == format }
    override fun isSupportComments(): Boolean = false
    override fun isSupportBulletComments(): Boolean = false
    override fun isSupportRelatedItems(): Boolean = true

    override fun getRelatedItems(): StreamInfoItemsCollector {
        val collector = StreamInfoItemsCollector(serviceId)
        val result = api.get("video/${metadata.getString("id")}/related", mapOf(
            "fields" to DailymotionCatalog.VIDEO_FIELDS,
            "limit" to "20"
        ))
        val seen = mutableSetOf(metadata.getString("id"))
        for (item in (result["list"] as? List<*>).orEmpty().filterIsInstance<JsonObject>()) {
            val itemId = item.getString("id", "")
            if (itemId.isNotBlank() && seen.add(itemId)) collector.commit(DailymotionStreamItemExtractor(item))
        }
        return collector
    }

    private fun owner(): JsonObject = metadata["owner"] as? JsonObject ?: JsonObject()

    private fun uploadTime() = if (type == StreamType.LIVE_STREAM) {
        null
    } else {
        metadata.getLong("created_time", -1L).takeIf { it > 0 }?.let {
            try { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC) } catch (_: DateTimeException) { null }
        }
    }

    private fun largestImage(images: JsonObject?): String = images?.entries
        ?.sortedByDescending { it.key.toIntOrNull() ?: -1 }
        ?.firstNotNullOfOrNull { mediaUrl(it.value as? String) }.orEmpty()

    private fun subtitleFormat(url: String, declaredFormat: String): MediaFormat? {
        val extension = URI(url).path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension.ifBlank { declaredFormat.lowercase(Locale.ROOT) }) {
            "vtt" -> MediaFormat.VTT
            "srt" -> MediaFormat.SRT
            "ttml", "dfxp" -> MediaFormat.TTML
            else -> null
        }
    }

    private fun mediaUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val url = if (value.startsWith("//")) "https:$value" else value
        val uri = try { URI(url) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        return url.substringBefore('#')
    }

    private fun hasDrm(value: JsonObject): Boolean = listOf("drm", "drm_info", "drm_required", "license_url").any { key ->
        when (val flag = value[key]) {
            null, false -> false
            is String -> flag.isNotBlank() && flag != "false"
            is Map<*, *> -> flag.isNotEmpty()
            is List<*> -> flag.isNotEmpty()
            is Number -> flag.toInt() != 0
            else -> true
        }
    }

    companion object {
        private const val STATISTICS_FIELDS = "id,views_total,likes_total,audience,owner.verified"
        private val LANGUAGE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
    }
}
