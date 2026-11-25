/*
 * SPDX-FileCopyrightText: 2026 WizeStream contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.ChannelTabInfo
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.plugin.PluginContext
import org.schabi.newpipe.extractor.plugin.PluginException
import org.schabi.newpipe.extractor.plugin.PluginLoader
import org.schabi.newpipe.extractor.plugin.PluginManifest
import org.schabi.newpipe.extractor.plugin.PluginPackage
import org.schabi.newpipe.extractor.plugin.PluginPreferences
import org.schabi.newpipe.extractor.plugin.PluginRepository
import org.schabi.newpipe.extractor.plugin.WizeStreamPluginContext
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Copied into an isolated, pinned host checkout by CI. Production plugin classes are
 * loaded only from the signed .ppp DEX; no plugin sources enter the host/test APK.
 * Every extractor request is answered locally. No Dailymotion account or network is used.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24)
class DailymotionPackageCompatibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val assets get() = instrumentation.context.assets
    private lateinit var previousDownloader: Downloader
    private lateinit var directory: File
    private lateinit var service: StreamingService
    private lateinit var manifest: PluginManifest
    private lateinit var loader: DexClassLoader
    private val offline = OfflineDownloader()
    private val preferences = TestPreferences()
    private var initialized = false
    private var registered = false

    @Before
    fun loadVerifiedNativePackage() {
        previousDownloader = NewPipe.getDownloader()
        initialized = true
        assertNull(ServiceList.getById(SERVICE_ID))
        val repository = "https://example.invalid/dailymotion-ci/index.json"
        val index = assets.open("dailymotion-package/index.json").bufferedReader().use { it.readText() }
        val entry = PluginRepository.parse(repository, index).plugins.single()
        assertEquals("org.wizestream.plugin.dailymotion", entry.pluginId)
        val packageName = URI(entry.packageUrl).path.substringAfterLast('/')
        val bytes = assets.open("dailymotion-package/$packageName").use { it.readBytes() }
        directory = File(context.codeCacheDir, "dailymotion-ci-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "dailymotion.ppp")
        FileOutputStream(file).use { output ->
            assertTrue(file.setReadOnly())
            output.write(bytes)
        }
        PluginPackage.verify(file, entry.sha256, entry.signature, entry.publicKey)
        manifest = PluginPackage.readManifest(file)
        assertTrue(PluginPackage.hasDex(file))
        loader = DexClassLoader(file.absolutePath, directory.absolutePath, null, PluginLoader::class.java.classLoader)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val nativeContext = object : WizeStreamPluginContext {
            override fun getPluginId(): String = manifest.pluginId
            override fun getPreferences(): PluginPreferences = preferences
            @Suppress("DEPRECATION")
            override fun getHostVersionCode(): Int = info.versionCode
            override fun getHostVersionName(): String = info.versionName.orEmpty()
            override fun getProfileId(): String = "e2f8d41c-0369-4eb2-8f84-b363f15dac1f"
        }
        service = PluginLoader.createService(loader, manifest, SERVICE_ID, nativeContext)
        ServiceList.register(service)
        registered = true
        NewPipe.init(offline, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry())
    }

    @After
    fun restoreHost() {
        if (!initialized) return
        if (registered) ServiceList.unregister(SERVICE_ID)
        NewPipe.init(previousDownloader, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry())
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun nativeApiPackageLoadsFromDexAndRejectsLegacyContext() {
        assertEquals("wizestream", manifest.host)
        assertEquals("2.0", manifest.apiVersion)
        assertEquals(1052000, manifest.minHostVersionCode)
        assertEquals(24, manifest.minAndroidSdk)
        assertEquals(SERVICE_ID, service.serviceId)
        assertEquals("Dailymotion", service.serviceInfo.name)
        assertEquals("https://www.dailymotion.com", service.baseUrl)
        assertTrue(service.javaClass.classLoader is DexClassLoader)
        assertTrue(service.streamLHFactory.acceptUrl("https://dai.ly/x9fixture"))
        assertFalse(service.streamLHFactory.acceptUrl("https://www.dailymotion.com.evil.invalid/video/x9fixture"))
        val legacyContext = object : PluginContext {
            override fun getPluginId(): String = manifest.pluginId
            override fun getPreferences(): PluginPreferences = preferences
        }
        assertThrows(PluginException::class.java) {
            PluginLoader.createService(loader, manifest, SERVICE_ID, legacyContext)
        }
        assertTrue(offline.requests.isEmpty())
    }

    @Test
    fun searchAndPlaybackUseSharedHostTypesAndOfflineResponses() {
        val query = service.searchQHFactory.fromQuery("fixture video", emptyList(), emptyList())
        val search = SearchInfo.getInfo(service, query)
        assertTrue(search.errors.toString(), search.errors.isEmpty())
        assertEquals("Fixture public video", search.relatedItems.single().name)
        assertEquals(SERVICE_ID, search.relatedItems.single().serviceId)

        val channelsFilter = service.searchQHFactory.availableContentFilter.filterGroups
            .flatMap { it.filterItems.toList() }
            .single { it.name == "channels" }
        val channelQuery = service.searchQHFactory.fromQuery("fixture channel", listOf(channelsFilter), emptyList())
        val channelSearch = SearchInfo.getInfo(service, channelQuery)
        assertTrue(channelSearch.errors.toString(), channelSearch.errors.isEmpty())
        val channelItem = channelSearch.relatedItems.single()
        assertTrue(channelItem is ChannelInfoItem)
        channelItem as ChannelInfoItem
        assertEquals(SERVICE_ID, channelItem.serviceId)
        assertEquals("Fixture channel", channelItem.name)
        assertEquals("https://www.dailymotion.com/xfixtureowner", channelItem.url)
        assertEquals("xfixtureowner", channelItem.uploaderId)
        assertEquals("Fixture channel description", channelItem.description)
        assertEquals(42L, channelItem.subscriberCount)
        assertEquals(1L, channelItem.streamCount)
        assertEquals("https://s1.dmcdn.net/fixture-avatar.png", channelItem.thumbnailUrl)
        assertFalse(channelItem.isVerified)
        assertTrue(offline.requests.any { URI(it).path == "/users" })

        val video = StreamInfo.getInfo(service, "https://www.dailymotion.com/video/x9fixture")
        assertTrue(video.errors.toString(), video.errors.isEmpty())
        assertEquals("Fixture public video", video.name)
        assertEquals("Fixture channel", video.uploaderName)
        assertEquals(87L, video.duration)
        assertEquals(StreamType.VIDEO_STREAM, video.streamType)
        assertTrue(video.videoStreams.any { it.deliveryMethod == DeliveryMethod.HLS })
        assertEquals("https://s1.dmcdn.net/fixture-subtitle.vtt", video.subtitles.single().content)
        assertEquals("en", video.subtitles.single().languageTag)
        assertTrue(offline.requests.any { URI(it).path == "/player/metadata/video/x9fixture" })
    }

    @Test
    fun channelsPlaylistsAndDiscoveryUseSharedCollectors() {
        val channel = ChannelInfo.getInfo(service, "https://www.dailymotion.com/fixture-channel")
        assertTrue(channel.errors.toString(), channel.errors.isEmpty())
        assertEquals("Fixture channel", channel.name)
        assertEquals(42L, channel.subscriberCount)
        assertTrue(channel.tabs.isNotEmpty())

        val playlistsTab = channel.tabs.single { it.contentFilters.single().name == ChannelTabs.PLAYLISTS }
        val channelPlaylists = ChannelTabInfo.getInfo(service, playlistsTab)
        assertTrue(channelPlaylists.errors.toString(), channelPlaylists.errors.isEmpty())
        val playlistItem = channelPlaylists.relatedItems.single()
        assertTrue(playlistItem is PlaylistInfoItem)
        playlistItem as PlaylistInfoItem
        assertEquals(SERVICE_ID, playlistItem.serviceId)
        assertEquals("Fixture playlist", playlistItem.name)
        assertEquals("https://www.dailymotion.com/playlist/xfixture", playlistItem.url)
        assertEquals("Fixture channel", playlistItem.uploaderName)
        assertEquals("xfixtureowner", playlistItem.uploaderId)
        assertEquals("https://www.dailymotion.com/xfixtureowner", playlistItem.uploaderUrl)
        assertEquals(1L, playlistItem.streamCount)
        assertEquals(PlaylistInfo.PlaylistType.NORMAL, playlistItem.playlistType)
        assertEquals("https://s1.dmcdn.net/fixture-cover.png", playlistItem.thumbnailUrl)
        assertTrue(offline.requests.any { URI(it).path == "/user/xfixtureowner/playlists" })

        val playlist = PlaylistInfo.getInfo(service, "https://www.dailymotion.com/playlist/xfixture")
        assertTrue(playlist.errors.toString(), playlist.errors.isEmpty())
        assertEquals("Fixture playlist", playlist.name)
        assertEquals(1L, playlist.streamCount)
        assertEquals("Fixture public video", playlist.relatedItems.single().name)

        val kiosk = requireNotNull(service.kioskList.defaultKioskExtractor)
        kiosk.fetchPage()
        val discovery = KioskInfo.getInfo(kiosk)
        assertTrue(discovery.errors.toString(), discovery.errors.isEmpty())
        assertEquals("Fixture public video", discovery.relatedItems.single().name)
        assertNotNull(service.channelTabLHFactory)
    }

    private inner class OfflineDownloader : Downloader() {
        val requests = mutableListOf<String>()

        override fun execute(request: Request): Response {
            requests += request.url()
            val uri = URI(request.url())
            val path = uri.path
            val fixture = when {
                uri.host == "www.dailymotion.com" && path == "/player/metadata/video/x9fixture" -> "metadata.json"
                uri.host == "api.dailymotion.com" -> when {
                    path == "/videos" -> "videos.json"
                    path == "/users" -> "channels.json"
                    path == "/playlists" -> "playlists.json"
                    path == "/video/x9fixture" -> "video.json"
                    path == "/video/x9fixture/related" -> "empty.json"
                    path.startsWith("/user/") && path.endsWith("/videos") -> "videos.json"
                    path.startsWith("/user/") && path.endsWith("/playlists") -> "playlists.json"
                    path.startsWith("/user/") -> "channel.json"
                    path == "/playlist/xfixture/videos" -> "videos.json"
                    path == "/playlist/xfixture" -> "playlist.json"
                    else -> throw AssertionError("Unexpected offline API request: ${request.url()}")
                }
                uri.host == "cdn.dailymotion.com" && path == "/fixture.m3u8" -> "master.m3u8"
                uri.host == "s1.dmcdn.net" && path == "/fixture-subtitle.vtt" -> "subtitle.vtt"
                else -> throw AssertionError("Unexpected network request in offline DEX test: ${request.url()}")
            }
            val bytes = assets.open("dailymotion-fixtures/$fixture").use { it.readBytes() }
            return Response(200, "OK", emptyMap(), bytes.toString(Charsets.UTF_8), bytes, request.url())
        }

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw AssertionError("Unexpected asynchronous request in offline DEX test")
    }

    private class TestPreferences : PluginPreferences {
        private val strings = mutableMapOf<String, String>()
        private val booleans = mutableMapOf<String, Boolean>()
        override fun getString(key: String): String? = strings[key]
        override fun getBoolean(key: String): Boolean = booleans[key] ?: false
        override fun putString(key: String, value: String?) { if (value == null) strings.remove(key) else strings[key] = value }
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun addChangeListener(listener: PluginPreferences.ChangeListener) = Unit
    }

    companion object {
        private const val SERVICE_ID = 1_500_000_001
    }
}
