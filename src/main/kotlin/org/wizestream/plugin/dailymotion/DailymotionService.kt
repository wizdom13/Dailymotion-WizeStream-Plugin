/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor
import org.schabi.newpipe.extractor.comments.CommentsExtractor
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.kiosk.KioskList
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandlerFactory
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor

class DailymotionService(id: Int) : StreamingService(
    id, "Dailymotion", listOf(ServiceInfo.MediaCapability.VIDEO, ServiceInfo.MediaCapability.LIVE)
) {
    override fun getBaseUrl(): String = DailymotionUrls.BASE_URL
    // Player URLs are signed and short-lived; resolve fresh metadata when a cached info expires.
    override fun getInfoCacheExpirationMillis(): Long = 5 * 60 * 1000L
    override fun getStreamLHFactory(): LinkHandlerFactory = DailymotionStreamLinkHandlerFactory()
    override fun getChannelLHFactory(): ListLinkHandlerFactory = DailymotionChannelLinkHandlerFactory()
    override fun getChannelTabLHFactory(): ListLinkHandlerFactory = DailymotionChannelTabLinkHandlerFactory()
    override fun getPlaylistLHFactory(): ListLinkHandlerFactory = DailymotionPlaylistLinkHandlerFactory()
    override fun getSearchQHFactory(): SearchQueryHandlerFactory = DailymotionSearchQueryHandlerFactory()
    override fun getCommentsLHFactory(): ListLinkHandlerFactory? = null
    override fun getSuggestionExtractor(): SuggestionExtractor? = null
    override fun getSubscriptionExtractor(): SubscriptionExtractor? = null
    override fun getStreamExtractor(linkHandler: LinkHandler): StreamExtractor = DailymotionStreamExtractor(this, linkHandler)
    override fun getSearchExtractor(queryHandler: SearchQueryHandler): SearchExtractor = DailymotionSearchExtractor(this, queryHandler)
    override fun getChannelExtractor(linkHandler: ListLinkHandler): ChannelExtractor = DailymotionChannelExtractor(this, linkHandler)
    override fun getChannelTabExtractor(linkHandler: ListLinkHandler): ChannelTabExtractor = DailymotionChannelTabExtractor(this, linkHandler)
    override fun getPlaylistExtractor(linkHandler: ListLinkHandler): PlaylistExtractor = DailymotionPlaylistExtractor(this, linkHandler)
    override fun getCommentsExtractor(linkHandler: ListLinkHandler): CommentsExtractor = throw ContentNotSupportedException("Dailymotion public comments are not supported")
    override fun getCommentsExtractor(url: String): CommentsExtractor = throw ContentNotSupportedException("Dailymotion public comments are not supported")

    override fun getKioskList(): KioskList = KioskList(this).apply {
        val handler = DailymotionKioskLinkHandlerFactory()
        addKioskEntry({ service, url, kiosk -> DailymotionKioskExtractor(service, handler.fromUrl(url), kiosk) }, handler, "Trending")
        setDefaultKiosk("Trending")
    }
}
