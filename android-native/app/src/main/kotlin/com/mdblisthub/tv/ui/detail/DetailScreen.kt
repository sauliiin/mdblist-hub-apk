package com.mdblisthub.tv.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.LibraryBucket
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.Review
import com.mdblisthub.tv.core.model.ReviewProvider
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.LoadingScreen
import com.mdblisthub.tv.core.ui.component.MediaRow
import com.mdblisthub.tv.core.ui.component.RatingBadges
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    graph: DataGraph,
    type: MediaType,
    tmdbId: Int,
    onBack: () -> Unit,
    onPlay: (season: Int?, episode: Int?) -> Unit,
    onOpenTitle: (MediaItem) -> Unit,
) {
    val viewModel = hubViewModel(key = "detail-$type-$tmdbId") {
        DetailViewModel(graph, type, tmdbId)
    }
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val season by viewModel.season.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    BackHandler { onBack() }

    val current = detail
    if (current == null) {
        LoadingScreen(message = "Carregando ficha…")
        return
    }

    Box(Modifier.fillMaxSize()) {
        FanartBackdrop(url = current.backdropUrl, scrim = 0.86f)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(HubDimens.RowSpacing),
            contentPadding = PaddingValues(
                top = HubDimens.ScreenPaddingVertical * 2,
                bottom = HubDimens.ScreenPaddingVertical * 2,
            ),
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = HubDimens.ScreenPaddingHorizontal)) {
                    // Kodi's skins lead with the clearlogo when there is one;
                    // it reads better over artwork than set type ever does.
                    if (current.logoUrl != null) {
                        AsyncImage(
                            model = current.logoUrl,
                            contentDescription = current.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.height(96.dp).widthIn(max = 460.dp).fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.displayLarge,
                            color = HubColors.Text,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOfNotNull(
                            current.year?.toString(),
                            current.certification,
                            current.runtimeMinutes?.let { "$it min" },
                            current.seasonCount?.let { "$it temporada${if (it > 1) "s" else ""}" },
                            current.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() },
                        ).forEach {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    RatingBadges(current.ratings)

                    current.overview?.let {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = HubColors.TextDim,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    // Five buttons is more than a phone's width holds at a
                    // 10-foot touch-target size, so the row scrolls instead of
                    // squeezing — and `height(IntrinsicSize.Min)` with
                    // `fillMaxHeight()` on each button is what keeps "Marcar
                    // como assistido" from reading taller than the others
                    // whenever it does wrap.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .height(IntrinsicSize.Min)
                            // Coming back up from cast/reviews/episodes to this
                            // row is the moment the poster, rating and overview
                            // above it need to be visible again, so focus
                            // landing here snaps the list back to the top.
                            .onFocusChanged { state ->
                                if (state.hasFocus) {
                                    coroutineScope.launch { listState.animateScrollToItem(0) }
                                }
                            },
                    ) {
                        HubButton(
                            text = if (type == MediaType.SHOW) "Assistir T${season}E1" else "Assistir",
                            primary = true,
                            onClick = {
                                if (type == MediaType.SHOW) onPlay(season, 1) else onPlay(null, null)
                            },
                            modifier = Modifier.fillMaxHeight(),
                        )
                        if (current.trailerKey != null) {
                            HubButton(
                                text = "Trailer",
                                onClick = { openTrailer(context, current.trailerKey!!) },
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        HubButton(
                            text = if (library.watchlist) "Na watchlist" else "+ Watchlist",
                            enabled = LibraryBucket.WATCHLIST !in pending,
                            onClick = viewModel::toggleWatchlist,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        HubButton(
                            text = if (library.collection) "Na coleção" else "+ Coleção",
                            enabled = LibraryBucket.COLLECTION !in pending,
                            onClick = viewModel::toggleCollection,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        HubButton(
                            text = if (library.watched) "Assistido" else "Marcar assistido",
                            enabled = LibraryBucket.WATCHED !in pending,
                            onClick = viewModel::toggleWatched,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }

                    libraryError?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = HubColors.Rotten)
                    }

                    if (current.directors.isNotEmpty() || current.studios.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = listOfNotNull(
                                current.directors.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")?.let { "Direção: $it" },
                                current.studios.takeIf { it.isNotEmpty() }
                                    ?.joinToString(", ")?.let { "Estúdio: $it" },
                            ).joinToString("   ·   "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HubColors.TextFaint,
                        )
                    }
                }
            }

            if (type == MediaType.SHOW && current.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Temporadas",
                            style = MaterialTheme.typography.titleLarge,
                            color = HubColors.Text,
                            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(current.seasons, key = { it.seasonNumber }) { entry ->
                                HubButton(
                                    text = "T${entry.seasonNumber}",
                                    primary = entry.seasonNumber == season,
                                    onClick = { viewModel.selectSeason(entry.seasonNumber) },
                                )
                            }
                        }
                    }
                }

                item(key = "episodes") {
                    EpisodeRow(
                        episodes = episodes,
                        onPlay = { ep -> onPlay(ep.seasonNumber, ep.episodeNumber) },
                    )
                }
            }

            if (current.cast.isNotEmpty()) {
                item(key = "cast") {
                    CastRow(current)
                }
            }

            if (current.reviews.isNotEmpty()) {
                item(key = "reviews") {
                    ReviewsRow(current.reviews)
                }
            }

            if (current.recommendations.isNotEmpty()) {
                item(key = "recs") {
                    MediaRow(
                        title = "Parecidos",
                        items = current.recommendations,
                        onItemClick = onOpenTitle,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episodes: List<Episode>, onPlay: (Episode) -> Unit) {
    if (episodes.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Episódios",
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
            modifier = Modifier.focusRestorer(),
        ) {
            items(episodes, key = { it.id }) { episode ->
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HubColors.Surface.copy(alpha = 0.7f))
                        .clickable { onPlay(episode) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    episode.stillUrl?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = episode.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(
                        text = "${episode.episodeNumber}. ${episode.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = HubColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CastRow(current: com.mdblisthub.tv.core.model.MediaDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Elenco",
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
        ) {
            items(current.cast, key = { it.id }) { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.width(112.dp),
                ) {
                    Box(
                        Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(HubColors.Surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (member.profileUrl != null) {
                            AsyncImage(
                                model = member.profileUrl,
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = HubColors.Text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    member.character?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = HubColors.TextFaint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewsRow(reviews: List<Review>) {
    if (reviews.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Reviews",
            style = MaterialTheme.typography.titleLarge,
            color = HubColors.Text,
            modifier = Modifier.padding(start = HubDimens.ScreenPaddingHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = HubDimens.ScreenPaddingHorizontal),
            modifier = Modifier.focusRestorer(),
        ) {
            items(reviews, key = { "${it.provider}-${it.author}-${it.updatedAt}" }) { review ->
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HubColors.Surface.copy(alpha = 0.7f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = review.author,
                            style = MaterialTheme.typography.titleMedium,
                            color = HubColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        review.rating?.let {
                            Text(
                                text = "★ ${String.format("%.1f", it)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = HubColors.Imdb,
                            )
                        }
                        Text(
                            text = review.provider.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = review.provider.color(),
                        )
                    }
                    Text(
                        text = review.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HubColors.TextDim,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun ReviewProvider.label(): String = when (this) {
    ReviewProvider.TRAKT -> "Trakt"
    ReviewProvider.TMDB -> "TMDB"
}

private fun ReviewProvider.color(): Color = when (this) {
    ReviewProvider.TRAKT -> HubColors.Trakt
    ReviewProvider.TMDB -> HubColors.Tmdb
}

/**
 * Opens the trailer in the YouTube app, or a browser if there is no YouTube
 * app to hand it to.
 *
 * The player embedded in this app cannot do it: libVLC plays media files, not
 * YouTube's streaming protocol, and this build carries none of the resolver
 * scripts (`youtube.lua`) that would let it try. Handing off to an app built
 * for exactly this is the native equivalent of the `<iframe>` the web build
 * uses — both delegate instead of reimplementing a video platform.
 */
private fun openTrailer(context: Context, youtubeKey: String) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(webIntent)
        } catch (_: ActivityNotFoundException) {
            // A box with neither the YouTube app nor a browser: nothing left
            // to hand the trailer to.
        }
    }
}
