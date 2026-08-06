package com.mdblisthub.tv.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette is the web app's, arranged the way Kodi's Estuary arranges one:
 * a near-black field, one saturated accent for focus, and everything else in
 * greys so the artwork is the only colour on screen.
 */
object HubColors {
    val Background = Color(0xFF07080C)
    val Surface = Color(0xFF121620)
    val SurfaceStrong = Color(0xFF1A1F2B)
    val Border = Color(0xFF232A38)

    /** Focus. In a 10-foot UI this is the most important colour there is. */
    val Accent = Color(0xFF7C5CFF)
    val AccentSoft = Color(0xFFB6A5FF)
    val Accent2 = Color(0xFF12D6C4)

    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFFA8B0C0)
    val TextFaint = Color(0xFF6C7688)

    val Imdb = Color(0xFFF5C518)
    val Fresh = Color(0xFF34D399)
    val Rotten = Color(0xFFF87171)
    val Metacritic = Color(0xFFFFCC33)
    val Trakt = Color(0xFFED1C24)
    val Tmdb = Color(0xFF01B4E4)
    val Letterboxd = Color(0xFF00E054)
}
