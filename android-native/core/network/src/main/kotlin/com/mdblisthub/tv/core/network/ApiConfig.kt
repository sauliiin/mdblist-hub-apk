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

    const val TMDB_KEY = "703cf5598b9fd74adac824baf7923126"
    const val OMDB_KEY = "b2f2fcca"

    /** Metadata language, with an English fallback wherever TMDB supports one. */
    const val LANGUAGE = "pt-BR"

    /**
     * Signing in with this account unlocks the curated home — the hand-picked
     * lists renamed to Portuguese. Every other account sees all of its own.
     */
    const val OWNER_USERNAME = "mestreyodarossi"

    const val USER_AGENT = "mdblist-hub-tv/0.1 (Android TV)"
}
