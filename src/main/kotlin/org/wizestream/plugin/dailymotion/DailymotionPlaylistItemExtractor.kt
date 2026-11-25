/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor

internal class DailymotionPlaylistItemExtractor(private val item: JsonObject) : PlaylistInfoItemExtractor {
    override fun getName(): String = DailymotionCatalog.required(item, "name")
    override fun getUrl(): String = DailymotionUrls.playlistUrl(DailymotionCatalog.required(item, "id"))
    override fun getThumbnailUrl(): String = DailymotionCatalog.image(item, "thumbnail_720_url")
    override fun getThumbnails(): List<Image> = getThumbnailUrl().takeIf(String::isNotEmpty)?.let {
        listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.HIGH))
    }.orEmpty()
    override fun getUploaderName(): String = DailymotionCatalog.ownerName(item)
    override fun getUploaderId(): String = DailymotionCatalog.ownerId(item)
    override fun getUploaderUrl(): String = DailymotionCatalog.ownerUrl(item)
    override fun getStreamCount(): Long = DailymotionCatalog.count(item, "videos_total")
    override fun getPlaylistType(): PlaylistInfo.PlaylistType = PlaylistInfo.PlaylistType.NORMAL
}
