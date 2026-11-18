package crow.evilo.plugin.copymanga

/**
 * Selects the CopyManga endpoints this source talks to.
 *
 * CopyManga splits its API across two host families and the split is server-side, not
 * request-side: list and search responses only exist on the `api.*` hosts, while comic
 * details, chapter lists, and chapter images only exist on the `mapi.*` mirrors. The
 * list hosts answer `code: 200` with `results: null` for `/api/v3/comic2/{pathWord}`,
 * so a single-host configuration cannot serve this provider.
 *
 * Both URLs are validated against the reviewed host allowlists so a misconfigured value
 * cannot silently point this provider at an unreviewed origin. The host imposes no
 * separate origin policy.
 *
 * @property listBaseUrl origin used for `/api/v3/comics` and `/api/v3/search/comic`.
 * @property contentBaseUrl origin used for comic details, chapter lists, and chapter images.
 */
data class CopyMangaConfig(
    val listBaseUrl: String = PRIMARY_LIST_URL,
    val contentBaseUrl: String = PRIMARY_CONTENT_URL,
) {
    init {
        require(listBaseUrl in LIST_BASE_URLS) { "CopyManga list requests require a supported HTTPS list origin." }
        require(contentBaseUrl in CONTENT_BASE_URLS) {
            "CopyManga details, chapters, and pages require a supported HTTPS mirror origin."
        }
    }

    /** Reviewed CopyManga origins this provider is willing to call. */
    companion object {
        /** Primary list origin reported by `/api/v3/system/network2`. */
        const val PRIMARY_LIST_URL = "https://api.copy202601.com"

        /** First fallback list origin; identical catalog contents. */
        const val SECONDARY_LIST_URL = "https://api.copy202602.com"

        /** Primary mirror; the only host family that serves details, chapters, and images. */
        const val PRIMARY_CONTENT_URL = "https://mapi.hotmangasd.com"

        /** First fallback mirror with identical contents. */
        const val SECONDARY_CONTENT_URL = "https://mapi.hotmangasg.com"

        /** Every list origin verified to serve `/api/v3/comics` and `/api/v3/search/comic`. */
        val LIST_BASE_URLS: Set<String> = setOf(
            PRIMARY_LIST_URL,
            SECONDARY_LIST_URL,
            "https://api.copy-manga.com",
            "https://api.copy2000.online",
            "https://api.mangacopy.com",
        )

        /** Every mirror verified to serve `/api/v3/comic2/{pathWord}` and chapter endpoints. */
        val CONTENT_BASE_URLS: Set<String> = setOf(
            PRIMARY_CONTENT_URL,
            SECONDARY_CONTENT_URL,
            "https://mapi.hotmangasf.com",
            "https://mapi.elfgjfghkk.club",
            "https://mapi.fgjfghkkcenter.club",
            "https://mapi.fgjfghkk.club",
        )

        /** Keep the returned CDN host and path together; substituting `s3` breaks images. */
        val MEDIA_BASE_URLS: Set<String> = buildSet {
            add("https://s3.mangafunb.fun")
            add("https://s0.mangafunb.fun")
            for (letter in 'a'..'z') add("https://s$letter.mangafunb.fun")
        }
    }
}
