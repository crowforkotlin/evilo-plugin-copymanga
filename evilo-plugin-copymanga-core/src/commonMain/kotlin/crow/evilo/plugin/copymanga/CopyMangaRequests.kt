package crow.evilo.plugin.copymanga

import crow.evilo.plugin.api.SourceRequest
import kotlin.time.Clock

internal const val COPYMANGA_SEARCH_LIMIT = 30
internal const val COPYMANGA_CATALOG_LIMIT = 50
internal const val COPYMANGA_CHAPTER_LIMIT = 500

/** Reuses immutable headers; only authentication timestamps are per-request. */
internal class CopyMangaRequests(private val clock: Clock) {
    fun get(url: String): SourceRequest {
        require(url.length <= 4096) { "CopyManga request URL exceeds evilo's 4096-character limit." }
        val mirror = CopyMangaConfig.CONTENT_BASE_URLS.any { url.startsWith("$it/") }
        return SourceRequest(url, headers = (if (mirror) MIRROR_HEADERS else LIST_HEADERS) +
            CopyMangaSigner.authHeaders(clock.now().epochSeconds))
    }

    companion object {
        val IMAGE_HEADERS: Map<String, String> = mapOf("User-Agent" to CopyMangaSigner.USER_AGENT)
        val API_HEADER_NAMES: Set<String> = setOf(
            "user-agent", "accept", "platform", "version", "webp", "referer", "umstring",
            "x-requested-with", "x-auth-timestamp", "tapstring", "x-auth-signature",
        )

        private val COMMON_HEADERS = mapOf(
            "Accept" to "*/*",
            "platform" to CopyMangaSigner.PLATFORM,
            "version" to CopyMangaSigner.CLIENT_VERSION,
            "webp" to CopyMangaSigner.WEBP,
        )
        private val LIST_HEADERS = COMMON_HEADERS + mapOf(
            "User-Agent" to CopyMangaSigner.USER_AGENT,
            "referer" to CopyMangaSigner.REFERER,
            "umstring" to CopyMangaSigner.UMSTRING,
        )
        private val MIRROR_HEADERS = COMMON_HEADERS + mapOf(
            "User-Agent" to CopyMangaSigner.MIRROR_USER_AGENT,
            "X-Requested-With" to CopyMangaSigner.REQUESTED_WITH,
        )
    }
}

internal fun copyMangaSearchPath(query: String, offset: Long, limit: Int): String {
    require(offset >= 0 && limit > 0) { "Invalid CopyManga search pagination." }
    val path = if (query.isBlank()) {
        "/api/v3/comics?free_type=1&ordering=-datetime_updated"
    } else {
        "/api/v3/search/comic?q=${encodeCopyMangaComponent(query)}&q_type="
    }
    return "$path&offset=$offset&limit=$limit&platform=${CopyMangaSigner.PLATFORM}"
}

internal fun copyMangaDetailsPath(mangaId: String): String =
    "/api/v3/comic2/${encodeCopyMangaPath(mangaId)}?platform=${CopyMangaSigner.PLATFORM}"

internal fun copyMangaChaptersPath(context: CopyMangaChapterContext): String =
    "/api/v3/comic/${encodeCopyMangaPath(context.mangaId)}/group/" +
        "${encodeCopyMangaPath(context.groups[context.groupIndex].pathWord)}/chapters" +
        "?limit=$COPYMANGA_CHAPTER_LIMIT&offset=${context.offset}&platform=${CopyMangaSigner.PLATFORM}"

internal fun copyMangaPagesPath(mangaId: String, chapterId: String): String =
    "/api/v3/comic/${encodeCopyMangaPath(mangaId)}/chapter/${encodeCopyMangaPath(chapterId)}" +
        "?platform=${CopyMangaSigner.PLATFORM}"

internal fun copyMangaDetailsUrl(origin: String, mangaId: String): String = origin + copyMangaDetailsPath(mangaId)
internal fun copyMangaPagesUrl(origin: String, mangaId: String, chapterId: String): String = origin + copyMangaPagesPath(mangaId, chapterId)

internal fun copyMangaImageUrl(value: String): String {
    val trimmed = value.trim()
    val url = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    check(url.length <= 4096 && url.startsWith("https://") &&
        url.none { it <= ' ' || it == '\u007f' || it == '\\' || it == '#' }
    ) { "CopyManga images require a valid HTTPS URL." }
    val origin = "https://" + url.removePrefix("https://").substringBefore('/').substringBefore('?')
    check(origin in CopyMangaConfig.MEDIA_BASE_URLS) { "CopyManga returned an unapproved image origin: $origin." }
    check(url.startsWith("$origin/") && url.length > origin.length + 1) { "CopyManga returned an image without a path." }
    return url
}

internal fun encodeCopyMangaPath(value: String): String {
    require(value.isNotBlank() && value.length <= 512 && value != "." && value != ".." &&
        value.none { it < ' ' || it == '\u007f' }
    ) { "CopyManga path segments must be non-empty IDs, without control characters or dot segments." }
    return encodeCopyMangaComponent(value)
}

internal fun encodeCopyMangaComponent(value: String): String = buildString {
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
