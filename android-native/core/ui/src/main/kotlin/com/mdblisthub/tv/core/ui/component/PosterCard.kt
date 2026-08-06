package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens

/**
 * One title in a row.
 *
 * Focus is expressed three ways at once — scale, an accent border and a
 * brightening title — because on a television the viewer is metres away and
 * any single cue is easy to lose. Kodi's skins do the same thing for the same
 * reason.
 */
@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (MediaItem) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.09f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "poster-scale",
    )

    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) onFocused(item)
    }

    Column(
        modifier = modifier.width(HubDimens.PosterWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .scale(scale)
                .width(HubDimens.PosterWidth)
                .height(HubDimens.PosterHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(HubColors.Surface)
                .border(
                    width = if (focused) 2.5.dp else 0.dp,
                    color = if (focused) HubColors.Accent else HubColors.Border,
                    shape = RoundedCornerShape(10.dp),
                )
                // `clickable` is what makes it focusable *and* what turns the
                // remote's centre key into a click; adding `focusable` beside
                // it would register two focus targets for one card.
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            if (item.posterUrl != null) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No poster is common on obscure titles; a readable fallback
                // beats an empty rectangle the eye reads as a loading error.
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                )
            }

            item.score?.takeIf { it > 0 }?.let { score ->
                ScoreBadge(
                    score = score,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) HubColors.Text else HubColors.TextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(HubDimens.PosterWidth),
        )
    }
}

@Composable
private fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.verticalGradient(
                    listOf(HubColors.Background.copy(alpha = 0.85f), HubColors.Background)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (score / 10.0).let { String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", it) },
            style = MaterialTheme.typography.labelSmall,
            color = HubColors.Imdb,
        )
    }
}
