/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class DailymotionChannelTabExtractor(service: StreamingService, handler: ListLinkHandler) : ChannelTabExtractor(service, handler) {
    private val kind = handler.contentFilters.singleOrNull()?.name
    private val playlists = kind == ChannelTabs.PLAYLISTS
    private val path = "/user/${handler.id}/${if (playlists) "playlists" else "videos"}"
    private val parameters = if (playlists) emptyMap() else mapOf("sort" to "recent")
    private lateinit var initial: InfoItemsPage<InfoItem>

    init {
        if (kind !in setOf(ChannelTabs.VIDEOS, ChannelTabs.PLAYLISTS) || !handler.sortFilter.isNullOrEmpty()) {
            throw ContentNotSupportedException("Dailymotion provides Videos and Playlists channel tabs")
        }
    }

    override fun onFetchPage(downloader: Downloader) {
        initial = load(1)
    }

    override fun getInitialPage(): InfoItemsPage<InfoItem> {
        assertPageFetched()
        return initial
    }

    override fun getPage(page: Page?): InfoItemsPage<InfoItem> = load(DailymotionCatalog.pageNumber(
        page,
        DailymotionCatalog.listScope(path, parameters, if (playlists) DailymotionCatalog.PLAYLIST_FIELDS else DailymotionCatalog.VIDEO_FIELDS)
    ))

    private fun load(number: Int): InfoItemsPage<InfoItem> = DailymotionCatalog.mixed(
        DailymotionApi(downloader, extractorLocalization), serviceId, path, parameters, number,
        if (playlists) "playlists" else "videos"
    )
}
