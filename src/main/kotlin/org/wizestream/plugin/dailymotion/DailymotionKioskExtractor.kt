/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class DailymotionKioskExtractor(service: StreamingService, handler: ListLinkHandler, kioskId: String) : KioskExtractor<StreamInfoItem>(service, handler, kioskId) {
    private val path = "/videos"
    private val parameters = mapOf("sort" to "trending")
    private lateinit var initial: InfoItemsPage<StreamInfoItem>

    override fun onFetchPage(downloader: Downloader) {
        initial = load(1)
    }

    override fun getName(): String = "Trending"

    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        assertPageFetched()
        return initial
    }

    override fun getPage(page: Page?): InfoItemsPage<StreamInfoItem> = load(DailymotionCatalog.pageNumber(page, DailymotionCatalog.listScope(path, parameters)))

    private fun load(number: Int): InfoItemsPage<StreamInfoItem> = DailymotionCatalog.streams(
        DailymotionApi(downloader, extractorLocalization), serviceId, path, parameters, number
    )
}
