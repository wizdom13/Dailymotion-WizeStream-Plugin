/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs

class DailymotionUrlsTest {
    private val service = DailymotionService(12345)

    @Test fun recognizesPublicVideoFormsAndCanonicalizesThem() {
        listOf(
            "https://www.dailymotion.com/video/x3rdtfy",
            "http://dailymotion.com/video/x3rdtfy_title?playlist=x1#t=20",
            "https://m.dailymotion.com/video/x3rdtfy",
            "https://www.dailymotion.com/embed/video/x3rdtfy",
            "https://www.dailymotion.com/crawler/video/x3rdtfy",
            "https://dai.ly/x3rdtfy?start=12",
            "https://geo.dailymotion.com/player/xabc.html?video=x3rdtfy",
            "https://geo.dailymotion.com/player.html?video=x3rdtfy"
        ).forEach { url ->
            val handler = service.streamLHFactory.fromUrl(url)
            assertEquals(url, "x3rdtfy", handler.id)
            assertEquals("https://www.dailymotion.com/video/x3rdtfy", handler.url)
        }
    }

    @Test fun refusesForeignOriginsInvalidIdsAndAmbiguousPlayerParameters() {
        listOf(
            "https://www.dailymotion.com.evil.invalid/video/x3rdtfy",
            "https://evil.invalid/?url=https://www.dailymotion.com/video/x3rdtfy",
            "https://www.dailymotion.com@evil.invalid/video/x3rdtfy",
            "https://user:password@www.dailymotion.com/video/x3rdtfy",
            "file://www.dailymotion.com/video/x3rdtfy",
            "https://www.dailymotion.com:8443/video/x3rdtfy",
            "https://www.dailymotion.com/video/x3rdtfy/extra",
            "https://www.dailymotion.com/video/..",
            "https://www.dailymotion.com/video/x3%2Frdtfy",
            "https://geo.dailymotion.com/player.html?video=x3rdtfy&video=xevil"
        ).forEach { assertFalse(it, service.streamLHFactory.acceptUrl(it)) }
        assertThrows(ParsingException::class.java) { service.streamLHFactory.fromId("../video/x") }
    }

    @Test fun routesChannelsPlaylistsAndTabsWithoutOverlappingVideoRoutes() {
        assertEquals("euronews", service.channelLHFactory.fromUrl("https://www.dailymotion.com/euronews/videos").id)
        assertEquals("euronews", service.channelLHFactory.fromUrl("https://www.dailymotion.com/user/euronews").id)
        assertEquals("x8abc", service.playlistLHFactory.fromUrl("https://www.dailymotion.com/playlist/x8abc_title").id)
        listOf("video", "playlist", "search", "trending", "login", "settings").forEach {
            assertFalse(service.channelLHFactory.acceptUrl("https://www.dailymotion.com/$it"))
        }
        assertFalse(service.channelLHFactory.acceptUrl("https://www.dailymotion.com/video/x3rdtfy"))
        assertEquals(ChannelTabs.VIDEOS, service.channelTabLHFactory.fromId("euronews").contentFilters.single().name)
        assertEquals(ChannelTabs.VIDEOS, service.channelTabLHFactory.fromQuery("euronews", emptyList(), null).contentFilters.single().name)
        assertEquals(ChannelTabs.PLAYLISTS, service.channelTabLHFactory.fromUrl("https://www.dailymotion.com/euronews/playlists").contentFilters.single().name)
    }

    @Test fun searchEncodesArbitraryUnicodeAndOffersOnlySupportedFilters() {
        val factory = service.searchQHFactory
        val query = "طبيعة / café & birds + sea"
        val handler = factory.fromQuery(query)
        assertEquals(query, handler.searchString)
        assertEquals(query, factory.getSearchString(handler.url))
        assertTrue(handler.url.contains("%2F"))
        assertEquals(listOf("videos", "channels"), factory.availableContentFilter.filterGroups.flatMap { it.filterItems.toList() }.map { it.name })
        assertTrue(factory.getContentFilterSortFilterVariant(2).filterGroups.isEmpty())
        assertThrows(ParsingException::class.java) { factory.fromQuery("   ") }
        assertEquals(12345, service.serviceId)
        assertNull(service.commentsLHFactory)
        assertEquals("Trending", service.kioskList.defaultKioskId)
    }
}
