/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamType

class DailymotionStreamItemExtractor(private val item: JsonObject) : StreamInfoItemExtractor {
    override fun getName(): String = DailymotionCatalog.required(item, "title")
    override fun getUrl(): String = DailymotionUrls.videoUrl(DailymotionCatalog.required(item, "id"))
    override fun getThumbnailUrl(): String = DailymotionCatalog.image(item, "thumbnail_720_url")
    override fun getThumbnails(): List<Image> = getThumbnailUrl().takeIf(String::isNotEmpty)?.let {
        listOf(Image(it, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.HIGH))
    }.orEmpty()
    override fun getStreamType(): StreamType = if (DailymotionCatalog.text(item, "mode") == "live") StreamType.LIVE_STREAM else StreamType.VIDEO_STREAM
    override fun getDuration(): Long = if (getStreamType() == StreamType.LIVE_STREAM) 0 else DailymotionCatalog.count(item, "duration")
    override fun getViewCount(): Long = if (getStreamType() == StreamType.LIVE_STREAM) -1 else DailymotionCatalog.count(item, "views_total")
    override fun getUploaderName(): String = DailymotionCatalog.ownerName(item)
    override fun getUploaderId(): String = DailymotionCatalog.ownerId(item)
    override fun getUploaderUrl(): String = DailymotionCatalog.ownerUrl(item)
    override fun getUploaderAvatarUrl(): String = DailymotionCatalog.image(item, "owner.avatar_360_url")
    override fun isUploaderVerified(): Boolean = item["owner.verified"] == true
    override fun getTextualUploadDate(): String? = getUploadDate()?.offsetDateTime()?.toString()
    override fun getUploadDate(): DateWrapper? = if (getStreamType() == StreamType.LIVE_STREAM) null else DailymotionCatalog.date(item)

    // Keep host interface defaults concrete: the plugin DEX cannot use host desugaring companions.
    override fun getAlbumUrl(): String? = null
    override fun getUpcomingStatus(): Boolean? = null
    override fun isAd(): Boolean = false
    override fun getShortDescription(): String? = null
    override fun isRoundPlayStream(): Boolean = false
    override fun isShortFormContent(): Boolean = false
    override fun requiresMembership(): Boolean = false
    override fun getContentAvailability(): ContentAvailability = ContentAvailability.UNKNOWN
}
