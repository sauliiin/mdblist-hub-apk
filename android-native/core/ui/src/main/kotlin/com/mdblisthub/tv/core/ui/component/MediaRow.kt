package com.mdblisthub.tv.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens

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
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onItemFocused: (MediaItem) -> Unit = {},
    onReachedEnd: () -> Unit = {},
) {
    if (items.isEmpty()) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )

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
                    modifier = Modifier.focusProperties { },
                )
            }
        }
    }
}
