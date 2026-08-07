package com.mdblisthub.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.MediaRow
import com.mdblisthub.tv.core.ui.component.RailItem
import com.mdblisthub.tv.core.ui.component.SideRail
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.ui.hubViewModel

@Composable
fun HomeScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
    onOpenAddons: () -> Unit,
    onResume: (ResumePoint) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hubViewModel { HomeViewModel(graph) }
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val resumePoints by viewModel.resumePoints.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val becauseYouWatched by viewModel.becauseYouWatched.collectAsStateWithLifecycle()

    val rail = listOf(
        RailItem("home", "Início", Icons.Default.Home),
        RailItem("addons", "Addons", Icons.Default.Extension),
        RailItem("exit", "Sair", Icons.Default.Logout),
    )

    Box(Modifier.fillMaxSize()) {
        // The fanart follows focus, the way Estuary does it: whatever the
        // remote is pointing at fills the screen behind the rows.
        FanartBackdrop(url = focused?.backdropUrl ?: focused?.posterUrl)

        Row(Modifier.fillMaxSize()) {
            SideRail(
                items = rail,
                selectedKey = "home",
                onSelect = { item ->
                    when (item.key) {
                        "addons" -> onOpenAddons()
                        "exit" -> viewModel.signOut(onSignOut)
                    }
                },
            )

            if (lists.isEmpty() && resumePoints.isEmpty()) {
                LoadingScreen(message = "Sincronizando suas listas…")
                return@Row
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Tighter than HubDimens.RowSpacing on purpose — with the
                // smaller posters, this is what keeps two rows of a list on
                // screen together instead of one full row plus a sliver of
                // the next.
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 12.dp,
                    bottom = HubDimens.ScreenPaddingVertical * 2,
                ),
            ) {
                item(key = "hero") {
                    HeroPanel(focused)
                }

                if (resumePoints.isNotEmpty()) {
                    item(key = "resume") {
                        MediaRow(
                            title = "Continuar assistindo",
                            items = resumePoints.map { it.toCardItem() },
                            onItemClick = { card ->
                                resumePoints.firstOrNull { it.tmdbId == card.tmdbId }
                                    ?.let(onResume)
                            },
                            onItemFocused = viewModel::onFocused,
                        )
                    }
                }

                items(lists, key = { it.id }) { list ->
                    ListRow(
                        graph = graph,
                        list = list,
                        onEnsure = { viewModel.ensureItems(list.id) },
                        onItemClick = onOpenTitle,
                        onItemFocused = viewModel::onFocused,
                        onReachedEnd = { viewModel.loadMore(list.id) },
                    )
                }

                // "Porque você assistiu" — always last, since it is built from
                // the five most recent watches rather than curated like the
                // rows above it.
                items(becauseYouWatched, key = { "byw-${it.seedTitle}" }) { row ->
                    MediaRow(
                        title = "Porque você assistiu ${row.seedTitle}",
                        items = row.items,
                        onItemClick = onOpenTitle,
                        onItemFocused = viewModel::onFocused,
                    )
                }
            }
        }
    }
}

/**
 * One row, collecting its own items.
 *
 * Each row owns its query rather than the ViewModel holding a map of them:
 * `LazyColumn` only composes what is on screen, so a row that has never been
 * scrolled to never opens a cursor or asks mdblist for anything.
 */
@Composable
private fun ListRow(
    graph: DataGraph,
    list: MediaList,
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onReachedEnd: () -> Unit,
) {
    val items by graph.lists.observeItems(list.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(list.id) { onEnsure() }

    MediaRow(
        title = list.name,
        items = items,
        onItemClick = onItemClick,
        onItemFocused = onItemFocused,
        onReachedEnd = onReachedEnd,
    )
}

@Composable
private fun HeroPanel(item: MediaItem?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HubDimens.ScreenPaddingHorizontal)
            .height(76.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item == null) {
            Text(
                text = "mdblist hub",
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
            )
            return@Column
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineLarge,
            color = HubColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOfNotNull(
                item.year?.toString(),
                if (item.type == MediaType.SHOW) "Série" else "Filme",
                item.runtimeMinutes?.let { "$it min" },
                item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
            ).forEach {
                Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
            }
        }
    }
}

private fun ResumePoint.toCardItem() = MediaItem(
    tmdbId = tmdbId ?: 0,
    type = type,
    title = title,
    imdbId = imdbId,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    score = score,
)
