/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import org.schabi.newpipe.extractor.exceptions.ParsingException

internal object DailymotionUrls {
    const val BASE_URL = "https://www.dailymotion.com"
    const val API_URL = "https://api.dailymotion.com"
    private val siteHosts = setOf("dailymotion.com", "www.dailymotion.com", "m.dailymotion.com")
    private val idPattern = Regex("[A-Za-z0-9]{1,80}")
    private val channelPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    private val reserved = setOf(
        "video", "videos", "playlist", "playlists", "embed", "player", "search", "user", "users",
        "trending", "explore", "live", "library", "feed", "following", "signin", "signup", "login",
        "logout", "settings", "upload", "partner", "partners", "premium", "legal", "about", "help",
        "country", "channel", "channels", "fr", "en", "us", "gb", "de", "es", "it"
    )

    fun videoId(url: String): String {
        val uri = parse(url)
        val parts = segments(uri)
        val host = uri.host.lowercase(Locale.ROOT)
        val token = when {
            host in setOf("dai.ly", "www.dai.ly") && parts.size == 1 -> parts[0]
            host in siteHosts && parts.size == 2 && parts[0] == "video" -> parts[1]
            host in siteHosts && parts.size == 3 && parts.take(2) == listOf("embed", "video") -> parts[2]
            host in siteHosts && parts.size == 3 && parts.take(2) == listOf("crawler", "video") -> parts[2]
            host == "geo.dailymotion.com" && parts == listOf("player.html") -> query(uri, "video")
            host == "geo.dailymotion.com" && parts.size == 2 && parts[0] == "player" &&
                Regex("[A-Za-z0-9]+\\.html").matches(parts[1]) -> query(uri, "video")
            else -> throw ParsingException("Not a Dailymotion video URL")
        }
        return requireId(token.substringBefore('_'))
    }

    fun playlistId(url: String): String {
        val uri = parseSite(url)
        val parts = segments(uri)
        if (parts.size != 2 || parts[0] != "playlist") throw ParsingException("Not a Dailymotion playlist URL")
        return requireId(parts[1].substringBefore('_'))
    }

    fun channelId(url: String): String {
        val uri = parseSite(url)
        val parts = segments(uri)
        val name = when {
            parts.size == 1 -> parts[0]
            parts.size == 2 && parts[0] == "user" -> parts[1]
            parts.size == 2 && parts[1] in setOf("videos", "playlists") -> parts[0]
            else -> throw ParsingException("Not a Dailymotion channel URL")
        }
        if (name.lowercase(Locale.ROOT) in reserved) throw ParsingException("Reserved Dailymotion route")
        return requireChannelId(name)
    }

    fun videoUrl(id: String): String = "$BASE_URL/video/${requireId(id)}"
    fun playlistUrl(id: String): String = "$BASE_URL/playlist/${requireId(id)}"
    fun channelUrl(id: String): String = "$BASE_URL/${requireChannelId(id)}"
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun requireId(id: String): String {
        if (!idPattern.matches(id)) throw ParsingException("Invalid Dailymotion identifier")
        return id
    }

    fun requireChannelId(id: String): String {
        if (!channelPattern.matches(id) || id == "." || id == "..") {
            throw ParsingException("Invalid Dailymotion channel identifier")
        }
        return id
    }

    fun parseSite(url: String): URI = parse(url).also {
        if (it.host.lowercase(Locale.ROOT) !in siteHosts) throw ParsingException("Not a Dailymotion site URL")
    }

    fun query(uri: URI, key: String): String {
        val matches = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val pieces = pair.split('=', limit = 2)
            if (decode(pieces[0]) == key) decode(pieces.getOrElse(1) { "" }) else null
        }
        if (matches.size != 1) throw ParsingException("Missing or ambiguous Dailymotion URL parameter")
        return matches.single()
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (error: IllegalArgumentException) {
        throw ParsingException("Invalid Dailymotion URL encoding", error)
    }

    private fun parse(url: String): URI {
        val uri = try { URI(url.trim()) } catch (error: Exception) {
            throw ParsingException("Invalid Dailymotion URL", error)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("https", "http") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80)) {
            throw ParsingException("Invalid Dailymotion URL origin")
        }
        return uri
    }

    private fun segments(uri: URI): List<String> = uri.path.orEmpty().trim('/').split('/').also {
        if (it.any { part -> part.isEmpty() || part == "." || part == ".." }) {
            throw ParsingException("Invalid Dailymotion URL path")
        }
    }
}
