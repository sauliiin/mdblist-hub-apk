package com.mdblisthub.tv.core.model

/**
 * TMDB image URLs.
 *
 * The sizes are picked for a 1080p television, which is the whole point of
 * choosing them centrally: a `original` backdrop is 3–8 MB and no TV surface
 * paints more than 1280 px of it, so asking for one is pure decode cost on a
 * box with a weak SoC.
 */
object TmdbImages {
    const val BASE = "https://image.tmdb.org/t/p"

    const val POSTER_CARD = "w342"
    const val POSTER_LARGE = "w500"
    const val BACKDROP_FANART = "w1280"
    const val STILL = "w300"
    const val PROFILE = "w185"
    const val LOGO = "w500"

    fun url(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$BASE/$size${if (it.startsWith("/")) it else "/$it"}" }

    /**
     * mdblist hands out posters already sized at `w200`, which is visibly soft
     * on a 10-foot card. Rewriting the size segment buys a sharper file for
     * free, since the CDN serves every size off the same path.
     */
    fun upscale(url: String?, size: String = POSTER_CARD): String? {
        if (url.isNullOrBlank()) return null
        return Regex("/t/p/w\\d+/").replace(url, "/t/p/$size/")
    }
}
