package com.mdblisthub.tv.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette is the web app's, arranged the way Kodi's Estuary arranges one:
 * a near-black field, one saturated accent for focus, and everything else in
 * greys so the artwork is the only colour on screen.
 */
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

object HubColors {
    var Background by mutableStateOf(Color(0xFF07080C))
    var Surface by mutableStateOf(Color(0xFF121620))
    var SurfaceStrong by mutableStateOf(Color(0xFF1A1F2B))
    var Border by mutableStateOf(Color(0xFF232A38))

    var Accent by mutableStateOf(Color(0xFF7C5CFF))
    var AccentSoft by mutableStateOf(Color(0xFFB6A5FF))
    var Accent2 by mutableStateOf(Color(0xFF12D6C4))

    var Text by mutableStateOf(Color(0xFFF2F4F8))
    var TextDim by mutableStateOf(Color(0xFFA8B0C0))
    var TextFaint by mutableStateOf(Color(0xFF6C7688))

    val Imdb = Color(0xFFF5C518)
    val Fresh = Color(0xFF34D399)
    val Rotten = Color(0xFFF87171)
    val Metacritic = Color(0xFFFFCC33)
    val Trakt = Color(0xFFED1C24)
    val Tmdb = Color(0xFF01B4E4)
    val Letterboxd = Color(0xFF00E054)

    var isCyberpunk by mutableStateOf(false)
    var isNetflixy by mutableStateOf(false)

    fun toggleTheme() {
        if (!isCyberpunk && !isNetflixy) {
            // Normal -> Cyberpunk
            Background = Color(0xFF050014)
            Surface = Color(0xFF14002e)
            SurfaceStrong = Color(0xFF2b005e)
            Border = Color(0xFFff0055)
            Accent = Color(0xFF00f3ff)
            AccentSoft = Color(0xFF99faff)
            Accent2 = Color(0xFFff00aa)
            Text = Color(0xFFfff000)
            TextDim = Color(0xFF00f3ff)
            TextFaint = Color(0xFFff0055)
            isCyberpunk = true
            isNetflixy = false
        } else if (isCyberpunk && !isNetflixy) {
            // Cyberpunk -> Netflixy
            // Netflixy uses dark mode but clean, with maybe a red accent
            Background = Color(0xFF000000)
            Surface = Color(0xFF121212)
            SurfaceStrong = Color(0xFF1F1F1F)
            Border = Color(0xFF333333)
            Accent = Color(0xFFE50914) // Netflix Red
            AccentSoft = Color(0xFFFF5252)
            Accent2 = Color(0xFFE50914)
            Text = Color(0xFFFFFFFF)
            TextDim = Color(0xFFB3B3B3)
            TextFaint = Color(0xFF808080)
            isCyberpunk = false
            isNetflixy = true
        } else {
            // Netflixy -> Normal
            Background = Color(0xFF07080C)
            Surface = Color(0xFF121620)
            SurfaceStrong = Color(0xFF1A1F2B)
            Border = Color(0xFF232A38)
            Accent = Color(0xFF7C5CFF)
            AccentSoft = Color(0xFFB6A5FF)
            Accent2 = Color(0xFF12D6C4)
            Text = Color(0xFFF2F4F8)
            TextDim = Color(0xFFA8B0C0)
            TextFaint = Color(0xFF6C7688)
            isCyberpunk = false
            isNetflixy = false
        }
    }
}
