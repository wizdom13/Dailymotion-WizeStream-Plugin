/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor

internal class DailymotionChannelItemExtractor(private val item: JsonObject) : ChannelInfoItemExtractor {
    override fun getName(): String = DailymotionCatalog.text(item, "screenname").ifBlank { DailymotionCatalog.required(item, "username") }
    override fun getUrl(): String = DailymotionUrls.channelUrl(DailymotionCatalog.required(item, "id"))
    override fun getUploaderId(): String = DailymotionCatalog.required(item, "id")
    override fun getThumbnailUrl(): String = DailymotionCatalog.image(item, "avatar_360_url")
    override fun getThumbnails(): List<Image> = getThumbnailUrl().takeIf(String::isNotEmpty)?.let {
        listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.MEDIUM))
    }.orEmpty()
    override fun getDescription(): String = DailymotionCatalog.text(item, "description")
    override fun getSubscriberCount(): Long = DailymotionCatalog.count(item, "followers_total")
    override fun getStreamCount(): Long = DailymotionCatalog.count(item, "videos_total")
    override fun isVerified(): Boolean = item["verified"] == true
}
