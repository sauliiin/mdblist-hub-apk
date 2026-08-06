package com.mdblisthub.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.HubApplication
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.ui.component.FanartBackdrop
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.player.PlaybackPhase
import com.mdblisthub.tv.player.VlcVideoSurface
import com.mdblisthub.tv.player.label
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.delay

private const val OSD_TIMEOUT_MS = 4_000L
private const val SEEK_STEP_MS = 10_000L

@Composable
fun PlayerScreen(
    graph: DataGraph,
    type: MediaType,
    tmdbId: Int,
    season: Int?,
    episode: Int?,
    onBack: () -> Unit,
    onOpenAddons: () -> Unit,
) {
    val engine = (LocalContext.current.applicationContext as HubApplication).vlcEngine
    val viewModel = hubViewModel(key = "player-$type-$tmdbId-$season-$episode") {
        PlayerViewModel(graph, engine, type, tmdbId, season, episode)
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()

    var osdVisibleUntil by remember { mutableLongStateOf(System.currentTimeMillis() + OSD_TIMEOUT_MS) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }

    // Paused, or nothing decoding yet: the OSD has nothing to hide behind, so
    // it simply stays up rather than counting down to invisible controls.
    val osdVisible = now < osdVisibleUntil || !playback.isPlaying

    LaunchedEffect(osdVisibleUntil) {
        while (System.currentTimeMillis() < osdVisibleUntil) {
            delay(250)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler {
        if (subtitlePickerOpen) subtitlePickerOpen = false else onBack()
    }

    fun poke() { osdVisibleUntil = System.currentTimeMillis() + OSD_TIMEOUT_MS }
    fun hideNow() { osdVisibleUntil = 0L }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                poke()

                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                        viewModel.controller.togglePlayPause(); true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        viewModel.controller.seekBy(SEEK_STEP_MS); true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        viewModel.controller.seekBy(-SEEK_STEP_MS); true
                    }
                    // Any other key only wakes the OSD, which is what a remote
                    // user expects from pressing "something".
                    else -> false
                }
            }
            // A remote has an "any key wakes the OSD" gesture built in; a
            // touchscreen has nothing equivalent unless a tap is wired to the
            // same effect. Without this, the OSD fades four seconds in and a
            // phone has no way left to bring it back.
            .pointerInput(playback.canShowVideo) {
                if (!playback.canShowVideo) return@pointerInput
                detectTapGestures(
                    onTap = {
                        if (osdVisible && playback.isPlaying) hideNow() else poke()
                    },
                )
            },
    ) {
        VlcVideoSurface(
            controller = viewModel.controller,
            modifier = Modifier.fillMaxSize(),
        )

        /*
         * The veil.
         *
         * Everything the cascade does happens under this: nine sources may be
         * probed and abandoned while it is up, and the only thing on screen is
         * the film's own artwork and a line saying it is starting. That is the
         * entire point of hiding the sources — the failover has to be
         * invisible, or it is just a slower version of a picker.
         */
        if (ui.searching || playback.phase == PlaybackPhase.RESOLVING) {
            ResolvingVeil(
                backdropUrl = ui.backdropUrl,
                title = ui.title,
                subtitle = ui.episodeLabel,
                attempt = playback.attempt,
                total = playback.candidateCount,
            )
        }

        if (playback.phase == PlaybackPhase.FAILED || ui.noAddons || ui.missingImdbId) {
            FailureVeil(
                backdropUrl = ui.backdropUrl,
                message = when {
                    ui.noAddons -> "Nenhum addon instalado. É de um addon que saem as fontes."
                    ui.missingImdbId ->
                        "Este título não tem IMDb ID, e os addons são indexados por ele."
                    else -> playback.error ?: "Não consegui reproduzir este título."
                },
                showAddons = ui.noAddons || playback.phase == PlaybackPhase.FAILED,
                onOpenAddons = onOpenAddons,
                onBack = onBack,
            )
        }

        if (osdVisible && playback.canShowVideo) {
            PlayerOsd(
                title = ui.title,
                subtitle = ui.episodeLabel,
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                playing = playback.isPlaying,
                scaleLabel = playback.scaleType.label(),
                subtitleActive = playback.externalSubtitle != null,
                onTogglePlay = { viewModel.controller.togglePlayPause(); poke() },
                onCycleScale = { viewModel.controller.cycleScale(); poke() },
                onOpenSubtitles = { subtitlePickerOpen = true; poke() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (subtitlePickerOpen) {
        SubtitlePickerOverlay(
            options = ui.subtitles,
            active = playback.externalSubtitle,
            onSelect = { option ->
                viewModel.selectSubtitle(option)
                subtitlePickerOpen = false
                poke()
            },
            onDismiss = { subtitlePickerOpen = false },
        )
    }
}

@Composable
private fun ResolvingVeil(
    backdropUrl: String?,
    title: String,
    subtitle: String?,
    attempt: Int,
    total: Int,
) {
    Box(Modifier.fillMaxSize()) {
        FanartBackdrop(url = backdropUrl, scrim = 0.9f)

        Column(
            modifier = Modifier.fillMaxSize().padding(64.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HubSpinner(size = 44.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                text = title.ifBlank { "Preparando…" },
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
                textAlign = TextAlign.Center,
            )
            subtitle?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Preparando a reprodução…",
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
            )
            // Deliberately vague about *what* is being tried. The count is
            // there to show progress, not to invite a choice.
            if (total > 1 && attempt > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$attempt de $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                )
            }
        }
    }
}

@Composable
private fun FailureVeil(
    backdropUrl: String?,
    message: String,
    showAddons: Boolean,
    onOpenAddons: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        FanartBackdrop(url = backdropUrl, scrim = 0.94f)

        Column(
            modifier = Modifier.fillMaxSize().padding(72.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Não deu para reproduzir",
                style = MaterialTheme.typography.headlineLarge,
                color = HubColors.Text,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 640.dp),
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (showAddons) HubButton("Ver addons", onOpenAddons, primary = true)
                HubButton("Voltar", onBack)
            }
        }
    }
}

@Composable
private fun PlayerOsd(
    title: String,
    subtitle: String?,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    scaleLabel: String,
    subtitleActive: Boolean,
    onTogglePlay: () -> Unit,
    onCycleScale: () -> Unit,
    onOpenSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(HubColors.Background.copy(alpha = 0f), HubColors.Background.copy(alpha = 0.94f))
                )
            )
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = HubColors.Text)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = HubColors.TextDim)
            }
        }

        // ---------------------------------------------------------- controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                OsdIconButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pausar" else "Tocar",
                    onClick = onTogglePlay,
                    large = true,
                )
                OsdIconButton(
                    icon = Icons.Filled.Subtitles,
                    contentDescription = "Legenda",
                    onClick = onOpenSubtitles,
                    active = subtitleActive,
                )
                OsdIconButton(
                    icon = Icons.Filled.AspectRatio,
                    contentDescription = "Esticar: $scaleLabel",
                    onClick = onCycleScale,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(HubColors.Border),
        ) {
            val fraction = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(HubColors.Accent),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = HubColors.TextDim,
            )
            Text(
                text = scaleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextFaint,
            )
        }
    }
}

/**
 * A circular control that reads the same whether it is pressed with a thumb
 * or a D-pad OK: focus and touch both land on the same target, sized for a
 * fingertip first since that is what most of this app's testing has been on.
 */
@Composable
private fun OsdIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    active: Boolean = false,
) {
    val size = if (large) 56.dp else 44.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) HubColors.Accent.copy(alpha = 0.28f) else HubColors.Surface.copy(alpha = 0.7f))
            .border(
                1.dp,
                if (active) HubColors.Accent.copy(alpha = 0.6f) else HubColors.Border,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) HubColors.AccentSoft else HubColors.Text,
            modifier = Modifier.size(if (large) 30.dp else 22.dp),
        )
    }
}

@Composable
private fun SubtitlePickerOverlay(
    options: List<SubtitleOption>,
    active: SubtitleOption?,
    onSelect: (SubtitleOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(HubColors.Surface)
                .border(1.dp, HubColors.Border, RoundedCornerShape(14.dp))
                // An explicit empty tap detector, not a disabled `clickable`:
                // consuming the gesture here is what stops a tap inside the
                // sheet from also reaching the scrim's dismiss handler behind
                // it, and a real gesture detector is the part of the contract
                // that is actually guaranteed to consume it.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = "Legenda",
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item(key = "none") {
                    SubtitleRow(label = "Sem legenda", selected = active == null) { onSelect(null) }
                }
                items(options, key = { it.key }) { option ->
                    SubtitleRow(
                        label = "${option.label} — ${option.addon}",
                        selected = active?.key == option.key,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) HubColors.Accent.copy(alpha = 0.14f) else HubColors.Background.copy(alpha = 0f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) HubColors.AccentSoft else HubColors.TextDim,
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
