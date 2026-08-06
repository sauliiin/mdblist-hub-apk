package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.ui.theme.HubColors

/**
 * The full-bleed artwork behind every screen.
 *
 * This is the single most Kodi thing in the app: in Estuary the fanart of
 * whatever is focused fills the screen, dimmed almost to black, and the
 * interface floats over it. It costs nothing to read and makes browsing feel
 * like the library is about films rather than about rows.
 *
 * The crossfade is slow on purpose — focus moves fast on a remote, and a
 * quick swap would strobe.
 */
@Composable
fun FanartBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    scrim: Float = 0.82f,
) {
    Box(modifier.fillMaxSize().background(HubColors.Background)) {
        Crossfade(
            targetState = url,
            animationSpec = tween(durationMillis = 600),
            label = "fanart",
        ) { current ->
            if (current != null) {
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Two gradients rather than a flat overlay: the left edge has to carry
        // white text over whatever the artwork happens to be there, while the
        // bottom has to fade into the rows without a visible seam.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to HubColors.Background.copy(alpha = scrim),
                        0.55f to HubColors.Background.copy(alpha = scrim * 0.78f),
                        1f to HubColors.Background.copy(alpha = scrim * 0.5f),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to HubColors.Background.copy(alpha = 0.35f),
                        0.45f to HubColors.Background.copy(alpha = 0.5f),
                        1f to HubColors.Background,
                    )
                )
        )
    }
}
