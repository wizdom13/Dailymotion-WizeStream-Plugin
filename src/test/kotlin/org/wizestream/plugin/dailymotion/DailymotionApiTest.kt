/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.dailymotion

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.localization.Localization

class DailymotionApiTest {
    @Test fun usesHostDownloaderWithEncodedQueryAndFixedOrigin() {
        val downloader = ApiFixture(200, "{\"list\":[],\"has_more\":false}")
        val api = DailymotionApi(downloader, Localization("ar", "LB"))
        api.get("/videos", mapOf("search" to "طبيعة & birds/sea", "limit" to "20"))
        val request = downloader.requests.single()
        assertEquals("https", URI(request.url()).scheme)
        assertEquals("api.dailymotion.com", URI(request.url()).host)
        assertEquals("/videos", URI(request.url()).path)
        assertTrue(URLDecoder.decode(URI(request.url()).rawQuery, "UTF-8").contains("search=طبيعة & birds/sea"))
        assertEquals(listOf("application/json"), request.headers()["Accept"])
    }

    @Test fun rejectsArbitraryPaginationOrTraversalWithoutNetworking() {
        val downloader = ApiFixture(200, "{}")
        val api = DailymotionApi(downloader, Localization.DEFAULT)
        listOf("https://evil.invalid/videos", "//evil.invalid/videos", "../videos", "user/../videos", "videos?token=x", "videos%2Fx").forEach {
            assertThrows(it, ParsingException::class.java) { api.get(it) }
        }
        assertTrue(downloader.requests.isEmpty())
    }

    @Test fun distinguishesRestrictionsIncludingErrorsInsideSuccessfulHttpResponses() {
        fun api(body: String, status: Int = 200) = DailymotionApi(ApiFixture(status, body), Localization.DEFAULT)
        assertThrows(GeographicRestrictionException::class.java) { api("{\"error\":{\"code\":\"DM007\"}}").playerMetadata("x123") }
        assertThrows(AgeRestrictedContentException::class.java) { api("{\"error\":{\"message\":\"age restricted video\"}}").playerMetadata("x123") }
        assertThrows(PrivateContentException::class.java) { api("{\"error\":{\"message\":\"Video is private\"}}").playerMetadata("x123") }
        assertThrows(ContentNotAvailableException::class.java) { api("Not found", 404).playerMetadata("x123") }
        assertThrows(ExtractionException::class.java) { api("", 429).get("videos") }
        val pageError = assertThrows(ContentNotAvailableException::class.java) { api("{\"error\":{\"message\":\"Page not found\"}}").get("videos") }
        assertFalse(pageError is AgeRestrictedContentException)
    }

    @Test fun failsOnMalformedSuccessfulJsonInsteadOfReturningEmptyResults() {
        val api = DailymotionApi(ApiFixture(200, "<html>unexpected</html>"), Localization.DEFAULT)
        assertThrows(ParsingException::class.java) { api.get("videos") }
    }

    private class ApiFixture(private val code: Int, private val body: String) : Downloader() {
        val requests = mutableListOf<Request>()
        override fun execute(request: Request): Response {
            requests += request
            return Response(code, "fixture", emptyMap(), body, body.toByteArray(), request.url())
        }
        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall = throw AssertionError("Unexpected async request")
    }
}
