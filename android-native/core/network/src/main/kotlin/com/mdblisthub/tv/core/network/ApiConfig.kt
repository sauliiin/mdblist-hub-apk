package com.mdblisthub.tv.core.network

/**
 * Every external service the app talks to.
 *
 * The mdblist key is absent on purpose: it belongs to whoever signed in and
 * lives in the session store, never in the binary. The TMDB and OMDb keys are
 * the app's own and are the same ones the web build ships.
 */
object ApiConfig {
    const val MDBLIST_BASE = "https://api.mdblist.com/"
    const val TMDB_BASE = "https://api.themoviedb.org/3/"
    const val OMDB_BASE = "https://www.omdbapi.com/"

    /**
     * Realtime Database over its REST interface. Addons are keyed by the
     * SHA-256 of the mdblist API key, matching what `AddonSyncService` writes
     * from the web build — see [com.mdblisthub.tv.core.data.SyncTokens].
     */
    const val FIREBASE_BASE = "https://alien-bruin-339920-default-rtdb.firebaseio.com/"
    const val FIREBASE_ROOT = "mdblist-hub/addons"

    /** IMDb's own (undocumented) GraphQL API — see [ImdbApi] for why it's worth using. */
    const val IMDB_GRAPHQL_BASE = "https://graphql.prod.api.imdb.a2z.com/"

    const val TMDB_KEY = "703cf5598b9fd74adac824baf7923126"
    const val OMDB_KEY = "b2f2fcca"

    /**
     * OpenSubtitles.com's own API — a different service from the "OpenSubtitles
     * v3" Stremio addon of a similar name. Queried directly for [SubtitleMatcher]:
     * the Stremio subtitle protocol only guarantees `id`/`url`/`lang`, so a
     * subtitle from an addon rarely carries the release name a good automatic
     * match needs, where this API returns one for every result.
     *
     * Key and user agent are a personal OpenSubtitles.com API registration,
     * not this project's — used here with the account owner's permission,
     * on the same 100-download/day quota their own tools already share.
     */
    const val OPENSUBTITLES_BASE = "https://api.opensubtitles.com/api/v1/"
    const val OPENSUBTITLES_API_KEY = "9eBRI85k0K0D7teGENPWBhCrCH4jnsLF"
    const val OPENSUBTITLES_USER_AGENT = "mestreyoddarossi api for kodi"

    /**
     * Wyzie's own subtitle search — a second, independent source queried for
     * the same reason as OpenSubtitles.com above: a release name per result.
     * Its `language` query param takes exactly one code at a time in
     * practice — asking for `pb,pt` together has been observed to silently
     * drop every `pb` (Brazilian Portuguese) result, which is why
     * `StreamsRepository.wyzieSearch` queries each language on its own
     * rather than trusting a combined list.
     *
     * Key is a personal Wyzie registration, not this project's — used here
     * with the account owner's permission.
     */
    const val WYZIE_BASE = "https://sub.wyzie.io/"
    const val WYZIE_API_KEY = "wyzie-s9qb8pabb1bllkptwqe0z19ufdnpa5sa"

    /** Metadata language, with an English fallback wherever TMDB supports one. */
    const val LANGUAGE = "pt-BR"

    /**
     * Signing in with this account unlocks the curated home — the hand-picked
     * lists renamed to Portuguese. Every other account sees all of its own.
     */
    const val OWNER_USERNAME = "mestreyodarossi"

    const val USER_AGENT = "mdblist-hub-tv/0.1 (Android TV)"
}
