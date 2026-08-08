package com.mdblisthub.tv.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.IconButton
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens

/**
 * Compose's own scroll behaviour, restated so a row can opt back into it.
 *
 * Both members of `BringIntoViewSpec` are defaults, so an empty implementation
 * *is* the default — which matters because `LocalBringIntoViewSpec` is read by
 * every scrollable underneath whoever provided it. The home screen provides a
 * pivot spec to park rows at a fixed height; without this, the same spec would
 * reach the horizontal list below and park every *card* at 30% across, sliding
 * the row sideways the instant it took focus.
 */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {}

/**
 * A list, rendered as a row of posters.
 *
 * `focusRestorer` is what makes vertical navigation feel right: leaving a row
 * and coming back should land on the card you left, not snap to the first one.
 * Without it, browsing down three rows and back up loses your place every
 * time — the classic tell of a TV app built from phone components.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    isEditMode: Boolean = false,
    hidden: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onItemFocused: (MediaItem) -> Unit = {},
    onReachedEnd: () -> Unit = {},
) {
    if (items.isEmpty()) return

    Column(modifier.fillMaxWidth().alpha(if (isEditMode && hidden) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
            
            if (isEditMode) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.padding(end = HubDimens.ScreenPaddingHorizontal),
                    colors = androidx.tv.material3.IconButtonDefaults.colors(
                        focusedContainerColor = HubColors.Accent,
                        focusedContentColor = HubColors.Text,
                        contentColor = HubColors.TextFaint
                    )
                ) {
                    Icon(
                        imageVector = if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (hidden) "Mostrar lista" else "Ocultar lista",
                        tint = HubColors.Text
                    )
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(HubDimens.CardSpacing),
                contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRestorer(),
            ) {
                items(items, key = { it.key }) { item ->
                    PosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onFocused = { focused ->
                            onItemFocused(focused)
                            // Paging off focus rather than off scroll position:
                            // on a remote the two are the same thing, and focus
                            // is the one that fires exactly once per card.
                            if (focused.key == items.lastOrNull()?.key) onReachedEnd()
                        },
                        modifier = Modifier.focusProperties { canFocus = !isEditMode },
                    )
                }
            }
        }
    }
}
