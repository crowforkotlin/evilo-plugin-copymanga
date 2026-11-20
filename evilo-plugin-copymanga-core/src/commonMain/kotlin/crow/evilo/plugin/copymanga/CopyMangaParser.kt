package crow.evilo.plugin.copymanga

import crow.evilo.model.MediaChapter
import crow.evilo.model.MediaContent
import crow.evilo.model.MediaId
import crow.evilo.model.MediaItem
import crow.evilo.model.MediaKind
import crow.evilo.model.Page
import crow.evilo.plugin.api.SourceResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock

internal const val COPYMANGA_GROUPS = "copymanga.chapter_groups.v1"
internal const val COPYMANGA_ORIGIN = "copymanga.content_origin"
private const val GROUP_CACHE_SECONDS = 300L
internal const val MAX_CHAPTER_GROUPS = 64
internal const val MAX_IMAGE_PAGES = 2000

internal data class CopyMangaCatalogPage(
    val items: List<MediaItem>,
    val count: Int,
    val nextOffset: Long,
    val hasNextPage: Boolean,
    val total: Long?,
)

internal data class CopyMangaChapterBatch(
    val chapters: List<MediaChapter>,
    val nextContext: CopyMangaChapterContext?,
)

/** Carried by the returned detail item, rather than by a process-global manga cache. */
@Serializable
internal data class CopyMangaGroupSnapshot(
    val mangaId: String,
    val origin: String,
    val fetchedAt: Long,
    val groups: List<CopyMangaChapterGroup>,
)

/** Pure response parsing and mapping; HTTP execution remains in evilo. */
internal class CopyMangaParser(
    private val sourceId: String,
    private val config: CopyMangaConfig,
    private val clock: Clock,
) {
    fun catalog(response: SourceResponse, offset: Long, limit: Int): CopyMangaCatalogPage {
        val results = readResults<CopyMangaList<CopyMangaComic>>(response)
        val comics = checkNotNull(results.list) { "CopyManga search results contain no list." }
        val hasNextPage = hasNextPage(results, offset, comics.size, limit)
        val items = comics.map { it.toMedia() }
        check(items.map { it.id }.toSet().size == items.size) { "CopyManga returned duplicate comic IDs in one page." }
        return CopyMangaCatalogPage(items, comics.size, offset + comics.size, hasNextPage, results.total)
    }

    fun details(item: MediaItem, response: SourceResponse): MediaItem {
        val details = readResults<CopyMangaDetails>(response)
        val comic = details.comic ?: missingPayload(response, "CopyManga details contain no comic.")
        val origin = contentOrigin(response)
        val mapped = comic.toMedia(item, origin)
        val extensions = mapped.extensions.toMutableMap()
        // A fresh response without groups must not reuse an earlier discovery snapshot.
        extensions.remove(COPYMANGA_GROUPS)
        val detailGroups = details.groups?.takeIf { it.isNotEmpty() } ?: comic.groups ?: details.groups
        detailGroups?.let {
            val snapshot = CopyMangaGroupSnapshot(item.id.value, origin, clock.now().epochSeconds, groups(it))
            extensions[COPYMANGA_GROUPS] = CopyMangaJson.encodeToString(snapshot)
        }
        put(extensions, "is_lock", details.isLock?.toString())
        put(extensions, "is_login", details.isLogin?.toString())
        put(extensions, "is_vip", details.isVip?.toString())
        put(extensions, "is_mobile_bind", details.isMobileBind?.toString())
        put(extensions, "popular", details.popular?.toString())
        extensions[COPYMANGA_ORIGIN] = origin
        return mapped.copy(extensions = extensions)
    }

    fun discover(mangaId: String, response: SourceResponse): List<CopyMangaChapterGroup> {
        val details = readResults<CopyMangaDetails>(response)
        val comic = details.comic ?: missingPayload(response, "CopyManga chapter discovery contains no comic.")
        check(comic.pathWord.isNullOrBlank() || comic.pathWord == mangaId) {
            "CopyManga chapter discovery must preserve the requested manga ID."
        }
        val discovered = details.groups?.takeIf { it.isNotEmpty() } ?: comic.groups ?: details.groups
        return groups(discovered ?: missingPayload(response, "CopyManga chapter discovery contains no groups."))
    }

    /** Expired or incompatible optional snapshots cause a fresh discovery request. */
    fun cachedGroups(item: MediaItem): CopyMangaGroupSnapshot? {
        val encoded = item.extensions[COPYMANGA_GROUPS] ?: return null
        if (encoded.length > 64 * 1024) return null
        val snapshot = try {
            CopyMangaJson.decodeFromString<CopyMangaGroupSnapshot>(encoded)
        } catch (_: SerializationException) {
            // Optional data may come from an older plugin; discover from the API instead.
            return null
        }
        val now = clock.now().epochSeconds
        if (snapshot.mangaId != item.id.value || snapshot.origin !in CopyMangaConfig.CONTENT_BASE_URLS ||
            snapshot.fetchedAt < 0 || snapshot.fetchedAt > now || now - snapshot.fetchedAt >= GROUP_CACHE_SECONDS ||
            snapshot.groups.size > MAX_CHAPTER_GROUPS ||
            snapshot.groups.any { !validId(it.pathWord) || it.name.isBlank() || it.name.length > 512 || (it.count ?: 0) < 0 } ||
            snapshot.groups.map { it.pathWord }.toSet().size != snapshot.groups.size
        ) return null
        return snapshot
    }

    fun chapters(
        mangaId: MediaId,
        context: CopyMangaChapterContext,
        response: SourceResponse,
    ): CopyMangaChapterBatch {
        val results = readResults<CopyMangaList<CopyMangaChapter>>(response)
        val entries = checkNotNull(results.list) { "CopyManga chapter results contain no list." }
        val group = context.groups[context.groupIndex]
        if (context.offset == 0L && entries.isEmpty() && (group.count ?: 0) > 0) {
            missingPayload(response, "CopyManga mirror returned no chapters for non-empty group '${group.pathWord}'.")
        }
        val hasMore = hasNextPage(results, context.offset, entries.size, COPYMANGA_CHAPTER_LIMIT)
        val origin = contentOrigin(response)
        val ids = HashSet<String>(entries.size)
        val chapters = entries.map { entry ->
            check(entry.comicPathWord.isNullOrBlank() || entry.comicPathWord == mangaId.value) {
                "CopyManga returned a chapter belonging to another manga."
            }
            check(entry.groupPathWord.isNullOrBlank() || entry.groupPathWord == group.pathWord) {
                "CopyManga returned a chapter belonging to another group."
            }
            val id = requiredId(entry.uuid, "chapter UUID")
            check(ids.add(id) && id != context.previousLastId) { "CopyManga repeated a chapter while paginating." }
            val name = entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: id
            val extensions = mutableMapOf(COPYMANGA_ORIGIN to origin)
            put(extensions, "group_path_word", group.pathWord)
            put(extensions, "group_name", group.name)
            put(extensions, "name", name)
            put(extensions, "index", entry.index?.toString())
            put(extensions, "ordered", entry.ordered?.toString())
            put(extensions, "size", entry.size?.toString())
            put(extensions, "count", entry.count?.toString())
            put(extensions, "type", entry.type?.toString())
            put(extensions, "prev", entry.prev)
            put(extensions, "next", entry.next)
            put(extensions, "datetime_created", entry.datetimeCreated)
            MediaChapter(
                itemId = mangaId,
                id = id,
                title = (if (context.groups.size > 1) "${group.name}: $name" else name).take(512),
                url = copyMangaPagesUrl(origin, mangaId.value, id),
                number = entry.ordered?.takeIf { it > 0 } ?: entry.index ?: entry.ordered,
                extensions = extensions,
            )
        }
        val nextContext = when {
            hasMore -> context.copy(offset = context.offset + entries.size, previousLastId = chapters.last().id)
            context.groupIndex + 1 < context.groups.size -> context.copy(
                groupIndex = context.groupIndex + 1,
                offset = 0,
                previousLastId = null,
            )
            else -> null
        }
        return CopyMangaChapterBatch(chapters, nextContext)
    }

    fun content(chapter: MediaChapter, response: SourceResponse): MediaContent {
        val results = readResults<CopyMangaPages>(response)
        check(results.isLock != true) {
            "CopyManga chapter ${chapter.id} is locked; authentication or access permission is required."
        }
        check(results.comic?.pathWord.isNullOrBlank() || results.comic?.pathWord == chapter.itemId.value) {
            "CopyManga returned pages belonging to another manga."
        }
        val contents = results.chapter ?: missingPayload(response, "CopyManga page results contain no chapter.")
        check(contents.uuid.isNullOrBlank() || contents.uuid == chapter.id) {
            "CopyManga returned pages belonging to another chapter."
        }
        check(contents.comicPathWord.isNullOrBlank() || contents.comicPathWord == chapter.itemId.value) {
            "CopyManga returned pages belonging to another manga."
        }
        val images = contents.contents ?: missingPayload(response, "CopyManga page results contain no contents.")
        if (images.isEmpty()) missingPayload(response, "CopyManga chapter ${chapter.id} contains no images.")
        check(images.size <= MAX_IMAGE_PAGES) { "CopyManga chapter exceeds evilo's 2000-image limit." }
        check(contents.size == null || contents.size == images.size) {
            "CopyManga chapter size ${contents.size} does not match ${images.size} returned images."
        }
        val order = imageOrder(contents.words.orEmpty(), images.size)
        val pages = List(images.size) { index ->
            val imageIndex = order?.get(index) ?: index
            val url = checkNotNull(images[imageIndex]?.url?.takeIf { it.isNotBlank() }) {
                "CopyManga returned an image without a URL."
            }
            Page(index, copyMangaImageUrl(url), CopyMangaRequests.IMAGE_HEADERS)
        }
        val extensions = mutableMapOf<String, String>()
        put(extensions, "uuid", contents.uuid ?: chapter.id)
        put(extensions, "name", contents.name)
        put(extensions, "size", images.size.toString())
        put(extensions, "index", contents.index?.toString())
        put(extensions, "ordered", contents.ordered?.toString())
        put(extensions, "prev", contents.prev)
        put(extensions, "next", contents.next)
        return MediaContent(images = pages, extensions = extensions)
    }

    private fun groups(values: Map<String, CopyMangaGroup?>): List<CopyMangaChapterGroup> {
        check(values.size <= MAX_CHAPTER_GROUPS) { "CopyManga returned more than 64 chapter groups." }
        return values.map { (key, group) ->
            val pathWord = requiredId(key.takeIf { it.isNotBlank() } ?: group?.pathWord, "chapter group")
            check(group?.pathWord.isNullOrBlank() || group?.pathWord == pathWord) {
                "CopyManga chapter group key does not match its path_word."
            }
            val count = group?.count
            check(count == null || count >= 0) { "CopyManga returned a negative chapter group count." }
            CopyMangaChapterGroup(pathWord, (group?.name?.trim()?.takeIf { it.isNotEmpty() } ?: pathWord).take(512), count)
        }.also { groups ->
            check(groups.map { it.pathWord }.toSet().size == groups.size) { "CopyManga returned duplicate chapter groups." }
        }.sortedWith(compareBy<CopyMangaChapterGroup> { it.pathWord != "default" }.thenBy { it.pathWord })
    }

    private fun CopyMangaComic.toMedia(fallback: MediaItem? = null, origin: String = config.contentBaseUrl): MediaItem {
        val id = requiredId(pathWord?.takeIf { it.isNotBlank() } ?: fallback?.id?.value, "comic path_word")
        check(fallback == null || id == fallback.id.value) { "CopyManga details must preserve the requested manga ID." }
        val title = checkNotNull(name?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.title?.takeIf { it.isNotBlank() }) {
            "CopyManga returned a comic without a name."
        }
        check(title.length <= 512) { "CopyManga comic title exceeds evilo's 512-character limit." }
        val namedAuthors = author?.mapNotNull { it?.name?.trim()?.takeIf { name -> name.isNotEmpty() } }?.distinct().orEmpty()
        val aliasAuthors = authorAlias?.split(',', '，')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct().orEmpty()
        val authors = when {
            namedAuthors.isNotEmpty() -> namedAuthors
            aliasAuthors.isNotEmpty() -> aliasAuthors
            author != null || authorAlias != null -> emptyList()
            else -> fallback?.authors.orEmpty()
        }
        val extensions = fallback?.extensions.orEmpty().toMutableMap()
        put(extensions, "alias", alias)
        put(extensions, "author_alias", authorAlias)
        author?.let { put(extensions, "author", CopyMangaJson.encodeToString(it)) }
        theme?.let { put(extensions, "theme", CopyMangaJson.encodeToString(it)) }
        put(extensions, "popular", popular?.toString())
        put(extensions, "datetime_updated", datetimeUpdated)
        put(extensions, "free_type", freeType?.toString())
        put(extensions, "status", status)
        put(extensions, "region", region)
        put(extensions, "reclass", reclass)
        put(extensions, "last_chapter", lastChapter)
        put(extensions, "last_chapter_id", lastChapterId)
        put(extensions, "last_chapter_name", lastChapterName)
        put(extensions, "males", males)
        put(extensions, "females", females)
        put(extensions, "parodies", parodies)
        put(extensions, "clubs", clubs)
        put(extensions, "channel", channel)
        put(extensions, "b_404", b404)
        put(extensions, "b_hidden", bHidden)
        put(extensions, "ban", ban)
        put(extensions, "close_comment", closeComment)
        put(extensions, "close_roast", closeRoast)
        return MediaItem(
            id = MediaId(sourceId, MediaKind.MANGA, id),
            title = title,
            url = copyMangaDetailsUrl(origin, id),
            coverUrl = cover?.takeIf { it.isNotBlank() }?.let(::copyMangaImageUrl) ?: fallback?.coverUrl,
            description = brief ?: fallback?.description,
            authors = authors,
            tags = theme?.mapNotNull { it?.name?.trim()?.takeIf { name -> name.isNotEmpty() } }?.distinct() ?: fallback?.tags.orEmpty(),
            extensions = extensions,
        )
    }

    private fun <T> hasNextPage(results: CopyMangaList<T>, offset: Long, count: Int, limit: Int): Boolean {
        check(offset >= 0 && offset <= Long.MAX_VALUE - count) { "Invalid CopyManga pagination offset." }
        check(count <= limit) { "CopyManga page exceeds the requested limit." }
        check(results.offset == null || results.offset == offset) { "CopyManga returned an unexpected list offset." }
        check(results.limit == null || results.limit > 0) { "CopyManga returned a non-positive list limit." }
        val total = results.total
        check(total == null || total >= 0) { "CopyManga returned a negative list total." }
        val nextOffset = offset + count
        check(total == null || count == 0 || nextOffset <= total) { "CopyManga list exceeds its reported total." }
        val hasNext = if (total != null) nextOffset < total else count >= minOf(results.limit ?: limit, limit)
        check(!hasNext || count > 0) { "CopyManga returned an empty list before its reported end." }
        return hasNext
    }

    private inline fun <reified T> readResults(response: SourceResponse): T {
        if (response.statusCode !in 200..299) {
            throw CopyMangaApiException(response.statusCode, null, "CopyManga HTTP ${response.statusCode}.",
                retryable = response.statusCode in setOf(403, 404, 408, 421, 425) || response.statusCode in 500..599)
        }
        val envelope = try {
            CopyMangaJson.decodeFromString<CopyMangaEnvelope>(response.body.removePrefix("\uFEFF"))
        } catch (failure: SerializationException) {
            throw CopyMangaApiException(response.statusCode, null, "CopyManga returned an invalid JSON envelope.",
                retryable = true, cause = failure)
        }
        val code = envelope.code?.content?.trim()
        if (code != "200") {
            throw CopyMangaApiException(response.statusCode, code,
                "CopyManga API ${code ?: "unknown"}: ${envelope.message ?: "Request rejected."}")
        }
        val results = envelope.results?.let(CopyMangaJson::unpack)
        if (results == null || results is JsonNull) {
            missingPayload(response, "CopyManga returned an empty payload; details, chapters, and images require a mapi.* mirror.")
        }
        return CopyMangaJson.decodeFromJsonElement(results)
    }

    private fun missingPayload(response: SourceResponse, message: String): Nothing =
        throw CopyMangaApiException(response.statusCode, "200", message, retryable = true)

    private fun contentOrigin(response: SourceResponse): String =
        CopyMangaConfig.CONTENT_BASE_URLS.firstOrNull { response.url == it || response.url.startsWith("$it/") }
            ?: config.contentBaseUrl

    private fun put(values: MutableMap<String, String>, key: String, value: JsonElement?) {
        if (value != null && value !is JsonNull) put(values, key, if (value is JsonPrimitive) value.content else value.toString())
    }

    private fun put(values: MutableMap<String, String>, key: String, value: String?) {
        value?.let { values["copymanga.$key"] = it }
    }
}

/** O(n) for a zero/one-based permutation or an already ordered list; sparse ranks sort. */
private fun imageOrder(words: List<Int>, size: Int): IntArray? {
    if (words.isEmpty()) return null
    check(words.size == size) { "CopyManga image order length does not match contents." }
    var increasing = true
    var minimum = Int.MAX_VALUE
    var maximum = 0
    words.forEachIndexed { index, word ->
        check(word >= 0) { "CopyManga image order contains negative positions." }
        if (index > 0 && word <= words[index - 1]) increasing = false
        minimum = minOf(minimum, word)
        maximum = maxOf(maximum, word)
    }
    if (increasing) return null
    if ((minimum == 0 || minimum == 1) && maximum.toLong() - minimum == size.toLong() - 1) {
        val order = IntArray(size) { -1 }
        words.forEachIndexed { image, word ->
            val position = word - minimum
            check(order[position] == -1) { "CopyManga image order contains duplicate positions." }
            order[position] = image
        }
        return order
    }
    check(words.toSet().size == size) { "CopyManga image order contains duplicate positions." }
    return words.indices.sortedBy { words[it] }.toIntArray()
}

private fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 512 && value != "." && value != ".." &&
    value.none { it < ' ' || it == '\u007f' }

private fun requiredId(value: String?, label: String): String {
    check(value != null && validId(value)) { "CopyManga returned an invalid $label." }
    return value
}
