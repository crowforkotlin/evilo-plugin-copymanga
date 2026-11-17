@file:OptIn(ExperimentalSerializationApi::class)

package crow.evilo.plugin.copymanga

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * `offset`, `limit`, and `total` are decoded through [LenientNumber] because the same
 * route can answer with JSON numbers or numeric strings.
 */
@Serializable
internal data class CopyMangaList<Result>(
    val list: List<Result>? = null,
    @Serializable(with = LenientLong::class) val offset: Long? = null,
    @Serializable(with = LenientInt::class) val limit: Int? = null,
    @Serializable(with = LenientLong::class) val total: Long? = null,
)

/**
 * Comic fields used by list, search, and details responses.
 *
 * Search omits `brief`; list omits nothing that this source reads. `path_word` is the
 * only stable identifier across hosts, so it becomes the local manga ID.
 */
@Serializable
internal data class CopyMangaComic(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val brief: String? = null,
    val author: List<CopyMangaAuthor?>? = null,
)

/** One element of `comic.author`; `name` may be blank or absent. */
@Serializable
internal data class CopyMangaAuthor(
    val name: String? = null,
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
)

/** One chapter group from `details.groups`, keyed by its `path_word`. */
@Serializable
internal data class CopyMangaGroup(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
)

/** One chapter entry from `/api/v3/comic/{pathWord}/group/{group}/chapters`. */
@Serializable
internal data class CopyMangaChapter(
    val uuid: String? = null,
    val name: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    val ordered: Double? = null,
    val index: Double? = null,
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
    @SerialName("is_lock") val isLock: Boolean? = null,
)

/**
 * The `chapter` object of a chapter-images response.
 *
 * `words` reorders `contents`; both are absent or empty for guest-readable chapters,
 * in which case the returned order is already correct.
 */
@Serializable
internal data class CopyMangaChapterContents(
    val uuid: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    val contents: List<CopyMangaImage?>? = null,
    val words: List<Int>? = null,
    @Serializable(with = LenientInt::class) val size: Int? = null,
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
)

/** Decodes a JSON number or numeric string into a `Long`; absent values stay `null`. */
internal object LenientLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("crow.evilo.plugin.copymanga.LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? = decodeLenient(decoder) { it.toLongOrNull() }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/** Decodes a JSON number or numeric string into an `Int`; absent values stay `null`. */
internal object LenientInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("crow.evilo.plugin.copymanga.LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? = decodeLenient(decoder) { it.toIntOrNull() }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/**
 * Reads the current JSON element as a number.
 *
 * A present but non-numeric value is a protocol violation and fails loudly instead of
 * degrading into a `null` that would silently disable pagination checks.
 */
private inline fun <T> decodeLenient(decoder: Decoder, parse: (String) -> T?): T? {
    val json = decoder as? JsonDecoder ?: return null
    val element = json.decodeJsonElement()
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return parse(primitive.content.trim())
        ?: throw SerializationException("Expected a numeric CopyManga value but was '${primitive.content}'.")
}
