package crow.evilo.plugin.copymanga

import crow.evilo.model.Chapter
import crow.evilo.model.Manga
import crow.evilo.model.MangaId
import crow.evilo.model.Page
import crow.evilo.plugin.api.MangaSearchResult
import crow.evilo.plugin.api.MangaSourceService
import crow.evilo.plugin.api.SOURCE_API_VERSION
import crow.evilo.plugin.api.SourceChapterRequest
import crow.evilo.plugin.api.SourceChapterResult
import crow.evilo.plugin.api.SourceMetadata
import crow.evilo.plugin.api.SourceRequest
import crow.evilo.plugin.api.SourceResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

private const val SEARCH_PAGE_SIZE = 20
private const val CHAPTER_PAGE_SIZE = 100

class CopyMangaSource(
    private val sourceId: String,
    private val sourceVersion: String,
    private val config: CopyMangaConfig = CopyMangaConfig(),
    private val clock: Clock = Clock.System,
) : MangaSourceService {
    init {
        require(sourceId.isNotBlank() && sourceVersion.isNotBlank()) { "Source identity comes from the guest release descriptor." }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun metadata(): SourceMetadata = SourceMetadata(
        id = sourceId,
        name = "CopyManga",
        language = "zh",
        version = sourceVersion,
        apiVersion = SOURCE_API_VERSION,
    )

    override fun searchRequest(query: String, page: Int): SourceRequest {
        val offset = searchOffset(page)
        val path = if (query.isBlank()) {
            "/api/v3/comics?free_type=1"
        } else {
            "/api/v3/search/comic?q=${encodeComponent(query)}&q_type="
        }
        return request("${config.apiBaseUrl}$path&offset=$offset&limit=$SEARCH_PAGE_SIZE")
    }

    override fun parseSearch(query: String, page: Int, response: SourceResponse): MangaSearchResult {
        val offset = searchOffset(page)
        val results = readResults<CopyMangaList<CopyMangaComic>>(response)
        val comics = checkNotNull(results.list) { "CopyManga search results contain no list." }
        val hasNextPage = hasNextPage(results, offset, comics.size, SEARCH_PAGE_SIZE)
        check(comics.size <= SEARCH_PAGE_SIZE) { "CopyManga search page exceeds the requested limit." }
        // The next public page advances by 20, so a short non-final page would skip results.
        check(!hasNextPage || comics.size == SEARCH_PAGE_SIZE) {
            "CopyManga returned an incomplete search page before its reported end."
        }
        return MangaSearchResult(
            manga = comics.map { comic -> comic.toManga() },
            hasNextPage = hasNextPage,
        )
    }

    override fun detailsRequest(manga: Manga): SourceRequest {
        requireSource(manga.id)
        return request(detailsUrl(manga.id.value))
    }

    override fun parseDetails(manga: Manga, response: SourceResponse): Manga {
        requireSource(manga.id)
        val details = readResults<CopyMangaDetails>(response)
        val comic = checkNotNull(details.comic) { "CopyManga details contain no comic." }
        return comic.toManga(manga)
    }

    override fun chaptersRequest(manga: Manga): SourceChapterRequest =
        SourceChapterRequest(request = detailsRequest(manga))

    override fun parseChapters(
        manga: Manga,
        request: SourceChapterRequest,
        response: SourceResponse,
    ): SourceChapterResult {
        requireSource(manga.id)
        val requestContext = request.context
        if (requestContext == null) {
            requireRequest(request.request, detailsUrl(manga.id.value))
            val details = readResults<CopyMangaDetails>(response)
            val comic = checkNotNull(details.comic) { "CopyManga chapter discovery contains no comic." }
            check(comic.pathWord.isNullOrBlank() || comic.pathWord == manga.id.value) {
                "CopyManga chapter discovery must preserve the requested manga ID."
            }
            val responseGroups = checkNotNull(details.groups) { "CopyManga chapter discovery contains no groups." }
            val groups = responseGroups.map { (key, group) ->
                val pathWord = key.takeIf { it.isNotBlank() } ?: group?.pathWord
                check(!pathWord.isNullOrBlank()) { "CopyManga returned a blank chapter group." }
                CopyMangaChapterGroup(
                    pathWord = pathWord,
                    name = group?.name?.takeIf { it.isNotBlank() } ?: pathWord,
                )
            }.distinctBy { it.pathWord }.sortedWith(
                compareBy<CopyMangaChapterGroup> { it.pathWord != "default" }.thenBy { it.pathWord },
            )
            if (groups.isEmpty()) return SourceChapterResult()
            return SourceChapterResult(
                nextRequest = chapterRequest(CopyMangaChapterContext(manga.id.value, groups)),
            )
        }

        val context = json.decodeFromString<CopyMangaChapterContext>(requestContext)
        require(context.mangaId == manga.id.value) { "Chapter request context belongs to another manga." }
        require(context.groupIndex in context.groups.indices && context.offset >= 0) {
            "Invalid CopyManga chapter request context."
        }
        requireRequest(request.request, chaptersUrl(context))
        val results = readResults<CopyMangaList<CopyMangaChapter>>(response)
        val entries = checkNotNull(results.list) { "CopyManga chapter results contain no list." }
        val hasMore = hasNextPage(results, context.offset, entries.size, CHAPTER_PAGE_SIZE)
        val group = context.groups[context.groupIndex]
        val chapters = entries.map { entry ->
            check(entry.comicPathWord.isNullOrBlank() || entry.comicPathWord == manga.id.value) {
                "CopyManga returned a chapter belonging to another manga."
            }
            val id = checkNotNull(entry.uuid?.takeIf { it.isNotBlank() }) {
                "CopyManga returned a chapter without an ID."
            }
            val title = entry.name?.takeIf { it.isNotBlank() } ?: id
            Chapter(
                mangaId = manga.id,
                id = id,
                title = if (context.groups.size > 1) "${group.name}: $title" else title,
                url = pagesUrl(manga.id.value, id),
                number = entry.ordered ?: entry.index,
            )
        }
        val nextContext = when {
            hasMore -> context.copy(offset = context.offset + entries.size)
            context.groupIndex + 1 < context.groups.size -> context.copy(
                groupIndex = context.groupIndex + 1,
                offset = 0,
            )
            else -> null
        }
        return SourceChapterResult(
            chapters = chapters,
            nextRequest = nextContext?.let(::chapterRequest),
        )
    }

    override fun pagesRequest(chapter: Chapter): SourceRequest {
        requireSource(chapter.mangaId)
        require(chapter.id.isNotBlank()) { "Chapter ID must not be blank." }
        return request(pagesUrl(chapter.mangaId.value, chapter.id))
    }

    override fun parsePages(chapter: Chapter, response: SourceResponse): List<Page> {
        requireSource(chapter.mangaId)
        require(chapter.id.isNotBlank()) { "Chapter ID must not be blank." }
        val results = readResults<CopyMangaPages>(response)
        check(results.isLock != true) {
            "CopyManga chapter ${chapter.id} is locked; authentication or access permission is required."
        }
        val comicPathWord = results.comic?.pathWord
        check(comicPathWord.isNullOrBlank() || comicPathWord == chapter.mangaId.value) {
            "CopyManga returned pages belonging to another manga."
        }
        val contents = checkNotNull(results.chapter) { "CopyManga page results contain no chapter." }
        check(contents.uuid.isNullOrBlank() || contents.uuid == chapter.id) {
            "CopyManga returned pages belonging to another chapter."
        }
        check(contents.comicPathWord.isNullOrBlank() || contents.comicPathWord == chapter.mangaId.value) {
            "CopyManga returned pages belonging to another manga."
        }
        val images = checkNotNull(contents.contents) { "CopyManga page results contain no contents." }
        check(images.isNotEmpty()) { "CopyManga chapter ${chapter.id} contains no images." }
        val words = contents.words.orEmpty()
        // zip() would silently discard images if the API returned a partial order list.
        check(words.isEmpty() || words.size == images.size) { "CopyManga image order length does not match contents." }
        check(words.all { it >= 0 } && words.distinct().size == words.size) {
            "CopyManga image order contains negative or duplicate positions."
        }
        val indexes = if (words.isEmpty()) images.indices.toList() else images.indices.sortedBy { words[it] }
        return indexes.mapIndexed { index, imageIndex ->
            val url = checkNotNull(images[imageIndex]?.url?.takeIf { it.isNotBlank() }) {
                "CopyManga returned an image without a URL."
            }
            Page(
                index = index,
                imageUrl = imageUrl(url),
                headers = mapOf("User-Agent" to CopyMangaSigner.USER_AGENT),
            )
        }
    }

    private fun request(url: String): SourceRequest = SourceRequest(
        url = url,
        headers = mapOf(
            "User-Agent" to CopyMangaSigner.USER_AGENT,
            "Accept" to "application/json",
            "platform" to "1",
            "version" to CopyMangaSigner.CLIENT_VERSION,
            "region" to config.region,
            "webp" to "1",
            "referer" to "com.copymanga.app-2.5.0",
        ) + CopyMangaSigner.authHeaders(clock.now().epochSeconds),
    )

    private fun detailsUrl(mangaId: String): String =
        "${config.apiBaseUrl}/api/v3/comic2/${encodePathSegment(mangaId)}"

    private fun pagesUrl(mangaId: String, chapterId: String): String =
        "${config.apiBaseUrl}/api/v3/comic/${encodePathSegment(mangaId)}/chapter2/${encodePathSegment(chapterId)}"

    private fun chaptersUrl(context: CopyMangaChapterContext): String {
        val group = context.groups[context.groupIndex]
        return "${config.apiBaseUrl}/api/v3/comic/${encodePathSegment(context.mangaId)}" +
            "/group/${encodePathSegment(group.pathWord)}/chapters?offset=${context.offset}&limit=$CHAPTER_PAGE_SIZE"
    }

    private fun chapterRequest(context: CopyMangaChapterContext): SourceChapterRequest = SourceChapterRequest(
        request = request(chaptersUrl(context)),
        context = json.encodeToString(context),
    )

    private inline fun <reified Result> readResults(response: SourceResponse): Result {
        if (response.statusCode !in 200..299) {
            throw CopyMangaApiException(response.statusCode, null, "CopyManga HTTP ${response.statusCode}.")
        }
        val envelope = json.decodeFromString<CopyMangaEnvelope>(response.body)
        val code = envelope.code?.content
        if (code != "200") {
            throw CopyMangaApiException(
                response.statusCode,
                code,
                "CopyManga API ${code ?: "unknown"}: ${envelope.message ?: "Request rejected."}",
            )
        }
        var results = envelope.results
        check(results != null && results != JsonNull) { "CopyManga API returned no results." }
        if (results is JsonPrimitive && results.isString) {
            results = json.parseToJsonElement(results.content)
        }
        return json.decodeFromJsonElement(results)
    }

    private fun CopyMangaComic.toManga(fallback: Manga? = null): Manga {
        val localId = checkNotNull(pathWord?.takeIf { it.isNotBlank() } ?: fallback?.id?.value) {
            "CopyManga returned a comic without a path_word."
        }
        check(fallback == null || localId == fallback.id.value) {
            "CopyManga details must preserve the requested manga ID."
        }
        val title = checkNotNull(name?.takeIf { it.isNotBlank() } ?: fallback?.title) {
            "CopyManga returned a comic without a name."
        }
        return Manga(
            id = MangaId(sourceId, localId),
            title = title,
            url = detailsUrl(localId),
            coverUrl = cover?.takeIf { it.isNotBlank() }?.let(::imageUrl) ?: fallback?.coverUrl,
            description = brief ?: fallback?.description,
            authors = author?.mapNotNull { it?.name?.trim()?.takeIf(String::isNotBlank) }?.distinct()
                ?: fallback?.authors.orEmpty(),
        )
    }

    private fun <Result> hasNextPage(
        results: CopyMangaList<Result>,
        offset: Long,
        count: Int,
        requestedLimit: Int,
    ): Boolean {
        check(results.offset == null || results.offset == offset) { "CopyManga returned an unexpected list offset." }
        val total = results.total
        check(total == null || total >= 0) { "CopyManga returned a negative list total." }
        val nextOffset = offset + count
        val hasNext = if (total != null) {
            nextOffset < total
        } else {
            val limit = results.limit?.takeIf { it > 0 } ?: requestedLimit
            count >= limit
        }
        check(!hasNext || count > 0) { "CopyManga returned an empty list before its reported end." }
        return hasNext
    }

    private fun searchOffset(page: Int): Long {
        require(page > 0) { "Search pages start at one." }
        return (page.toLong() - 1) * SEARCH_PAGE_SIZE
    }

    private fun requireSource(mangaId: MangaId) {
        require(mangaId.sourceId == sourceId) { "Manga belongs to another source." }
    }

    private fun requireRequest(request: SourceRequest, expectedUrl: String) {
        require(request.url == expectedUrl && request.method == "GET" && request.body == null) {
            "CopyManga chapter response does not match its request context."
        }
    }

    private fun imageUrl(value: String): String {
        val url = if (value.startsWith("//")) "https:$value" else value
        check(url.startsWith("https://") && url.length > "https://".length) {
            "CopyManga images require an HTTPS URL."
        }
        return url
    }
}

class CopyMangaApiException(
    val httpStatusCode: Int,
    val apiCode: String?,
    message: String,
) : IllegalStateException(message)

private fun encodePathSegment(value: String): String {
    require(value.isNotBlank() && value != "." && value != "..") {
        "CopyManga path segments must not be blank or dot segments."
    }
    return encodeComponent(value)
}

private fun encodeComponent(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code ||
            code == '-'.code || code == '_'.code || code == '.'.code || code == '~'.code
        ) {
            append(code.toChar())
        } else {
            append('%')
            append(hex[code ushr 4])
            append(hex[code and 0x0f])
        }
    }
}
