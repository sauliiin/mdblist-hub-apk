package com.mdblisthub.tv.ui.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
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
import kotlinx.coroutines.flow.StateFlow

/** Where a focused row parks: 30% down the viewport, the same pivot Compose's own (internal) TV spec uses. */
private const val ROW_PIVOT = 0.3f

/**
 * How the column scrolls when focus moves between rows.
 *
 * Compose's default already animates — it is a `spring` — so the jerkiness
 * was never a missing animation. It is that the default scrolls the *minimum*
 * distance needed to reveal the row, so every row settles at whatever height
 * happens to work out and each press travels a different amount. The eye
 * reads that as stumbling rather than gliding.
 *
 * The fix is a fixed landing point. Compose ships exactly this as
 * `PivotBringIntoViewSpec`, in its Android source set because it exists for
 * televisions — but it is `internal`, so the geometry is restated here.
 *
 * A spring, not a tween, and a stiff one. Two reasons. The pivot means every
 * press now scrolls — the old minimal-scroll often moved nothing at all — so
 * the duration is paid on every single press and a leisurely one reads as
 * lag. And holding the direction key fires presses faster than any animation
 * finishes: a tween restarts from zero each time, so the list visibly trails
 * the focus, while a spring retargets from wherever it is and keeps its
 * velocity, which is what makes a held key feel like one continuous glide.
 * `DampingRatioNoBouncy` is what keeps "spring" from meaning "wobbles".
 */
@OptIn(ExperimentalFoundationApi::class)
private val RowPivotScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = ROW_PIVOT * containerSize

        // A row tall enough that parking it at the pivot would hang its
        // bottom off-screen is aligned to the bottom edge instead — parking
        // it would otherwise hide the very cards being brought into view.
        val target = if (size <= containerSize && containerSize - pivot < size) {
            containerSize - size
        } else {
            pivot
        }

        // The container clamps this at both ends, which is what keeps the
        // hero panel visible at the top of the list: the first row wants to
        // move *down* to reach the pivot, and there is nowhere to scroll.
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    graph: DataGraph,
    onOpenTitle: (MediaItem) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAddons: () -> Unit,
    onResume: (ResumePoint) -> Unit,
    onSignOut: () -> Unit,
) {
    val viewModel = hubViewModel { HomeViewModel(graph) }
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val resumePoints by viewModel.resumePoints.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val focusedBackdropUrl by viewModel.focusedBackdropUrl.collectAsStateWithLifecycle()
    val becauseYouWatched by viewModel.becauseYouWatched.collectAsStateWithLifecycle()
    val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()

    var showExitDialog by remember { mutableStateOf(false) }

    if (showExitDialog) {
        Dialog(onDismissRequest = { showExitDialog = false }) {
            Box(
                modifier = Modifier
                    .background(
                        HubColors.Surface, 
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Deseja realmente sair?", 
                        style = MaterialTheme.typography.titleLarge, 
                        color = HubColors.Text
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { 
                                showExitDialog = false
                                viewModel.signOut(onSignOut) 
                            }
                        ) {
                            Text("Sim")
                        }
                        Button(onClick = { showExitDialog = false }) {
                            Text("Não")
                        }
                    }
                }
            }
        }
    }

    // Remembered because this composable recomposes on every card focus (it
    // reads `focused` for the hero panel), and neither of these depends on
    // that — rebuilding them per focus is pure allocation.
    val rail = remember(isEditMode) {
        listOf(
            RailItem("home", "Início", Icons.Default.Home),
            RailItem("search", "Busca", Icons.Default.Search),
            RailItem("addons", "Addons", Icons.Default.Extension),
            RailItem("lists", if (isEditMode) "Concluir" else "Listas", if (isEditMode) Icons.Default.Check else Icons.AutoMirrored.Filled.ViewList),
            RailItem("theme", if (HubColors.isCyberpunk) "Normal" else "Cyberpunk", Icons.Default.Settings),
            RailItem("exit", "Sair", Icons.AutoMirrored.Filled.Logout),
        )
    }
    val resumeCards = remember(resumePoints) { resumePoints.map { it.toCardItem() } }

    Box(Modifier.fillMaxSize()) {
        // The fanart follows focus, the way Estuary does it: whatever the
        // remote is pointing at fills the screen behind the rows.
        FanartBackdrop(url = focusedBackdropUrl)

        Row(Modifier.fillMaxSize()) {
            SideRail(
                items = rail,
                selectedKey = "home",
                onSelect = { item ->
                    when (item.key) {
                        "search" -> onOpenSearch()
                        "addons" -> onOpenAddons()
                        "lists" -> viewModel.toggleEditMode()
                        "theme" -> HubColors.toggleCyberpunk()
                        "exit" -> showExitDialog = true
                    }
                },
            )

            if (lists.isEmpty() && resumePoints.isEmpty()) {
                LoadingScreen(message = "Sincronizando suas listas…")
                return@Row
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides RowPivotScroll) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Tighter than HubDimens.RowSpacing on purpose — with the
                // smaller posters, this is what keeps two rows of a list on
                // screen together instead of one full row plus a sliver of
                // the next.
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 12.dp,
                    // Room to park the last row at the pivot instead of
                    // stopping short with it pinned to the bottom edge.
                    bottom = HubDimens.ScreenPaddingVertical * 8,
                ),
            ) {
                item(key = "hero") {
                    HeroPanel(focused)
                }

                if (resumePoints.isNotEmpty() && !isEditMode) {
                    item(key = "resume") {
                        MediaRow(
                            title = "Continuar assistindo",
                            items = resumeCards,
                            onItemClick = { card ->
                                resumePoints.firstOrNull { it.tmdbId == card.tmdbId }
                                    ?.let(onResume)
                            },
                            onItemFocused = viewModel::onFocused,
                        )
                    }
                }

                items(lists, key = { it.id }) { list ->
                    val itemFlow = remember(list.id) { viewModel.itemsFor(list.id) }
                    ListRow(
                        list = list,
                        itemFlow = itemFlow,
                        isEditMode = isEditMode,
                        onToggleVisibility = { viewModel.toggleListVisibility(list) },
                        onEnsure = { viewModel.ensureItems(list.id) },
                        onItemClick = onOpenTitle,
                        onItemFocused = viewModel::onFocused,
                        onReachedEnd = { viewModel.loadMore(list.id) },
                    )
                }

                // "Porque você assistiu" — always last, since it is built from
                // the five most recent watches rather than curated like the
                // rows above it.
                if (!isEditMode) {
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
    }
}

/**
 * One row, collecting its own items.
 *
 * `LazyColumn` only collects rows near the viewport. The ViewModel retains
 * each visited row's last Room emission, so returning upward restores its
 * geometry immediately instead of flashing through an empty 0dp item.
 */
@Composable
private fun ListRow(
    list: MediaList,
    itemFlow: StateFlow<List<MediaItem>>,
    isEditMode: Boolean = false,
    onToggleVisibility: () -> Unit = {},
    onEnsure: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onItemFocused: (MediaItem) -> Unit,
    onReachedEnd: () -> Unit,
) {
    val items by itemFlow.collectAsStateWithLifecycle()

    LaunchedEffect(list.id) { onEnsure() }

    MediaRow(
        title = list.name,
        items = items,
        isEditMode = isEditMode,
        hidden = list.hidden,
        onToggleVisibility = onToggleVisibility,
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
