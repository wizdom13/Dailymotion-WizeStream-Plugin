/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.io.IOException
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class DailymotionStreamExtractorTest {
    private lateinit var network: FixtureDownloader
    private lateinit var service: DailymotionService

    @Before
    fun setUp() {
        network = FixtureDownloader()
        NewPipe.init(network)
        service = DailymotionService(1000)
    }

    @Test
    fun `host stream info aggregation preserves identity sources and subtitles without errors`() {
        val url = "https://www.dailymotion.com/video/x9fixture"
        val info = StreamInfo.getInfo(service, url)

        assertTrue("Unexpected host extraction errors: ${info.errors}", info.errors.isEmpty())
        assertEquals(1000, info.serviceId)
        assertEquals("x9fixture", info.id)
        assertEquals(url, info.url)
        assertEquals("Public fixture video", info.name)
        assertEquals("Fixture owner", info.uploaderName)
        assertEquals(87L, info.duration)
        assertEquals(StreamType.VIDEO_STREAM, info.streamType)
        assertEquals(4, info.videoStreams.size)
        assertEquals(
            setOf(DeliveryMethod.PROGRESSIVE_HTTP, DeliveryMethod.HLS, DeliveryMethod.DASH),
            info.videoStreams.map { it.deliveryMethod }.toSet()
        )
        assertTrue(info.videoStreams.all { it.cacheable == false })
        assertEquals(3, info.subtitles.size)
        assertEquals(setOf("en", "fr"), info.subtitles.map { it.languageTag }.toSet())
        assertEquals(
            setOf(MediaFormat.VTT, MediaFormat.SRT, MediaFormat.TTML),
            info.subtitles.map { it.format }.toSet()
        )
    }

    @Test
    fun `extracts public metadata with host assigned service identity`() {
        val stream = extract()
        assertEquals(1000, stream.serviceId)
        assertEquals("Public fixture video", stream.name)
        assertEquals(87L, stream.length)
        assertEquals("Fixture owner", stream.uploaderName)
        assertEquals("https://www.dailymotion.com/xfixtureowner", stream.uploaderUrl)
        assertEquals("https://s1.dmcdn.net/fixture/avatar-360.png", stream.uploaderAvatarUrl)
        assertEquals("https://s1.dmcdn.net/fixture/x720", stream.thumbnailUrl)
        assertEquals(68613L, stream.viewCount)
        assertEquals(24L, stream.likeCount)
        assertEquals(1455123397L, stream.uploadDate!!.offsetDateTime().toEpochSecond())
        assertEquals(StreamType.VIDEO_STREAM, stream.streamType)
        assertEquals(Privacy.PUBLIC, stream.privacy)
        assertEquals(0, stream.ageLimit)
        assertTrue(stream.isUploaderVerified)
        assertFalse(stream.isSupportComments)
        assertFalse(stream.isSupportBulletComments)
        assertTrue(stream.isSupportRelatedItems)
        assertEquals(listOf("nature", "travel"), stream.tags)
    }

    @Test
    fun `keeps advertised MP4 HLS and DASH with headers and expiring source policy`() {
        val stream = extract()
        val videos = stream.videoStreams
        assertEquals(4, videos.size)
        assertEquals(2, videos.count { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP })
        assertEquals(1, videos.count { it.deliveryMethod == DeliveryMethod.HLS })
        assertEquals(1, videos.count { it.deliveryMethod == DeliveryMethod.DASH })
        assertEquals("1080p", videos.first { it.content.contains("1920x1080") }.resolution)
        for (video in videos) {
            assertEquals(false, video.cacheable)
            assertEquals(MediaFormat.MPEG_4, video.format)
            assertFalse(video.isVideoOnly)
            assertEquals("https://www.dailymotion.com/video/x9fixture", video.requestHeaders!!["Referer"])
            assertEquals("https://www.dailymotion.com", video.requestHeaders!!["Origin"])
        }
        assertTrue(stream.hlsUrl.endsWith("master.m3u8?signature=fixture"))
        assertTrue(stream.dashMpdUrl.endsWith("manifest.mpd?signature=fixture"))
        assertEquals(videos.first().requestHeaders, stream.manifestRequestHeaders)
        assertTrue(stream.videoOnlyStreams.isEmpty())
        assertTrue(stream.audioStreams.isEmpty())
    }

    @Test
    fun `does not return duplicate or proprietary source formats`() {
        val qualities = network.metadata.getObject("qualities")
        qualities["extra"] = jsonArray(
            """{"type":"application/x-mpegURL","url":"https://cdndirector.dailymotion.com/fixture/master.m3u8?signature=fixture"}""",
            """{"type":"application/vnd.lumberjack.manifest","url":"https://cdndirector.dailymotion.com/fixture/custom.json"}"""
        )
        assertEquals(4, extract().videoStreams.size)
    }

    @Test
    fun `rejects unsafe source URLs and DRM marked alternatives`() {
        network.metadata["qualities"] = jsonObject("""{"auto":[
            {"type":"video/mp4","url":"file:///sdcard/video.mp4"},
            {"type":"video/mp4","url":"http://s1.dmcdn.net/video.mp4"},
            {"type":"video/mp4","url":"https://user:secret@s1.dmcdn.net/video.mp4"},
            {"type":"video/mp4","url":"https://s1.dmcdn.net:8080/video.mp4"},
            {"type":"video/mp4","url":"https://s1.dmcdn.net/video.mp4","drm":{"widevine":true}}
        ]}""")
        assertThrows(ContentNotAvailableException::class.java) { extract() }
    }

    @Test
    fun `does not invent sources for an empty or unsupported player response`() {
        network.metadata["qualities"] = JsonObject()
        assertThrows(ContentNotAvailableException::class.java) { extract() }
        network.metadata["qualities"] = jsonObject("""{"auto":[{"type":"application/vnd.lumberjack.manifest","url":"https://s1.dmcdn.net/manifest.json"}]}""")
        assertThrows(ContentNotAvailableException::class.java) { extract() }
    }

    @Test
    fun `exposes subtitles by actual format with language and request metadata`() {
        val stream = extract()
        assertEquals(3, stream.subtitlesDefault.size)
        for (format in listOf(MediaFormat.VTT, MediaFormat.SRT, MediaFormat.TTML)) {
            val subtitles = stream.getSubtitles(format)
            assertEquals(1, subtitles.size)
            assertEquals(format, subtitles.single().format)
            assertEquals(false, subtitles.single().cacheable)
            assertNotNull(subtitles.single().requestHeaders)
        }
        assertEquals("en", stream.getSubtitles(MediaFormat.VTT).single().languageTag)
        assertTrue(stream.getSubtitles(MediaFormat.TTML).single().isAutoGenerated)
        assertTrue(stream.getSubtitles(MediaFormat.MPEG_4).isEmpty())
    }

    @Test
    fun `handles no subtitles array and ignores malformed tracks`() {
        network.metadata["subtitles"] = jsonObject("""{"enable":false,"data":[]}""")
        assertTrue(extract().subtitlesDefault.isEmpty())
        network.metadata["subtitles"] = jsonObject("""{"data":{
            "../en":{"urls":["https://s1.dmcdn.net/sub.vtt"]},
            "en":{"urls":["javascript:alert(1)","https://user@s1.dmcdn.net/sub.vtt","https://s1.dmcdn.net/sub.txt"]},
            "fr":false
        }}""")
        assertTrue(extract().subtitlesDefault.isEmpty())
    }

    @Test
    fun `rejects password and DRM gates without fallback playback`() {
        network.metadata["is_password_protected"] = true
        assertThrows(PrivateContentException::class.java) { extract() }
        network.metadata["is_password_protected"] = false
        network.metadata["drm"] = jsonObject("""{"widevine":{"license_url":"https://license.example/"}}""")
        assertThrows(ContentNotSupportedException::class.java) { extract() }
    }

    @Test
    fun `honors errors embedded in HTTP 200 metadata`() {
        network.metadata["error"] = jsonObject("""{"code":"404","type":"not_found","message":"Video not found"}""")
        assertThrows(ContentNotAvailableException::class.java) { extract() }
        network.metadata["error"] = jsonObject("""{"code":"DM007","message":"Video is blocked in your country"}""")
        assertThrows(GeographicRestrictionException::class.java) { extract() }
        network.metadata["error"] = jsonObject("""{"code":"403","message":"Private video requires authentication"}""")
        assertThrows(PrivateContentException::class.java) { extract() }
    }

    @Test
    fun `requires matching identity and title before returning streams`() {
        network.metadata["id"] = "xother"
        assertThrows(ParsingException::class.java) { extract() }
        network.metadata["id"] = "x9fixture"
        network.metadata["title"] = ""
        assertThrows(ParsingException::class.java) { extract() }
    }

    @Test
    fun `reports live broadcasts and recorded archives correctly`() {
        network.metadata["mode"] = "live"
        network.metadata["stream_type"] = "live"
        val live = extract()
        assertEquals(StreamType.LIVE_STREAM, live.streamType)
        assertEquals(0L, live.length)
        assertEquals(42L, live.viewCount)
        assertNull(live.uploadDate)
        assertNull(live.textualUploadDate)
        network.metadata["stream_type"] = "recorded"
        val archive = extract()
        assertEquals(StreamType.VIDEO_STREAM, archive.streamType)
        assertEquals(87L, archive.length)
        assertNotNull(archive.uploadDate)
        network.metadata["stream_type"] = "live"
        network.metadata["onair"] = false
        assertThrows(ContentNotAvailableException::class.java) { extract() }
    }

    @Test
    fun `optional statistics failure does not discard playable sources`() {
        network.statisticsStatus = 503
        val stream = extract()
        assertEquals(4, stream.videoStreams.size)
        assertEquals(-1L, stream.viewCount)
        assertEquals(-1L, stream.likeCount)
    }

    @Test
    fun `preserves explicit age and private share link metadata`() {
        network.metadata["explicit"] = true
        network.metadata["private"] = true
        val stream = extract("https://www.dailymotion.com/video/kSharedLink")
        assertEquals(18, stream.ageLimit)
        assertEquals(Privacy.UNLISTED, stream.privacy)
        assertEquals("https://www.dailymotion.com/video/kSharedLink", stream.videoStreams.first().requestHeaders!!["Referer"])
    }

    @Test
    fun `related items preserve service identity and omit self and duplicates`() {
        network.related = """{"page":1,"has_more":false,"list":[
            {"id":"x9fixture","title":"Current"},
            {"id":"xrelated","title":"Related","mode":"vod","owner.id":"xowner","owner.screenname":"Owner","duration":12},
            {"id":"xrelated","title":"Duplicate"}
        ]}"""
        val items = extract().relatedItems.items
        assertEquals(1, items.size)
        assertEquals(1000, items.single().serviceId)
        assertEquals("https://www.dailymotion.com/video/xrelated", items.single().url)
    }

    @Test
    fun `timestamp parsing is bounded and rejects ambiguous values`() {
        assertEquals(62L, extract("https://dai.ly/x9fixture?start=62").timeStamp)
        assertEquals(9L, extract("https://www.dailymotion.com/video/x9fixture#t=9").timeStamp)
        for (suffix in listOf("?start=-1", "?start=9223372036854775807", "?start=1&t=2", "?start=oops")) {
            assertEquals(0L, extract("https://www.dailymotion.com/video/x9fixture$suffix").timeStamp)
        }
    }

    private fun extract(url: String = "https://www.dailymotion.com/video/x9fixture"): DailymotionStreamExtractor =
        DailymotionStreamExtractor(service, service.streamLHFactory.fromUrl(url)).apply { fetchPage() }

    private class FixtureDownloader : Downloader() {
        val metadata: JsonObject = jsonObject(requireNotNull(javaClass.getResource("/dailymotion/player-metadata.json")).readText())
        var statisticsStatus = 200
        var related = """{"page":1,"has_more":false,"list":[]}"""

        override fun execute(request: Request): Response {
            val uri = URI(request.url())
            val (body, status) = when {
                uri.host == "www.dailymotion.com" && uri.path.startsWith("/player/metadata/video/") -> JsonWriter.string(metadata) to 200
                uri.host == "api.dailymotion.com" && uri.path.endsWith("/related") -> related to 200
                uri.host == "api.dailymotion.com" && uri.path == "/video/x9fixture" ->
                    if (statisticsStatus == 200) """{"id":"x9fixture","views_total":68613,"likes_total":24,"audience":42,"owner.verified":true}""" to 200
                    else """{"error":{"message":"Temporarily unavailable"}}""" to statisticsStatus
                else -> throw IOException("Unexpected fixture request: ${request.url()}")
            }
            return Response(status, "fixture", emptyMap(), body, body.toByteArray(), request.url())
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw UnsupportedOperationException("Fixture requests are synchronous")
    }

    companion object {
        private fun jsonObject(value: String): JsonObject = JsonParser.`object`().from(value)
        private fun jsonArray(vararg values: String): JsonArray = JsonArray().apply { values.forEach { add(jsonObject(it)) } }
    }
}
