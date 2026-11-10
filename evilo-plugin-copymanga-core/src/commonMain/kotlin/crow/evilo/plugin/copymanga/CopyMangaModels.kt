package crow.evilo.plugin.copymanga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class CopyMangaEnvelope(
    val code: JsonPrimitive? = null,
    val message: String? = null,
    val results: JsonElement? = null,
)

@Serializable
internal data class CopyMangaList<Result>(
    val list: List<Result>? = null,
    val offset: Long? = null,
    val limit: Int? = null,
    val total: Long? = null,
)

@Serializable
internal data class CopyMangaComic(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val brief: String? = null,
    val author: List<CopyMangaAuthor?>? = null,
)

@Serializable
internal data class CopyMangaAuthor(
    val name: String? = null,
)

@Serializable
internal data class CopyMangaDetails(
    val comic: CopyMangaComic? = null,
    val groups: Map<String, CopyMangaGroup?>? = null,
)

@Serializable
internal data class CopyMangaGroup(
    @SerialName("path_word") val pathWord: String? = null,
    val name: String? = null,
)

@Serializable
internal data class CopyMangaChapter(
    val uuid: String? = null,
    val name: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    val ordered: Double? = null,
    val index: Double? = null,
)

@Serializable
internal data class CopyMangaPages(
    val chapter: CopyMangaChapterContents? = null,
    val comic: CopyMangaComic? = null,
    @SerialName("is_lock") val isLock: Boolean? = null,
)

@Serializable
internal data class CopyMangaChapterContents(
    val uuid: String? = null,
    @SerialName("comic_path_word") val comicPathWord: String? = null,
    val contents: List<CopyMangaImage?>? = null,
    val words: List<Int>? = null,
)

@Serializable
internal data class CopyMangaImage(
    val url: String? = null,
)

@Serializable
internal data class CopyMangaChapterGroup(
    val pathWord: String,
    val name: String,
)

@Serializable
internal data class CopyMangaChapterContext(
    val mangaId: String,
    val groups: List<CopyMangaChapterGroup>,
    val groupIndex: Int = 0,
    val offset: Long = 0,
)
