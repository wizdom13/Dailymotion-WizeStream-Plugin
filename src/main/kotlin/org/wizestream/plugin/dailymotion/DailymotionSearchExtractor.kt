/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor

class DailymotionSearchExtractor(service: StreamingService, handler: SearchQueryHandler) : SearchExtractor(service, handler) {
    private val kind = handler.contentFilters.firstOrNull()?.name ?: "videos"
    private val path = if (kind == "channels") "/users" else "/videos"
    private val parameters: Map<String, String>
    private lateinit var initial: InfoItemsPage<InfoItem>

    init {
        if (kind !in setOf("videos", "channels") || handler.contentFilters.size > 1) {
            throw ContentNotSupportedException("Dailymotion supports video and channel search")
        }
        val sort = handler.sortFilter?.firstOrNull()?.name ?: "relevance"
        if (kind == "videos" && (sort !in setOf("relevance", "recent", "visited") || handler.sortFilter.orEmpty().size > 1)) {
            throw ContentNotSupportedException("Unsupported Dailymotion video sort")
        }
        parameters = mapOf("search" to handler.searchString) + if (kind == "videos") mapOf("sort" to sort) else emptyMap()
    }

    override fun onFetchPage(downloader: Downloader) {
        initial = load(1)
    }

    override fun getInitialPageInternal(): InfoItemsPage<InfoItem> {
        assertPageFetched()
        return initial
    }

    override fun getPageInternal(page: Page?): InfoItemsPage<InfoItem> {
        val fields = if (kind == "channels") DailymotionCatalog.USER_FIELDS else DailymotionCatalog.VIDEO_FIELDS
        return load(DailymotionCatalog.pageNumber(page, DailymotionCatalog.listScope(path, parameters, fields)))
    }

    private fun load(number: Int): InfoItemsPage<InfoItem> =
        DailymotionCatalog.mixed(DailymotionApi(downloader, extractorLocalization), serviceId, path, parameters, number, kind)
}
