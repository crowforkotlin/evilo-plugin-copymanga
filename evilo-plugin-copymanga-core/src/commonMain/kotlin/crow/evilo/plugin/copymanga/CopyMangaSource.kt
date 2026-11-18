package crow.evilo.plugin.copymanga

import crow.evilo.model.Chapter
import crow.evilo.model.Manga
import crow.evilo.model.MangaId
import crow.evilo.model.MediaChapter
import crow.evilo.model.MediaId
import crow.evilo.model.MediaItem
import crow.evilo.model.MediaKind
import crow.evilo.model.Page
import crow.evilo.plugin.api.MangaSearchResult
import crow.evilo.plugin.api.MangaSourceService
import crow.evilo.plugin.api.SOURCE_API_VERSION
import crow.evilo.plugin.api.SourceChapterRequest
import crow.evilo.plugin.api.SourceChapterResult
import crow.evilo.plugin.api.SourceMetadata
import crow.evilo.plugin.api.SourceRequest
import crow.evilo.plugin.api.SourceResponse
import kotlin.time.Clock

private const val LEGACY_SEARCH_PAGE_SIZE = 20

/** API 2 compatibility wrapper; new WASI releases use [CopyMangaPlugin] directly. */
class CopyMangaSource(
    private val sourceId: String,
    private val sourceVersion: String,
    private val config: CopyMangaConfig = CopyMangaConfig(),
    clock: Clock = Clock.System,
) : MangaSourceService {
    init {
        require(sourceId.isNotBlank() && sourceVersion.isNotBlank()) { "Source identity comes from the guest release descriptor." }
    }

    private val parser = CopyMangaParser(sourceId, config, clock)
    private val requests = CopyMangaRequests(clock)

    override fun metadata(): SourceMetadata = SourceMetadata(sourceId, "CopyManga", "zh", sourceVersion, SOURCE_API_VERSION)

    override fun searchRequest(query: String, page: Int): SourceRequest = requests.get(
        config.listBaseUrl + copyMangaSearchPath(query, searchOffset(page), LEGACY_SEARCH_PAGE_SIZE),
    )

    override fun parseSearch(query: String, page: Int, response: SourceResponse): MangaSearchResult {
        val result = parser.catalog(response, searchOffset(page), LEGACY_SEARCH_PAGE_SIZE)
        check(!result.hasNextPage || result.count == LEGACY_SEARCH_PAGE_SIZE) {
            "CopyManga returned an incomplete search page before its reported end."
        }
        return MangaSearchResult(result.items.map { it.toManga() }, result.hasNextPage)
    }

    override fun detailsRequest(manga: Manga): SourceRequest {
        requireSource(manga.id)
        return requests.get(copyMangaDetailsUrl(config.contentBaseUrl, manga.id.value))
    }

    override fun parseDetails(manga: Manga, response: SourceResponse): Manga {
        requireSource(manga.id)
        return parser.details(manga.toMedia(), response).toManga()
    }

    override fun chaptersRequest(manga: Manga): SourceChapterRequest = SourceChapterRequest(detailsRequest(manga))

    override fun parseChapters(manga: Manga, request: SourceChapterRequest, response: SourceResponse): SourceChapterResult {
        requireSource(manga.id)
        val encoded = request.context
        if (encoded == null) {
            requireRequest(request.request, copyMangaDetailsUrl(config.contentBaseUrl, manga.id.value))
            val groups = parser.discover(manga.id.value, response)
            return SourceChapterResult(nextRequest = groups.takeIf { it.isNotEmpty() }?.let {
                chapterRequest(CopyMangaChapterContext(manga.id.value, it))
            })
        }
        val context = CopyMangaJson.decodeFromString<CopyMangaChapterContext>(encoded)
        require(context.mangaId == manga.id.value && context.groupIndex in context.groups.indices && context.offset >= 0) {
            "Invalid CopyManga chapter request context."
        }
        requireRequest(request.request, config.contentBaseUrl + copyMangaChaptersPath(context))
        val result = parser.chapters(MediaId(sourceId, MediaKind.MANGA, manga.id.value), context, response)
        return SourceChapterResult(
            chapters = result.chapters.map { it.toChapter() },
            nextRequest = result.nextContext?.let(::chapterRequest),
        )
    }

    override fun pagesRequest(chapter: Chapter): SourceRequest {
        requireSource(chapter.mangaId)
        return requests.get(copyMangaPagesUrl(config.contentBaseUrl, chapter.mangaId.value, chapter.id))
    }

    override fun parsePages(chapter: Chapter, response: SourceResponse): List<Page> {
        requireSource(chapter.mangaId)
        encodeCopyMangaPath(chapter.id)
        return parser.content(chapter.toMedia(), response).images
    }

    private fun chapterRequest(context: CopyMangaChapterContext): SourceChapterRequest = SourceChapterRequest(
        requests.get(config.contentBaseUrl + copyMangaChaptersPath(context)), CopyMangaJson.encodeToString(context),
    )

    private fun searchOffset(page: Int): Long {
        require(page > 0) { "Search pages start at one." }
        return (page.toLong() - 1) * LEGACY_SEARCH_PAGE_SIZE
    }

    private fun requireSource(id: MangaId) {
        require(id.sourceId == sourceId) { "Manga belongs to another source." }
        encodeCopyMangaPath(id.value)
    }

    private fun requireRequest(request: SourceRequest, expectedUrl: String) {
        require(request.url == expectedUrl && request.method == "GET" && request.body == null) {
            "CopyManga chapter response does not match its request context."
        }
    }

    private fun Manga.toMedia() = MediaItem(MediaId(id.sourceId, MediaKind.MANGA, id.value), title, url, coverUrl, description, authors)
    private fun MediaItem.toManga() = Manga(MangaId(id.pluginId, id.value), title, url, coverUrl, description, authors)
    private fun Chapter.toMedia() = MediaChapter(MediaId(mangaId.sourceId, MediaKind.MANGA, mangaId.value), id, title, url, number)
    private fun MediaChapter.toChapter() = Chapter(MangaId(itemId.pluginId, itemId.value), id, title, url, number)
}

/** Keeps the HTTP status, provider code, and retry decision separate. */
class CopyMangaApiException(
    val httpStatusCode: Int,
    val apiCode: String?,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
