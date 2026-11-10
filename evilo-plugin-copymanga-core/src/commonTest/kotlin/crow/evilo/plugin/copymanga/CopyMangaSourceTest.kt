package crow.evilo.plugin.copymanga

import crow.evilo.model.Chapter
import crow.evilo.model.Manga
import crow.evilo.model.MangaId
import crow.evilo.plugin.api.SOURCE_API_VERSION
import crow.evilo.plugin.api.SourceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private const val TEST_SOURCE_ID = "crow.evilo.source.copymanga"
private const val TEST_SOURCE_VERSION = "1.0.0"

class CopyMangaSourceTest {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_700_000_000L)
    }
    private val source = CopyMangaSource(TEST_SOURCE_ID, TEST_SOURCE_VERSION, clock = clock)
    private val manga = Manga(
        id = MangaId(TEST_SOURCE_ID, "example"),
        title = "Example",
        url = "https://untrusted.invalid/ignored",
        coverUrl = "https://images.invalid/old.jpg",
        description = "Old description",
        authors = listOf("Old author"),
    )
    private val chapter = Chapter(manga.id, "chapter-1", "First", "https://untrusted.invalid/ignored")

    @Test
    fun metadataMatchesTheSourceContract() {
        val metadata = source.metadata()
        assertEquals(TEST_SOURCE_ID, metadata.id)
        assertEquals(TEST_SOURCE_VERSION, metadata.version)
        assertEquals(SOURCE_API_VERSION, metadata.apiVersion)
        assertEquals("zh", metadata.language)
    }

    @Test
    fun searchEncodesUtf8AndQueryDelimiters() {
        val request = source.searchRequest("\u6f2b\u753b &?#", 2)
        assertEquals(
            CopyMangaConfig.PRIMARY_API_URL +
                "/api/v3/search/comic?q=%E6%BC%AB%E7%94%BB%20%26%3F%23&q_type=&offset=20&limit=20",
            request.url,
        )
        assertEquals("1", request.headers["platform"])
        assertEquals(CopyMangaSigner.CLIENT_VERSION, request.headers["version"])
        assertEquals(CopyMangaSigner.USER_AGENT, request.headers["User-Agent"])
        assertEquals("1700000000", request.headers["X-Auth-Timestamp"])
        assertEquals("1700000000", request.headers["tapString"])
        assertFalse(request.headers.containsKey("source"))
        assertFalse(request.headers.containsKey("dt"))
        assertFalse(request.headers.containsKey("PmxApi"))
        assertFalse(request.headers.containsKey("authorization"))
    }

    @Test
    fun blankSearchBrowsesTheComicCatalog() {
        assertEquals(
            CopyMangaConfig.PRIMARY_API_URL + "/api/v3/comics?free_type=1&offset=0&limit=20",
            source.searchRequest("", 1).url,
        )
    }

    @Test
    fun pageOffsetsDoNotOverflowAtLargePageNumbers() {
        assertTrue(source.searchRequest("example", Int.MAX_VALUE).url.contains("offset=42949672920"))
        assertFailsWith<IllegalArgumentException> { source.searchRequest("example", 0) }
    }

    @Test
    fun everyRequestUsesTheCurrentClock() {
        val clock = MutableClock(1_700_000_000L)
        val source = CopyMangaSource(TEST_SOURCE_ID, TEST_SOURCE_VERSION, clock = clock)
        val first = source.detailsRequest(manga).headers
        clock.seconds++
        val second = source.pagesRequest(chapter).headers
        assertNotEquals(first["X-Auth-Timestamp"], second["X-Auth-Timestamp"])
        assertNotEquals(first["X-Auth-Signature"], second["X-Auth-Signature"])
    }

    @Test
    fun configuredApiOriginsDoNotAcceptArbitraryHostsOrCredentials() {
        val secondary = CopyMangaSource(TEST_SOURCE_ID, TEST_SOURCE_VERSION, CopyMangaConfig(CopyMangaConfig.SECONDARY_API_URL, "0"), clock)
        val request = secondary.detailsRequest(manga)
        assertTrue(request.url.startsWith(CopyMangaConfig.SECONDARY_API_URL + "/"))
        assertEquals("0", request.headers["region"])
        for (url in listOf(
            "https://untrusted.invalid",
            "http://api.copy202602.com",
            "https://api.copy202602.com@untrusted.invalid",
            "https://api.copy202602.com/path",
        )) {
            assertFailsWith<IllegalArgumentException> { CopyMangaConfig(url) }
        }
        assertFailsWith<IllegalArgumentException> { CopyMangaConfig(region = "3") }
    }

    @Test
    fun requestsUseSourceIdsInsteadOfInputUrls() {
        assertEquals(
            CopyMangaConfig.PRIMARY_API_URL + "/api/v3/comic2/example",
            source.detailsRequest(manga).url,
        )
        assertEquals(
            CopyMangaConfig.PRIMARY_API_URL + "/api/v3/comic/example/chapter2/chapter-1",
            source.pagesRequest(chapter).url,
        )
        val escaped = manga.copy(id = MangaId(TEST_SOURCE_ID, "a/b?#"))
        assertTrue(source.detailsRequest(escaped).url.endsWith("a%2Fb%3F%23"))
        assertFailsWith<IllegalArgumentException> {
            source.detailsRequest(manga.copy(id = MangaId(TEST_SOURCE_ID, "..")))
        }
        assertFailsWith<IllegalArgumentException> { source.pagesRequest(chapter.copy(id = ".")) }
        val foreign = manga.copy(id = MangaId("another-source", "example"))
        assertFailsWith<IllegalArgumentException> { source.detailsRequest(foreign) }
        assertFailsWith<IllegalArgumentException> { source.chaptersRequest(foreign) }
        assertFailsWith<IllegalArgumentException> { source.pagesRequest(chapter.copy(mangaId = foreign.id)) }
    }

    @Test
    fun searchAcceptsOptionalNullsAndUnknownFields() {
        val result = source.parseSearch("example", 1, response(
            """{
                "code":"200",
                "results":{
                    "offset":0,"limit":20,"total":2,
                    "list":[
                        {"path_word":"first","name":"First","cover":"//images.invalid/first.jpg",
                         "author":[{"name":" Author "},null,{"name":null},{"name":"Author"}],"unknown":true},
                        {"path_word":"second","name":"Second","cover":null,"brief":null,"author":null}
                    ]
                }
            }""",
        ))
        assertEquals(listOf("first", "second"), result.manga.map { it.id.value })
        assertTrue(result.manga.all { it.id.sourceId == TEST_SOURCE_ID })
        assertEquals(listOf("Author"), result.manga.first().authors)
        assertEquals("https://images.invalid/first.jpg", result.manga.first().coverUrl)
        assertNull(result.manga.last().coverUrl)
        assertFalse(result.hasNextPage)
    }

    @Test
    fun stringEncodedResultsAreDecodedOnce() {
        val result = source.parseSearch("example", 1, response(
            """{"code":200,"results":"{\"list\":[],\"total\":0}"}""",
        ))
        assertTrue(result.manga.isEmpty())
        assertFalse(result.hasNextPage)
    }

    @Test
    fun fullSearchPagesAdvanceWithoutSkippingResults() {
        val list = (1..20).joinToString(",") { index ->
            """{"path_word":"comic-$index","name":"Comic $index"}"""
        }
        val first = source.parseSearch("example", 1, response(
            """{"code":200,"results":{"offset":0,"limit":20,"total":21,"list":[$list]}}""",
        ))
        assertTrue(first.hasNextPage)
        val last = source.parseSearch("example", 2, response(
            """{"code":200,"results":{"offset":20,"limit":20,"total":21,
                "list":[{"path_word":"comic-21","name":"Comic 21"}]}}""",
        ))
        assertFalse(last.hasNextPage)
        val withoutTotal = source.parseSearch("example", 1, response(
            """{"code":200,"results":{"limit":20,"list":[$list]}}""",
        ))
        assertTrue(withoutTotal.hasNextPage)
    }

    @Test
    fun truncatedSearchPagesFailInsteadOfSkippingResults() {
        assertFailsWith<IllegalStateException> {
            source.parseSearch("example", 1, response(
                """{"code":200,"results":{"limit":1,"list":[{"path_word":"first","name":"First"}]}}""",
            ))
        }
    }

    @Test
    fun detailsPreserveTheIdAndPreviouslyKnownOptionalValues() {
        val result = source.parseDetails(manga, response(
            """{"code":200,"results":{"comic":{"name":"Updated","cover":null,"author":null,"brief":null}}}""",
        ))
        assertEquals(manga.id, result.id)
        assertEquals("Updated", result.title)
        assertEquals(manga.coverUrl, result.coverUrl)
        assertEquals(manga.authors, result.authors)
        assertEquals(manga.description, result.description)
        assertFailsWith<IllegalStateException> {
            source.parseDetails(manga, response(
                """{"code":200,"results":{"comic":{"path_word":"different","name":"Different"}}}""",
            ))
        }
    }

    @Test
    fun chapterRequestsTraverseServerCappedPagesAndAllGroups() {
        val clock = MutableClock(1_700_000_000L)
        val source = CopyMangaSource(TEST_SOURCE_ID, TEST_SOURCE_VERSION, clock = clock)
        val start = source.chaptersRequest(manga)
        val discovery = source.parseChapters(manga, start, response(
            """{"code":200,"results":{
                "comic":{"path_word":"example"},
                "groups":{
                    "volume":{"name":"Volume","count":0},
                    "default":{"name":"Main","count":2}
                }
            }}""",
        ))
        assertTrue(discovery.chapters.isEmpty())
        val firstRequest = assertNotNull(discovery.nextRequest)
        assertTrue(firstRequest.request.url.endsWith("/group/default/chapters?offset=0&limit=100"))
        assertNotNull(firstRequest.context)
        val first = source.parseChapters(manga, firstRequest, response(
            """{"code":200,"results":{"offset":0,"limit":1,"total":2,
                "list":[{"uuid":"first","name":"First","comic_path_word":"example","ordered":1}]}}""",
        ))
        assertEquals("Main: First", first.chapters.single().title)
        assertEquals(1.0, first.chapters.single().number)
        clock.seconds++
        val secondRequest = assertNotNull(first.nextRequest)
        assertTrue(secondRequest.request.url.endsWith("/group/default/chapters?offset=1&limit=100"))
        val second = source.parseChapters(manga, secondRequest, response(
            """{"code":200,"results":{"offset":1,"limit":1,"total":2,
                "list":[{"uuid":"second","name":"Second","ordered":2}]}}""",
        ))
        val volumeRequest = assertNotNull(second.nextRequest)
        assertTrue(volumeRequest.request.url.endsWith("/group/volume/chapters?offset=0&limit=100"))
        assertNotEquals(secondRequest.request.headers["X-Auth-Timestamp"], volumeRequest.request.headers["X-Auth-Timestamp"])
        val volume = source.parseChapters(manga, volumeRequest, response(
            """{"code":200,"results":{"offset":0,"limit":100,"total":1,
                "list":[{"uuid":"volume-one","name":"One","index":1}]}}""",
        ))
        assertEquals("Volume: One", volume.chapters.single().title)
        assertTrue((first.chapters + second.chapters + volume.chapters).all { it.mangaId == manga.id })
        assertNull(volume.nextRequest)
    }

    @Test
    fun chapterDiscoveryDoesNotInventADefaultGroup() {
        val result = source.parseChapters(manga, source.chaptersRequest(manga), response(
            """{"code":200,"results":{"comic":{},"groups":{"special/group":{"name":"Special"}}}}""",
        ))
        assertTrue(assertNotNull(result.nextRequest).request.url.contains("/group/special%2Fgroup/"))
        val empty = source.parseChapters(manga, source.chaptersRequest(manga), response(
            """{"code":200,"results":{"comic":{},"groups":{}}}""",
        ))
        assertNull(empty.nextRequest)
        assertTrue(empty.chapters.isEmpty())
        assertFailsWith<IllegalStateException> {
            source.parseChapters(manga, source.chaptersRequest(manga), response(
                """{"code":200,"results":{"comic":{},"groups":null}}""",
            ))
        }
    }

    @Test
    fun overlappingChapterOperationsKeepIndependentContext() {
        val other = manga.copy(id = MangaId(TEST_SOURCE_ID, "other"))
        val firstRequest = assertNotNull(source.parseChapters(manga, source.chaptersRequest(manga), response(
            """{"code":200,"results":{"comic":{},"groups":{"first":{"name":"First"}}}}""",
        )).nextRequest)
        val otherRequest = assertNotNull(source.parseChapters(other, source.chaptersRequest(other), response(
            """{"code":200,"results":{"comic":{},"groups":{"other":{"name":"Other"}}}}""",
        )).nextRequest)
        val otherResult = source.parseChapters(other, otherRequest, response(
            """{"code":200,"results":{"offset":0,"total":1,"list":[{"uuid":"other-chapter"}]}}""",
        ))
        val firstResult = source.parseChapters(manga, firstRequest, response(
            """{"code":200,"results":{"offset":0,"total":1,"list":[{"uuid":"first-chapter"}]}}""",
        ))
        assertEquals(other.id, otherResult.chapters.single().mangaId)
        assertEquals(manga.id, firstResult.chapters.single().mangaId)
        assertFailsWith<IllegalArgumentException> {
            source.parseChapters(other, firstRequest, response("{}"))
        }
        assertFailsWith<IllegalArgumentException> {
            source.parseChapters(manga, firstRequest.copy(request = source.detailsRequest(manga)), response("{}"))
        }
    }

    @Test
    fun pagesFollowTheWordsPermutationAndUseZeroBasedIndexes() {
        val pages = source.parsePages(chapter, response(
            """{"code":200,"results":{"chapter":{"uuid":"chapter-1","comic_path_word":"example",
                "words":[2,0,1],"contents":[
                    {"url":"https://images.invalid/third.jpg"},
                    {"url":"//images.invalid/first.jpg"},
                    {"url":"https://images.invalid/second.jpg"}
                ]}}}""",
        ))
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), pages.map { it.imageUrl.substringAfterLast('/') })
        assertTrue(pages.all { it.headers == mapOf("User-Agent" to CopyMangaSigner.USER_AGENT) })
    }

    @Test
    fun absentWordsPreserveTheReturnedImageOrder() {
        val pages = source.parsePages(chapter, response(
            """{"code":200,"results":{"chapter":{"words":null,"contents":[
                {"url":"https://images.invalid/one.jpg"},{"url":"https://images.invalid/two.jpg"}
            ]}}}""",
        ))
        assertEquals(listOf("one.jpg", "two.jpg"), pages.map { it.imageUrl.substringAfterLast('/') })
    }

    @Test
    fun malformedPageOrderDoesNotSilentlyDropImages() {
        for (words in listOf("[0]", "[0,0]", "[-1,0]")) {
            assertFailsWith<IllegalStateException> {
                source.parsePages(chapter, response(
                    """{"code":200,"results":{"chapter":{"words":$words,"contents":[
                        {"url":"https://images.invalid/one.jpg"},{"url":"https://images.invalid/two.jpg"}
                    ]}}}""",
                ))
            }
        }
    }

    @Test
    fun lockedOrInvalidPagesFailWithoutPlaceholderImages() {
        for (results in listOf(
            """{"is_lock":true}""",
            """{"comic":{"path_word":"different"},"chapter":{"contents":[]}}""",
            """{"chapter":{"uuid":"different","contents":[]}}""",
            """{"chapter":{"contents":[]}}""",
            """{"chapter":{"contents":[{"url":null}]}}""",
            """{"chapter":{"contents":[{"url":"http://images.invalid/plain.jpg"}]}}""",
        )) {
            assertFailsWith<IllegalStateException> {
                source.parsePages(chapter, response("""{"code":200,"results":$results}"""))
            }
        }
    }

    @Test
    fun errorsAndInvalidOffsetsAreNotReportedAsEmptyResults() {
        val rejected = assertFailsWith<CopyMangaApiException> {
            source.parseSearch("example", 1, response("""{"code":"error","message":"Rejected"}"""))
        }
        assertEquals("error", rejected.apiCode)
        assertTrue(assertNotNull(rejected.message).contains("Rejected"))
        val limited = assertFailsWith<CopyMangaApiException> {
            source.parseSearch("example", 1, response("""{"code":210,"message":"Wait 30 seconds"}"""))
        }
        assertEquals("210", limited.apiCode)
        val http = assertFailsWith<CopyMangaApiException> {
            source.parseSearch("example", 1, response("not JSON", 403))
        }
        assertEquals(403, http.httpStatusCode)
        for (body in listOf(
            """{"code":200,"results":null}""",
            """{"code":200,"results":{}}""",
            """{"code":200,"results":{"offset":20,"list":[],"total":0}}""",
            """{"code":200,"results":{"offset":0,"list":[],"total":1}}""",
        )) {
            assertFailsWith<IllegalStateException> { source.parseSearch("example", 1, response(body)) }
        }
    }

    private fun response(body: String, statusCode: Int = 200): SourceResponse = SourceResponse(
        url = CopyMangaConfig.PRIMARY_API_URL,
        statusCode = statusCode,
        headers = emptyMap(),
        body = body,
    )

    private class MutableClock(var seconds: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }
}
