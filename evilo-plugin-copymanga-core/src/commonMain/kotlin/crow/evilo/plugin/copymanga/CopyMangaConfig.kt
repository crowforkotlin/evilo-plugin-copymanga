package crow.evilo.plugin.copymanga

data class CopyMangaConfig(
    val apiBaseUrl: String = PRIMARY_API_URL,
    val region: String = "1",
) {
    init {
        require(apiBaseUrl == PRIMARY_API_URL || apiBaseUrl == SECONDARY_API_URL) {
            "CopyManga requires a supported HTTPS API origin."
        }
        require(region == "0" || region == "1") { "CopyManga region must be 0 or 1." }
    }

    companion object {
        const val PRIMARY_API_URL = "https://api.copy202602.com"
        const val SECONDARY_API_URL = "https://api.mangacopy.com"
    }
}
