package crow.evilo.plugin.copymanga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * CopyManga's uniform response envelope.
 *
 * `code` arrives as either `200` or `"200"` depending on the route, and `results` is
 * `null` on hosts that do not serve the requested resource.
 */
@Serializable
internal data class CopyMangaEnvelope(
    val code: JsonPrimitive? = null,
    val message: String? = null,
    val results: JsonElement? = null,
)

/**
 * CopyManga's paginated `results` object.
 *
 * Pagination numbers accept numeric strings, and `list` may itself be JSON encoded.
 */
@Serializable
internal data class CopyMangaList<Result>(
    @Serializable(with = LenientList::class) val list: List<Result>? = null,
    @Serializable(with = LenientLong::class) val offset: Long? = null,
    @Serializable(with = LenientInt::class) val limit: Int? = null,
    @Serializable(with = LenientLong::class) val total: Long? = null,
)

/**
 * Comic fields used by list, search, and details responses.
 *
 * Search omits update times and themes. Missing optional fields stay absent so that
 * a detail response can retain values already supplied by the catalog.
 */
@Serializable
internal data class CopyMangaComic(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val brief: String? = null,
    @Serializable(with = LenientList::class) val author: List<CopyMangaAuthor?>? = null,
    @SerialName("author_alias") val authorAlias: String? = null,
    @Serializable(with = LenientList::class) val theme: List<CopyMangaTag?>? = null,
    val groups: Map<String, CopyMangaGroup?>? = null,
    val alias: JsonElement? = null,
    @Serializable(with = LenientLong::class) val popular: Long? = null,
    @SerialName("datetime_updated") val datetimeUpdated: String? = null,
    @SerialName("free_type") @Serializable(with = LenientInt::class) val freeType: Int? = null,
    val status: JsonElement? = null,
    val region: JsonElement? = null,
    val reclass: JsonElement? = null,
    @SerialName("last_chapter") val lastChapter: JsonElement? = null,
    @SerialName("last_chapter_id") val lastChapterId: String? = null,
    @SerialName("last_chapter_name") val lastChapterName: String? = null,
    val males: JsonElement? = null,
    val females: JsonElement? = null,
    val parodies: JsonElement? = null,
    val clubs: JsonElement? = null,
    val channel: JsonElement? = null,
    @SerialName("b_404") val b404: JsonElement? = null,
    @SerialName("b_hidden") val bHidden: JsonElement? = null,
    val ban: JsonElement? = null,
    @SerialName("close_comment") val closeComment: JsonElement? = null,
    @SerialName("close_roast") val closeRoast: JsonElement? = null,
)

/** One element of `comic.author`; `name` may be blank or absent. */
@Serializable
internal data class CopyMangaAuthor(
    val name: String? = null,
    @SerialName("path_word") val pathWord: String? = null,
)

@Serializable
internal data class CopyMangaTag(
    val name: String? = null,
    @SerialName("path_word") val pathWord: String? = null,
)

/**
 * `results` of `/api/v3/comic2/{pathWord}`.
 *
 * Only a mirror host fills `comic`; list hosts answer `code: 200` with `results: null`,
 * which is a routing failure rather than a missing comic.
 */
@Serializable
internal data class CopyMangaDetails(
    val comic: CopyMangaComic? = null,
    val groups: Map<String, CopyMangaGroup?>? = null,
    @Serializable(with = LenientLong::class) val popular: Long? = null,
    @SerialName("is_lock") @Serializable(with = LenientBoolean::class) val isLock: Boolean? = null,
    @SerialName("is_login") @Serializable(with = LenientBoolean::class) val isLogin: Boolean? = null,
    @SerialName("is_vip") @Serializable(with = LenientBoolean::class) val isVip: Boolean? = null,
    @SerialName("is_mobile_bind") @Serializable(with = LenientBoolean::class) val isMobileBind: Boolean? = null,
)

/** One chapter group from `details.groups`, keyed by its `path_word`. */
@Serializable
internal data class CopyMangaGroup(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
    @Serializable(with = LenientLong::class) val count: Long? = null,
)

/** One chapter entry from `/api/v3/comic/{pathWord}/group/{group}/chapters`. */
@Serializable
internal data class CopyMangaChapter(
    val uuid: String? = null,
    val name: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    @SerialName("group_path_word") val groupPathWord: String? = null,
    @Serializable(with = LenientDouble::class) val ordered: Double? = null,
    @Serializable(with = LenientDouble::class) val index: Double? = null,
    @Serializable(with = LenientInt::class) val size: Int? = null,
    @Serializable(with = LenientInt::class) val count: Int? = null,
    @Serializable(with = LenientInt::class) val type: Int? = null,
    val prev: String? = null,
    val next: String? = null,
    @SerialName("datetime_created") val datetimeCreated: String? = null,
)

/**
 * `results` of `/api/v3/comic/{pathWord}/chapter/{uuid}`.
 *
 * `is_lock` is served at this level, while `uuid`, `words`, and `contents` live under
 * `chapter`.
 */
@Serializable
internal data class CopyMangaPages(
    val chapter: CopyMangaChapterContents? = null,
    val comic: CopyMangaComic? = null,
    @SerialName("is_lock") @Serializable(with = LenientBoolean::class) val isLock: Boolean? = null,
)

/**
 * The `chapter` object of a chapter-images response.
 *
 * Empty or absent `words` preserves the returned image order. `contents` must contain
 * at least one usable image; an empty payload is never a readable chapter.
 */
@Serializable
internal data class CopyMangaChapterContents(
    val uuid: String? = null,
    val name: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    @Serializable(with = LenientList::class) val contents: List<CopyMangaImage?>? = null,
    @Serializable(with = LenientPositions::class) val words: List<Int>? = null,
    @Serializable(with = LenientInt::class) val size: Int? = null,
    @Serializable(with = LenientDouble::class) val index: Double? = null,
    @Serializable(with = LenientDouble::class) val ordered: Double? = null,
    val prev: String? = null,
    val next: String? = null,
)

/** One page image; only `url` is consumed. */
@Serializable
internal data class CopyMangaImage(
    val url: String? = null,
)

/** A resolved chapter group carried inside [CopyMangaChapterContext]. */
@Serializable
internal data class CopyMangaChapterGroup(
    val pathWord: String,
    val name: String,
    val count: Long? = null,
)

/**
 * Opaque pagination state for chapter discovery.
 *
 * It is serialized into [crow.evilo.plugin.api.SourceChapterRequest.context], so it never
 * travels over HTTP and concurrent manga can paginate independently.
 */
@Serializable
internal data class CopyMangaChapterContext(
    val mangaId: String,
    val groups: List<CopyMangaChapterGroup>,
    val groupIndex: Int = 0,
    val offset: Long = 0,
    val previousLastId: String? = null,
)
