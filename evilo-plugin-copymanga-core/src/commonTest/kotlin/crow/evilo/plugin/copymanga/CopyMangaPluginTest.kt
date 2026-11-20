package crow.evilo.plugin.copymanga

import crow.evilo.model.MediaChapter
import crow.evilo.model.MediaId
import crow.evilo.model.MediaItem
import crow.evilo.model.MediaKind
import crow.evilo.plugin.api.PluginDescriptor
import crow.evilo.plugin.api.PluginJson
import crow.evilo.plugin.api.PluginOperation
import crow.evilo.plugin.api.PluginOperationKind
import crow.evilo.plugin.api.PluginStep
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

class CopyMangaPluginTest {
    private val descriptor = PluginDescriptor(
        id = "crow.evilo.source.copymanga",
        name = "CopyManga",
        version = "1.0.0",
        versionCode = 1,
        kinds = setOf(MediaKind.MANGA),
        languages = listOf("zh"),
    )
    private val clock = MutableClock(1_800_000_000L)
    private val plugin = CopyMangaPlugin(descriptor, clock = clock)
    private val item = MediaItem(
        MediaId(descriptor.id, MediaKind.MANGA, "example"), "Example", "https://untrusted.invalid/ignored",
        "https://sm.mangafunb.fun/m/old.jpg", "Old description", listOf("Old author"), listOf("Old theme"),
    )
    private val chapter = MediaChapter(item.id, "chapter-1", "First", "https://untrusted.invalid/ignored")

    @Test
    fun descriptionMatchesTheGenericProtocol() {
        assertEquals(descriptor, PluginJson.decodeFromString<PluginDescriptor>(plugin.describe()))
    }

    @Test
    fun catalogAndSearchUseTheirOwnLimitsAndEncodeTheQuery() {
        val catalog = start(PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA))
        assertEquals(
            CopyMangaConfig.PRIMARY_LIST_URL + "/api/v3/comics?free_type=1&ordering=-datetime_updated&offset=0&limit=50&platform=1",
            catalog.request?.url,
        )
        val search = start(PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "漫画 &?#", 2))
        assertTrue(assertNotNull(search.request).url.contains("q=%E6%BC%AB%E7%94%BB%20%26%3F%23&q_type=&offset=30&limit=30"))
        assertEquals("COPY/3.1.1", search.request?.headers?.get("User-Agent"))
        assertEquals(CopyMangaSigner.UMSTRING, search.request?.headers?.get("umstring"))
        assertNull(search.request?.headers?.get("X-Requested-With"))
        val large = start(PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "a", Int.MAX_VALUE))
        assertTrue(assertNotNull(large.request).url.contains("offset=64424509380"))
    }

    @Test
    fun shortSearchPagesAdvanceByActualCountsAndReturnOneCompletePublicPage() {
        val operation = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        val first = resume(operation, start(operation), catalogJson(0, 1..10, 31))
        assertNull(first.result)
        assertTrue(assertNotNull(first.request).url.contains("offset=10&limit=20"))
        val second = resume(operation, first, catalogJson(10, 11..20, 31))
        assertTrue(assertNotNull(second.request).url.contains("offset=20&limit=10"))
        val completed = assertNotNull(resume(operation, second, catalogJson(20, 21..30, 31)).result)
        assertEquals((1..30).map { "comic-$it" }, completed.items.map { it.id.value })
        assertTrue(completed.hasNextPage)
        assertEquals("30", completed.extensions["copymanga.next_offset"])
        val lastOperation = operation.copy(page = 2)
        val last = assertNotNull(resume(lastOperation, start(lastOperation), catalogJson(30, 31..31, 31)).result)
        assertEquals(listOf("comic-31"), last.items.map { it.id.value })
        assertFalse(last.hasNextPage)
    }

    @Test
    fun stringEncodedListsAndOptionalCatalogFieldsMapWithoutRequiringSearchOnlyOmissions() {
        val operation = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        val result = assertNotNull(resume(operation, start(operation), """{
            "code":"200","results":{"offset":"0","total":"1","list":
            "[{\"path_word\":\"example\",\"name\":\"Example\",\"cover\":\"//sm.mangafunb.fun/m/cover.jpg\",\"author_alias\":\"Author\",\"popular\":\"79473134\",\"alias\":[\"Other name\"],\"ban\":false}]"}}
        """).result)
        val mapped = result.items.single()
        assertEquals(listOf("Author"), mapped.authors)
        assertTrue(mapped.tags.isEmpty())
        assertEquals("https://sm.mangafunb.fun/m/cover.jpg", mapped.coverUrl)
        assertEquals("79473134", mapped.extensions["copymanga.popular"])
        assertEquals("[\"Other name\"]", mapped.extensions["copymanga.alias"])
        assertFalse(mapped.extensions.containsKey("copymanga.datetime_updated"))
    }

    @Test
    fun detailsRetainMetadataAndReuseGroupsWithoutASecondDiscoveryRequest() {
        val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val first = start(operation)
        assertEquals(CopyMangaSigner.MIRROR_USER_AGENT, first.request?.headers?.get("User-Agent"))
        assertEquals(CopyMangaSigner.REQUESTED_WITH, first.request?.headers?.get("X-Requested-With"))
        assertNull(first.request?.headers?.get("source"))
        val details = assertNotNull(resume(operation, first, """{
            "code":200,"results":{
                "comic":{"path_word":"example","name":"Updated","author":null,"brief":null,
                    "theme":[{"name":" Action ","path_word":"dongzuo"}],"popular":"100",
                    "datetime_updated":"2026-11-17","region":{"name":"Japan"},"last_chapter":{"uuid":"last"}},
                "groups":{"tankobon":{"name":"Volume","count":"0"},"default":{"name":"Main","count":"1"}},
                "is_lock":"false","is_vip":0}}
        """).result?.details)
        assertEquals(item.id, details.id)
        assertEquals(item.authors, details.authors)
        assertEquals(item.description, details.description)
        assertEquals(listOf("Action"), details.tags)
        assertEquals("2026-11-17", details.extensions["copymanga.datetime_updated"])
        assertEquals("{\"uuid\":\"last\"}", details.extensions["copymanga.last_chapter"])
        assertEquals("false", details.extensions["copymanga.is_vip"])
        val chapterOperation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = details)
        assertTrue(assertNotNull(start(chapterOperation).request).url.contains("/group/default/chapters"))
        clock.seconds += 300
        assertTrue(assertNotNull(start(chapterOperation).request).url.contains("/comic2/example"))
    }

    @Test
    fun chapterBatchesKeepGroupAndNumberMetadataAndUseActualOffsets() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovered = resume(operation, start(operation), """{"code":200,"results":{
            "comic":{"path_word":"example"},"groups":{
                "tankobon":{"name":"Volume","count":0},"default":{"name":"Main","count":2}}}}
        """)
        val first = resume(operation, discovered, """{"code":200,"results":{"offset":"0","limit":"1","total":"2",
            "list":[{"uuid":"first","name":"First","index":"1.5","ordered":"100","size":"20",
                "comic_path_word":"example","group_path_word":"default","next":"second","datetime_created":"2026-11-17"}]}}
        """)
        val firstChapter = assertNotNull(first.batch).chapters.single()
        assertEquals(100.0, firstChapter.number)
        assertEquals("Main: First", firstChapter.title)
        assertEquals("100.0", firstChapter.extensions["copymanga.ordered"])
        assertEquals("default", firstChapter.extensions["copymanga.group_path_word"])
        assertTrue(assertNotNull(first.request).url.contains("offset=1"))
        val second = resume(operation, first, """{"code":200,"results":{"offset":1,"total":2,
            "list":[{"uuid":"second","index":"2.5","ordered":"0"}]}}""")
        assertEquals(2.5, second.batch?.chapters?.single()?.number)
        assertTrue(assertNotNull(second.request).url.contains("/group/tankobon/"))
        val volume = assertNotNull(resume(operation, second, """{"code":200,"results":{
            "offset":0,"total":1,"list":[{"uuid":"volume","index":1}]}}""").result)
        assertEquals("Volume: volume", volume.chapters.single().title)
    }

    @Test
    fun mirrorHttpAndNullPayloadFailuresRetryWithFreshSignatureAndPinTheSuccessfulOrigin() {
        val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val first = start(operation)
        clock.seconds++
        val second = resume(operation, first, """{"code":200,"results":null}""")
        assertTrue(assertNotNull(second.request).url.startsWith(CopyMangaConfig.SECONDARY_CONTENT_URL))
        assertNotEquals(first.request?.headers?.get("X-Auth-Signature"), second.request?.headers?.get("X-Auth-Signature"))
        val details = assertNotNull(resume(operation, second, """{"code":200,"results":{
            "comic":{"path_word":"example"},"groups":{"default":{"count":1}}}}""").result?.details)
        assertEquals(CopyMangaConfig.SECONDARY_CONTENT_URL, details.extensions[COPYMANGA_ORIGIN])
        assertTrue(assertNotNull(start(PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = details)).request)
            .url.startsWith(CopyMangaConfig.SECONDARY_CONTENT_URL))
        val third = resume(operation, second, "unavailable", 503)
        assertTrue(assertNotNull(third.request).url.startsWith("https://mapi.hotmangasf.com/"))
    }

    @Test
    fun advertisedNonemptyGroupsRetryAnEmptyMirrorInsteadOfPublishingNoChapters() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovered = resume(operation, start(operation), """{"code":200,"results":{
            "comic":{},"groups":{"default":{"count":"2"}}}}""")
        val retried = resume(operation, discovered, """{"code":200,"results":{"offset":0,"total":0,"list":[]}}""")
        assertNull(retried.result)
        assertNull(retried.batch)
        assertTrue(assertNotNull(retried.request).url.startsWith(CopyMangaConfig.SECONDARY_CONTENT_URL))
    }

    @Test
    fun mirrorRetriesEndAfterEveryReviewedOriginHasBeenAttempted() {
        val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        var step = start(operation)
        val attempted = mutableSetOf<String>()
        repeat(CopyMangaConfig.CONTENT_BASE_URLS.size - 1) {
            val url = assertNotNull(step.request).url
            attempted += url.substringBefore("/api/v3/")
            step = resume(operation, step, """{"code":200,"results":null}""")
        }
        attempted += assertNotNull(step.request).url.substringBefore("/api/v3/")
        assertEquals(CopyMangaConfig.CONTENT_BASE_URLS, attempted)
        assertFailsWith<CopyMangaApiException> { resume(operation, step, """{"code":200,"results":null}""") }
    }

    @Test
    fun searchAndChapterOperationsCanBeInterleavedWithoutSharingState() {
        val search = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        val partial = resume(search, start(search), catalogJson(0, 1..10, 20))
        val other = item.copy(id = item.id.copy(value = "other"))
        val chapters = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = other)
        val discovered = resume(chapters, start(chapters), """{"code":200,"results":{
            "comic":{"path_word":"other"},"groups":{"special":{"count":1}}}}""")
        val otherChapter = resume(chapters, discovered, """{"code":200,"results":{"total":1,"list":[
            {"uuid":"other-chapter","comic_path_word":"other","group_path_word":"special"}]}}""").result?.chapters?.single()
        assertEquals(other.id, otherChapter?.itemId)
        val complete = resume(search, partial, catalogJson(10, 11..20, 20)).result
        assertEquals((1..20).map { "comic-$it" }, complete?.items?.map { it.id.value })
        assertFalse(assertNotNull(complete).hasNextPage)
    }

    @Test
    fun missingAndMalformedOptionalGroupSnapshotsDiscoverAgainWhileEmptyGroupsFinish() {
        val details = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val empty = assertNotNull(resume(details, start(details), """{"code":200,"results":{"comic":{},"groups":{}}}""").result?.details)
        assertNull(start(PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = empty)).request)
        val malformed = item.copy(extensions = mapOf(COPYMANGA_GROUPS to "old invalid snapshot"))
        assertTrue(assertNotNull(start(PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = malformed)).request)
            .url.contains("/comic2/"))
        val refreshed = assertNotNull(resume(details.copy(item = empty), start(details.copy(item = empty)),
            """{"code":200,"results":{"comic":{}}}""").result?.details)
        assertFalse(refreshed.extensions.containsKey(COPYMANGA_GROUPS))
    }

    @Test
    fun stringEncodedResultsNullsAndExplicitEmptyAuthorsKeepDifferentMeanings() {
        val operation = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val response = """{"code":200,"results":"{\"comic\":{\"author\":[],\"theme\":[]}}"}"""
        val mapped = assertNotNull(resume(operation, start(operation), response).result?.details)
        assertTrue(mapped.authors.isEmpty())
        assertTrue(mapped.tags.isEmpty())
        assertEquals(item.title, mapped.title)
        assertEquals(item.description, mapped.description)
        assertEquals(item.coverUrl, mapped.coverUrl)
    }

    @Test
    fun providerRejectionsAndLocksDoNotRetry() {
        val details = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        for (code in listOf(210, 401)) {
            val failure = assertFailsWith<CopyMangaApiException> {
                resume(details, start(details), """{"code":$code,"message":"Access denied"}""")
            }
            assertEquals(code.toString(), failure.apiCode)
            assertFalse(failure.retryable)
        }
        val content = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        assertFailsWith<IllegalStateException> { resume(content, start(content), """{"code":200,"results":{"is_lock":"1"}}""") }
    }

    @Test
    fun contentSupportsNumericStringPermutationSparseRanksAndServerOrder() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        val contents = """[{"url":"//sc.mangafunb.fun/c/third.webp"},
            {"url":"https://sa.mangafunb.fun/a/first.webp"},{"url":"https://sb.mangafunb.fun/b/second.webp"}]"""
        for (words in listOf("[\"3\",\"1\",\"2\"]", "[300,100,200]")) {
            val pages = assertNotNull(resume(operation, start(operation), """{"code":200,"results":{"chapter":{
                "uuid":"chapter-1","size":"3","words":$words,"contents":$contents}}}""").result?.content).images
            assertEquals(listOf("first.webp", "second.webp", "third.webp"), pages.map { it.imageUrl.substringAfterLast('/') })
            assertEquals(listOf(0, 1, 2), pages.map { it.index })
            assertTrue(pages.all { it.headers == mapOf("User-Agent" to "COPY/3.1.1") })
        }
        val unchanged = assertNotNull(resume(operation, start(operation), """{"code":200,"results":{
            "chapter":{"words":[],"contents":$contents}}}""").result?.content).images
        assertTrue(unchanged.first().imageUrl.endsWith("third.webp"))
    }

    @Test
    fun malformedValuesAndCrossOperationResponsesCannotProduceContent() {
        val search = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        for (value in listOf("{}", "[]", "true", "\"many\"")) {
            assertFailsWith<IllegalArgumentException> {
                resume(search, start(search), """{"code":200,"results":{"total":$value,"list":[]}}""")
            }
        }
        val details = PluginOperation(PluginOperationKind.DETAILS, MediaKind.MANGA, item = item)
        val started = start(details)
        assertFailsWith<IllegalArgumentException> {
            resume(details.copy(item = item.copy(id = item.id.copy(value = "other"))), started, "{}")
        }
        assertFailsWith<IllegalArgumentException> {
            plugin.resume(PluginJson.encodeToString(details), started.context, PluginJson.encodeToString(
                SourceResponse("https://untrusted.invalid/response", 200, emptyMap(), "{}"),
            ))
        }
        assertFailsWith<IllegalStateException> {
            resume(details, started, """{"code":200,"results":{"comic":{"path_word":"other"}}}""")
        }
    }

    @Test
    fun malformedPageOrdersDoNotDropOrDuplicateImagesAndNonfiniteChapterNumbersFail() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        val images = """[{"url":"https://sa.mangafunb.fun/a/one.webp"},{"url":"https://sb.mangafunb.fun/b/two.webp"}]"""
        for (words in listOf("[0]", "[0,0]", "[-1,0]")) {
            assertFailsWith<IllegalStateException> {
                resume(operation, start(operation), """{"code":200,"results":{"chapter":{"words":$words,"contents":$images}}}""")
            }
        }
        for (words in listOf("[null,0]", "[{},0]", "[\"invalid\",0]")) {
            assertFailsWith<IllegalArgumentException> {
                resume(operation, start(operation), """{"code":200,"results":{"chapter":{"words":$words,"contents":$images}}}""")
            }
        }
        val chapterOperation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovered = resume(chapterOperation, start(chapterOperation), """{"code":200,"results":{
            "comic":{},"groups":{"default":{"count":1}}}}""")
        for (number in listOf("NaN", "Infinity")) {
            assertFailsWith<IllegalArgumentException> {
                resume(chapterOperation, discovered, """{"code":200,"results":{"total":1,"list":[{"uuid":"only","index":"$number"}]}}""")
            }
        }
    }

    @Test
    fun everyDeclaredImageHostIsAcceptedWithoutRewritingItAndUnknownHostsFail() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        for (origin in CopyMangaConfig.MEDIA_BASE_URLS) {
            val url = "$origin/m/image.jpg.h1500x.webp"
            val result = resume(operation, start(operation), """{"code":200,"results":{
                "chapter":{"contents":[{"url":"$url"}]}}}""")
            assertEquals(url, result.result?.content?.images?.single()?.imageUrl)
        }
        for (url in listOf("http://sm.mangafunb.fun/m/image.jpg", "https://sm.mangafunb.fun.evil.invalid/m/image.jpg",
            "https://sm.mangafunb.fun@evil.invalid/m/image.jpg", "https://unknown.invalid/m/image.jpg")) {
            assertFailsWith<IllegalStateException> {
                resume(operation, start(operation), """{"code":200,"results":{"chapter":{"contents":[{"url":"$url"}]}}}""")
            }
        }
    }

    @Test
    fun chapterDiscoveryUsesNestedComicGroupsWhenTopLevelGroupsAreAbsentOrEmpty() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        for (topLevel in listOf("", ",\"groups\":{}")) {
            val discovered = resume(operation, start(operation), """{"code":200,"results":{"comic":{
                "path_word":"example","groups":{"special":{"name":"Special","count":1}}}$topLevel}}""")
            assertTrue(assertNotNull(discovered.request).url.contains("/group/special/chapters"))
        }
    }

    @Test
    fun chapterRequestsUseTheReviewedHeaderPairAndCurrentGuestPaths() {
        val operation = PluginOperation(PluginOperationKind.CHAPTERS, MediaKind.MANGA, item = item)
        val discovery = start(operation)
        val list = resume(operation, discovery, """{"code":200,"results":{
            "comic":{"path_word":"example"},"groups":{"default":{"count":1}}}}""")
        val content = start(PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter))
        assertTrue(assertNotNull(list.request).url.contains("/group/default/chapters?limit=500&offset=0&platform=1"))
        assertTrue(assertNotNull(content.request).url.endsWith("/chapter/chapter-1?platform=1"))
        for (step in listOf(discovery, list, content)) {
            val request = assertNotNull(step.request)
            assertTrue(request.url.startsWith(CopyMangaConfig.PRIMARY_CONTENT_URL + "/api/v3/"))
            assertEquals("GET", request.method)
            assertEquals("1", request.headers["platform"])
            assertEquals("3.1.1", request.headers["version"])
            assertEquals("1", request.headers["webp"])
            assertEquals("*/*", request.headers["Accept"])
            assertTrue(request.headers.keys.none { it.lowercase() in setOf("source", "dt", "cookie", "authorization", "host", "origin") })
        }
    }

    @Test
    fun contentWithoutWordsUsesEveryServerImageAndEmptyPayloadRetries() {
        val operation = PluginOperation(PluginOperationKind.CONTENT, MediaKind.MANGA, chapter = chapter)
        val retry = resume(operation, start(operation), """{"code":200,"results":{"chapter":{"contents":[]}}}""")
        assertTrue(assertNotNull(retry.request).url.startsWith(CopyMangaConfig.SECONDARY_CONTENT_URL))
        val pages = assertNotNull(resume(operation, retry, """{"code":200,"results":{"chapter":{
            "uuid":"chapter-1","size":2,"contents":[{"url":"https://sm.mangafunb.fun/m/one.webp"},
            {"url":"https://sm.mangafunb.fun/m/two.webp"}]}}}""").result?.content).images
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertEquals(listOf("one.webp", "two.webp"), pages.map { it.imageUrl.substringAfterLast('/') })
    }

    private fun start(operation: PluginOperation): PluginStep = PluginJson.decodeFromString(plugin.start(PluginJson.encodeToString(operation)))

    private fun resume(operation: PluginOperation, step: PluginStep, body: String, status: Int = 200): PluginStep =
        PluginJson.decodeFromString(plugin.resume(PluginJson.encodeToString(operation), step.context,
            PluginJson.encodeToString(SourceResponse(assertNotNull(step.request).url, status, emptyMap(), body))))

    private fun catalogJson(offset: Int, indexes: IntRange, total: Int): String {
        val list = indexes.joinToString(",") { """{"path_word":"comic-$it","name":"Comic $it"}""" }
        return """{"code":200,"results":{"offset":$offset,"limit":10,"total":$total,"list":[$list]}}"""
    }

    private class MutableClock(var seconds: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(seconds)
    }
}
