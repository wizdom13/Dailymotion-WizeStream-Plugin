/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import com.grack.nanojson.JsonParser
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItemExtractor
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamType

class DailymotionCatalogTest {
    private val service = DailymotionService(1_234_567)
    private lateinit var downloader: CatalogFixtureDownloader

    @Before
    fun setUp() {
        downloader = CatalogFixtureDownloader()
        NewPipe.init(downloader, Localization("en", "US"))
    }

    @Test
    fun videoSearchPreservesUnicodeQueryAndFetchesNextPageOnFreshExtractor() {
        downloader.respond("/videos", "1", fixture("videos-page-1.json"))
        downloader.respond("/videos", "2", fixture("videos-page-2.json"))
        val handler = search("أرز & crème", sort = "recent")
        val extractor = DailymotionSearchExtractor(service, handler)
        extractor.fetchPage()
        val first = extractor.getInitialPage()
        assertEquals(2, first.items.size)
        assertTrue(first.errors.toString(), first.errors.isEmpty())
        assertEquals("أرز & crème", downloader.requests.first().parameters["search"])
        assertEquals("recent", downloader.requests.first().parameters["sort"])
        val video = first.items.first() as StreamInfoItem
        assertEquals("https://www.dailymotion.com/video/x9fixture", video.url)
        assertEquals("https://www.dailymotion.com/xfixtureuser", video.uploaderUrl)
        assertEquals(125L, video.duration)
        assertEquals(321L, video.viewCount)
        assertEquals(1_700_000_000L, requireNotNull(video.uploadDate).offsetDateTime().toEpochSecond())
        assertEquals(service.serviceId, video.serviceId)
        val second = DailymotionSearchExtractor(service, handler).getPage(first.nextPage)
        assertEquals(1, second.items.size)
        assertFalse(second.hasNextPage())
        assertEquals(listOf("1", "2"), downloader.requests.map { it.parameters["page"] })
    }

    @Test
    fun channelSearchDoesNotForwardRetainedVideoSort() {
        downloader.respond("/users", "1", fixture("users.json"))
        val extractor = DailymotionSearchExtractor(service, search("fixture", "channels", "visited"))
        extractor.fetchPage()
        val item = extractor.getInitialPage().items.single() as ChannelInfoItem
        assertEquals("Fixture creator", item.name)
        assertEquals(42L, item.subscriberCount)
        assertEquals(7L, item.streamCount)
        assertFalse(downloader.requests.single().parameters.containsKey("sort"))
    }

    @Test
    fun channelMetadataCanonicalizesAliasAndExposesVideosAndPlaylists() {
        downloader.respond("/user/fixture-channel", null, fixture("user.json"))
        downloader.respond("/user/xfixtureuser/videos", "1", fixture("videos-page-1.json"))
        downloader.respond("/user/xfixtureuser/videos", "2", fixture("videos-page-2.json"))
        val extractor = DailymotionChannelExtractor(service, channel("fixture-channel"))
        extractor.fetchPage()
        assertEquals("xfixtureuser", extractor.getId())
        assertEquals("https://www.dailymotion.com/xfixtureuser", extractor.getUrl())
        assertEquals("fixture-channel", extractor.getHandle())
        assertEquals("Fixture creator", extractor.getName())
        assertEquals("Public channel description", extractor.getDescription())
        assertEquals("https://s1.dmcdn.net/avatar/test.png", extractor.getAvatarUrl())
        assertEquals("2020-09-13", extractor.getJoinedDateText())
        val tabs = extractor.getTabs()
        assertEquals(listOf(ChannelTabs.VIDEOS, ChannelTabs.PLAYLISTS), tabs.map { it.contentFilters.single().name })
        assertEquals(listOf("https://www.dailymotion.com/xfixtureuser/videos", "https://www.dailymotion.com/xfixtureuser/playlists"), tabs.map { it.url })
        val first = extractor.getInitialPage()
        assertSame(first, extractor.getInitialPage())
        assertEquals("recent", downloader.requests.last().parameters["sort"])
        val restored = DailymotionChannelExtractor(service, channel("xfixtureuser"))
        assertEquals(1, restored.getPage(first.nextPage).items.size)
        assertEquals(3, downloader.requests.size)
    }

    @Test
    fun channelPlaylistTabUsesPluralCountAndHandlesMissingThumbnail() {
        downloader.respond("/user/xfixtureuser/playlists", "1", fixture("playlists.json"))
        val extractor = DailymotionChannelTabExtractor(service, channel("xfixtureuser", ChannelTabs.PLAYLISTS))
        extractor.fetchPage()
        val page = extractor.getInitialPage()
        val playlist = page.items.single() as PlaylistInfoItem
        assertEquals("https://www.dailymotion.com/playlist/xfixture", playlist.url)
        assertEquals(3L, playlist.streamCount)
        assertEquals("", playlist.thumbnailUrl)
        assertFalse(page.hasNextPage())
        assertTrue(page.errors.toString(), page.errors.isEmpty())
    }

    @Test
    fun playlistMetadataAndPaginationPreserveThePublishedOrder() {
        downloader.respond("/playlist/xfixture", null, fixture("playlist.json"))
        downloader.respond("/playlist/xfixture/videos", "1", fixture("videos-page-1.json"))
        downloader.respond("/playlist/xfixture/videos", "2", fixture("videos-page-2.json"))
        val url = DailymotionUrls.playlistUrl("xfixture")
        val handler = ListLinkHandler(url, url, "xfixture", emptyList(), emptyList())
        val extractor = DailymotionPlaylistExtractor(service, handler)
        extractor.fetchPage()
        assertEquals("Fixture playlist", extractor.getName())
        assertEquals(3L, extractor.getStreamCount())
        assertEquals("https://www.dailymotion.com/xfixtureuser", extractor.getUploaderUrl())
        val first = extractor.getInitialPage()
        assertEquals(listOf("First public video", "Second public video"), first.items.map { it.name })
        val last = DailymotionPlaylistExtractor(service, handler).getPage(first.nextPage)
        assertEquals("Third public video", last.items.single().name)
        assertTrue(downloader.requests.none { it.parameters.containsKey("sort") })
    }

    @Test
    fun emptyCatalogIsAValidFinalPage() {
        downloader.respond("/videos", "1", """{"page":1,"has_more":false,"list":[]}""")
        val extractor = DailymotionSearchExtractor(service, search("no results"))
        extractor.fetchPage()
        val page = extractor.getInitialPage()
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNextPage())
        assertTrue(page.errors.isEmpty())
        assertEquals(1, downloader.requests.size)
    }

    @Test
    fun trendingUsesItsOwnSortAndRecognizesLiveItemsWithoutInventingUpcomingState() {
        downloader.respond("/videos", "1", """{"page":1,"has_more":false,"list":[{"id":"x3live","title":"Live news","mode":"live","onair":false,"duration":0,"thumbnail_720_url":null,"owner.id":"xfixtureuser","owner.screenname":"Fixture creator"}]}""")
        val handler = ListLinkHandler("https://www.dailymotion.com/trending", "https://www.dailymotion.com/trending", "Trending", emptyList(), emptyList())
        val extractor = DailymotionKioskExtractor(service, handler, "Trending")
        extractor.fetchPage()
        val live = extractor.getInitialPage().items.single()
        assertEquals(StreamType.LIVE_STREAM, live.streamType)
        assertEquals(0L, live.duration)
        assertNull(live.uploadDate)
        assertNull(live.upcomingStatus)
        assertEquals("trending", downloader.requests.single().parameters["sort"])
    }

    @Test
    fun malformedVideoDoesNotDiscardHealthySiblings() {
        downloader.respond("/videos", "1", """{"page":1,"has_more":false,"list":[{"id":"xmissingtitle"},{"id":"x9fixture","title":"Valid item","mode":"vod","duration":12}]}""")
        val extractor = DailymotionSearchExtractor(service, search("fixture"))
        extractor.fetchPage()
        val page = extractor.getInitialPage()
        assertEquals("Valid item", page.items.single().name)
        assertFalse(page.errors.isEmpty())
    }

    @Test
    fun paginationRejectsExternalUrlsUnrelatedQueriesAndUnboundedCountersBeforeNetwork() {
        val extractor = DailymotionSearchExtractor(service, search("fixture"))
        val path = "/videos"
        val validScope = DailymotionCatalog.listScope(path, mapOf("search" to "fixture", "sort" to "relevance"))
        for (page in listOf(Page("https://example.invalid/steal", "2"), Page(validScope, "1001"), Page(validScope, "-1"), Page(validScope, "999999999999999999999"), Page(validScope.replace("fixture", "different"), "2"))) {
            assertThrows(ParsingException::class.java) { extractor.getPage(page) }
        }
        assertTrue(downloader.requests.isEmpty())
    }

    @Test
    fun unsupportedSearchAndTabsFailWithoutNetwork() {
        assertThrows(ContentNotSupportedException::class.java) { DailymotionSearchExtractor(service, search("fixture", "playlists")) }
        assertThrows(ContentNotSupportedException::class.java) { DailymotionChannelTabExtractor(service, channel("xfixtureuser", "unsupported")) }
        assertTrue(downloader.requests.isEmpty())
    }

    @Test
    fun itemMetadataDefaultsPreserveHostSemantics() {
        val videos = JsonParser.`object`().from(fixture("videos-page-1.json")).getArray("list")
        val stream: StreamInfoItemExtractor = DailymotionStreamItemExtractor(videos.getObject(0))
        assertNull(stream.getAlbumUrl())
        assertNull(stream.getUpcomingStatus())
        assertFalse(stream.isAd())
        assertNull(stream.getShortDescription())
        assertFalse(stream.isRoundPlayStream())
        assertFalse(stream.isShortFormContent())
        assertFalse(stream.requiresMembership())
        assertEquals(ContentAvailability.UNKNOWN, stream.getContentAvailability())
        assertEquals(stream.getThumbnailUrl(), stream.getThumbnails().single().url)
        val streamWithoutThumbnail: StreamInfoItemExtractor = DailymotionStreamItemExtractor(videos.getObject(1))
        assertTrue(streamWithoutThumbnail.getThumbnails().isEmpty())

        val channel: ChannelInfoItemExtractor = DailymotionChannelItemExtractor(JsonParser.`object`().from(fixture("user.json")))
        assertEquals("xfixtureuser", channel.getUploaderId())
        assertEquals(channel.getThumbnailUrl(), channel.getThumbnails().single().url)

        val playlist: PlaylistInfoItemExtractor = DailymotionPlaylistItemExtractor(JsonParser.`object`().from(fixture("playlist.json")))
        assertEquals("xfixtureuser", playlist.getUploaderId())
        assertEquals("https://www.dailymotion.com/xfixtureuser", playlist.getUploaderUrl())
        assertEquals(PlaylistInfo.PlaylistType.NORMAL, playlist.getPlaylistType())
        assertTrue(playlist.getThumbnails().isEmpty())
        assertTrue(downloader.requests.isEmpty())
    }

    private fun search(query: String, kind: String = "videos", sort: String = "relevance"): SearchQueryHandler =
        SearchQueryHandler("https://www.dailymotion.com/search", "https://www.dailymotion.com/search", query, listOf(FilterItem(1, kind)), listOf(FilterItem(10, sort)))

    private fun channel(id: String, tab: String? = null): ListLinkHandler {
        val url = DailymotionUrls.channelUrl(id)
        return ListLinkHandler(url, url, id, tab?.let { listOf(FilterItem(1, it)) }.orEmpty(), emptyList())
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/catalog/$name")).bufferedReader().use { it.readText() }
}

private class CatalogFixtureDownloader : Downloader() {
    data class SeenRequest(val path: String, val parameters: Map<String, String>)
    val requests = mutableListOf<SeenRequest>()
    private val responses = mutableMapOf<Pair<String, String?>, String>()

    fun respond(path: String, page: String?, body: String) {
        responses[path to page] = body
    }

    override fun execute(request: Request): Response {
        val uri = URI(request.url())
        assertEquals("https", uri.scheme)
        assertEquals("api.dailymotion.com", uri.host)
        assertEquals("GET", request.httpMethod())
        val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        requests += SeenRequest(uri.path, parameters)
        val body = responses[uri.path to parameters["page"]] ?: throw AssertionError("Unexpected network request: ${request.url()}")
        return Response(200, "OK", mapOf("Content-Type" to listOf("application/json")), body, body.toByteArray(), request.url())
    }

    override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall = throw AssertionError("Unexpected asynchronous request")
}
