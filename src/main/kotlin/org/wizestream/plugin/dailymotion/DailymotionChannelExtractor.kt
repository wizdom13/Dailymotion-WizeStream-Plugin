/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class DailymotionChannelExtractor(service: StreamingService, handler: ListLinkHandler) : ChannelExtractor(service, handler) {
    private lateinit var metadata: JsonObject
    private var initial: InfoItemsPage<StreamInfoItem>? = null
    private val parameters = mapOf("sort" to "recent")
    private val api get() = DailymotionApi(downloader, extractorLocalization)
    private val path get() = "/user/${getId()}/videos"

    override fun onFetchPage(downloader: Downloader) {
        metadata = api.get("/user/${linkHandler.id}", mapOf("fields" to DailymotionCatalog.USER_FIELDS))
        DailymotionCatalog.required(metadata, "id")
    }

    override fun getId(): String = if (::metadata.isInitialized) DailymotionCatalog.required(metadata, "id") else linkHandler.id
    override fun getUrl(): String = DailymotionUrls.channelUrl(getId())
    override fun getName(): String = DailymotionCatalog.text(metadata, "screenname").ifBlank { DailymotionCatalog.required(metadata, "username") }
    override fun getDescription(): String = DailymotionCatalog.text(metadata, "description")
    override fun getAvatarUrl(): String = DailymotionCatalog.image(metadata, "avatar_360_url")
    override fun getBannerUrl(): String = DailymotionCatalog.image(metadata, "cover_250_url")
    override fun getSubscriberCount(): Long = DailymotionCatalog.count(metadata, "followers_total")
    override fun isVerified(): Boolean = metadata["verified"] == true
    override fun getHandle(): String = DailymotionCatalog.text(metadata, "username")
    override fun getJoinedDateText(): String? = DailymotionCatalog.date(metadata)?.offsetDateTime()?.toLocalDate()?.toString()

    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        assertPageFetched()
        return initial ?: DailymotionCatalog.streams(api, serviceId, path, parameters, 1).also { initial = it }
    }

    override fun getPage(page: Page?): InfoItemsPage<StreamInfoItem> {
        return DailymotionCatalog.streams(api, serviceId, path, parameters, DailymotionCatalog.pageNumber(page, DailymotionCatalog.listScope(path, parameters)))
    }

    override fun getTabs(): List<ListLinkHandler> = listOf(ChannelTabs.VIDEOS, ChannelTabs.PLAYLISTS).mapIndexed { index, name ->
        // The host caches channel tabs by URL, so each tab must have its own canonical URL.
        service.channelTabLHFactory.fromQuery(getId(), listOf(FilterItem(index + 1, name)), emptyList())
    }
}
