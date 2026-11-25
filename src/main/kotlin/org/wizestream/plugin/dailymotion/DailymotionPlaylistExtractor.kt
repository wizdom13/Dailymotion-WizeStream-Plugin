/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class DailymotionPlaylistExtractor(service: StreamingService, handler: ListLinkHandler) : PlaylistExtractor(service, handler) {
    private lateinit var metadata: JsonObject
    private var initial: InfoItemsPage<StreamInfoItem>? = null
    private val api get() = DailymotionApi(downloader, extractorLocalization)
    private val path = "/playlist/${handler.id}/videos"

    override fun onFetchPage(downloader: Downloader) {
        metadata = api.get("/playlist/${linkHandler.id}", mapOf("fields" to DailymotionCatalog.PLAYLIST_FIELDS))
        DailymotionCatalog.required(metadata, "id")
    }

    override fun getName(): String = DailymotionCatalog.required(metadata, "name")
    override fun getThumbnailUrl(): String = DailymotionCatalog.image(metadata, "thumbnail_720_url")
    override fun getUploaderName(): String = DailymotionCatalog.ownerName(metadata)
    override fun getUploaderUrl(): String = DailymotionCatalog.ownerUrl(metadata)
    override fun getUploaderAvatarUrl(): String = DailymotionCatalog.image(metadata, "owner.avatar_360_url")
    override fun isUploaderVerified(): Boolean = false
    override fun getStreamCount(): Long = DailymotionCatalog.count(metadata, "videos_total")

    override fun getInitialPage(): InfoItemsPage<StreamInfoItem> {
        assertPageFetched()
        return initial ?: DailymotionCatalog.streams(api, serviceId, path, emptyMap(), 1).also { initial = it }
    }

    override fun getPage(page: Page?): InfoItemsPage<StreamInfoItem> = DailymotionCatalog.streams(
        api, serviceId, path, emptyMap(), DailymotionCatalog.pageNumber(page, DailymotionCatalog.listScope(path, emptyMap()))
    )
}
