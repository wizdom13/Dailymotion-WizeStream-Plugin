/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import java.net.URLDecoder
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.search.filter.Filter
import org.schabi.newpipe.extractor.search.filter.FilterGroup
import org.schabi.newpipe.extractor.search.filter.FilterItem

internal class DailymotionStreamLinkHandlerFactory : LinkHandlerFactory() {
    override fun getId(url: String): String = DailymotionUrls.videoId(url)
    override fun getUrl(id: String): String = DailymotionUrls.videoUrl(id)
    override fun onAcceptUrl(url: String): Boolean = accepts { getId(url) }
}

internal class DailymotionChannelLinkHandlerFactory : ListLinkHandlerFactory() {
    override fun getId(url: String): String = DailymotionUrls.channelId(url)
    override fun getUrl(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): String = DailymotionUrls.channelUrl(id)
    override fun onAcceptUrl(url: String): Boolean = accepts { getId(url) }
}

internal class DailymotionPlaylistLinkHandlerFactory : ListLinkHandlerFactory() {
    override fun getId(url: String): String = DailymotionUrls.playlistId(url)
    override fun getUrl(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): String = DailymotionUrls.playlistUrl(id)
    override fun onAcceptUrl(url: String): Boolean = accepts { getId(url) }
}

internal class DailymotionChannelTabLinkHandlerFactory : ListLinkHandlerFactory() {
    override fun getId(url: String): String = DailymotionUrls.channelId(url)
    override fun onAcceptUrl(url: String): Boolean = accepts { getId(url) }
    override fun getUrl(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): String {
        val tab = contentFilter.orEmpty().singleOrNull()?.name ?: ChannelTabs.VIDEOS
        if (contentFilter.orEmpty().size > 1 || tab !in setOf(ChannelTabs.VIDEOS, ChannelTabs.PLAYLISTS) || !sortFilter.isNullOrEmpty()) {
            throw ContentNotSupportedException("Dailymotion provides Videos and Playlists channel tabs")
        }
        return "${DailymotionUrls.channelUrl(id)}/$tab"
    }

    override fun fromUrl(url: String, baseUrl: String): ListLinkHandler {
        val handler = super.fromUrl(url, baseUrl)
        val tab = if (DailymotionUrls.parseSite(url).path.trimEnd('/').endsWith("/playlists")) ChannelTabs.PLAYLISTS else ChannelTabs.VIDEOS
        return withTab(handler, tab)
    }
    override fun fromId(id: String): ListLinkHandler = withTab(super.fromId(id), ChannelTabs.VIDEOS)
    override fun fromId(id: String, baseUrl: String): ListLinkHandler = withTab(super.fromId(id, baseUrl), ChannelTabs.VIDEOS)
    override fun fromQuery(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): ListLinkHandler =
        super.fromQuery(id, contentFilter?.takeIf { it.isNotEmpty() } ?: listOf(FilterItem(1, ChannelTabs.VIDEOS)), sortFilter)
    override fun fromQuery(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?, baseUrl: String): ListLinkHandler =
        fromQuery(id, contentFilter, sortFilter)
    private fun withTab(handler: ListLinkHandler, tab: String): ListLinkHandler = ListLinkHandler(
        handler.originalUrl, "${DailymotionUrls.channelUrl(handler.id)}/$tab", handler.id,
        listOf(FilterItem(if (tab == ChannelTabs.VIDEOS) 1 else 2, tab)), emptyList()
    )
}

internal class DailymotionSearchQueryHandlerFactory : SearchQueryHandlerFactory() {
    private val videos = FilterItem(1, "videos")
    private val channels = FilterItem(2, "channels")
    private val sorts = arrayOf(FilterItem(10, "relevance"), FilterItem(11, "recent"), FilterItem(12, "visited"))
    private val content = filter(100, arrayOf(videos, channels))
    private val sort = filter(101, sorts)

    override fun getUrl(query: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): String {
        if (query.isBlank()) throw ParsingException("Enter a Dailymotion search query")
        val type = contentFilter.orEmpty().singleOrNull()?.name ?: "videos"
        if (contentFilter.orEmpty().size > 1 || type !in setOf("videos", "channels") ||
            sortFilter.orEmpty().size > 1 || sortFilter.orEmpty().any { it.name !in setOf("relevance", "recent", "visited") }) {
            throw ContentNotSupportedException("Unsupported Dailymotion search filter")
        }
        return "${DailymotionUrls.BASE_URL}/search/${DailymotionUrls.encode(query).replace("+", "%20")}/$type"
    }

    override fun getSearchString(url: String): String = try {
        val parts = DailymotionUrls.parseSite(url).rawPath.trim('/').split('/')
        if (parts.size == 3 && parts[0] == "search" && parts[2] in setOf("videos", "channels")) {
            URLDecoder.decode(parts[1], "UTF-8")
        } else ""
    } catch (_: Exception) { "" }

    override fun getAvailableContentFilter(): Filter = content
    override fun getAvailableSortFilter(): Filter = sort
    override fun getContentFilterSortFilterVariant(contentFilterId: Int): Filter =
        if (contentFilterId == channels.identifier) Filter.Builder(emptyArray()).build() else sort
    override fun getFilterItem(filterId: Int): FilterItem? = (listOf(videos, channels) + sorts).firstOrNull { it.identifier == filterId }
    private fun filter(groupId: Int, items: Array<FilterItem>): Filter =
        Filter.Builder(arrayOf(FilterGroup(groupId, null, true, items))).setNoOfFilters(items.size).build()
}

internal class DailymotionKioskLinkHandlerFactory : ListLinkHandlerFactory() {
    override fun getId(url: String): String {
        val uri = DailymotionUrls.parseSite(url)
        if (uri.path.trim('/') != "trending") throw ParsingException("Not a Dailymotion discovery URL")
        return "Trending"
    }
    override fun getUrl(id: String, contentFilter: List<FilterItem>?, sortFilter: List<FilterItem>?): String {
        if (id != "Trending") throw ParsingException("Unknown Dailymotion discovery feed")
        return "${DailymotionUrls.BASE_URL}/trending"
    }
    override fun onAcceptUrl(url: String): Boolean = accepts { getId(url) }
}

private inline fun accepts(block: () -> String): Boolean = try { block(); true } catch (_: ParsingException) { false }
