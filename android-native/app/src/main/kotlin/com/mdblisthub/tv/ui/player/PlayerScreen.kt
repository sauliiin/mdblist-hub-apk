package com.mdblisthub.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import com.mdblisthub.tv.player.TrackInfo
import com.mdblisthub.tv.player.MpvVideoSurface
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
    val engine = (LocalContext.current.applicationContext as HubApplication).mpvEngine
    val viewModel = hubViewModel(key = "player-$type-$tmdbId-$season-$episode") {
        PlayerViewModel(graph, engine, type, tmdbId, season, episode)
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playback by viewModel.controller.state.collectAsStateWithLifecycle()

    var osdVisibleUntil by remember { mutableLongStateOf(System.currentTimeMillis() + OSD_TIMEOUT_MS) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var audioPickerOpen by remember { mutableStateOf(false) }
    // Whether one of the OSD buttons currently holds focus. While it does,
    // left/right have to move focus between the buttons instead of seeking —
    // see the key handler below.
    var controlsFocused by remember { mutableStateOf(false) }
    val playButtonFocusRequester = remember { FocusRequester() }
    // Set the moment OK/Enter wakes a hidden OSD, so focus can land on Play
    // as soon as it composes — the button does not exist yet in the same
    // frame the key press arrives in.
    var wantsPlayFocus by remember { mutableStateOf(false) }

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

    // Runs after the OSD (and its Play button) has actually composed, which
    // is why this can't happen inline in the key handler below.
    LaunchedEffect(osdVisible, wantsPlayFocus) {
        if (osdVisible && wantsPlayFocus) {
            playButtonFocusRequester.requestFocus()
            wantsPlayFocus = false
        }
    }

    BackHandler {
        when {
            subtitlePickerOpen -> subtitlePickerOpen = false
            audioPickerOpen -> audioPickerOpen = false
            else -> onBack()
        }
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
                // Read before the poke() below moves it, so this still
                // reflects whether the OSD was hidden *before* this press.
                val wasHidden = !osdVisible
                poke()

                when (event.key) {
                    // Three different things, depending on what OK/Enter
                    // finds: a focused button gets to handle its own press; a
                    // hidden OSD wakes with focus landing directly on Play,
                    // so the first press is never a blind guess at what it
                    // just did; and only once the OSD is already up with
                    // nothing focused does the old "OK toggles play" shortcut
                    // apply.
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        when {
                            controlsFocused -> false
                            wasHidden -> {
                                wantsPlayFocus = true
                                true
                            }
                            else -> {
                                viewModel.controller.togglePlayPause(); true
                            }
                        }
                    }
                    Key.MediaPlayPause -> {
                        viewModel.controller.togglePlayPause(); true
                    }
                    // Same story for left/right: they seek while browsing the
                    // video, but the moment a control is focused they have to
                    // fall through so the D-pad walks between the buttons
                    // instead of always skipping the film forward/back.
                    Key.DirectionRight -> {
                        if (controlsFocused) {
                            false
                        } else {
                            viewModel.controller.seekBy(SEEK_STEP_MS); true
                        }
                    }
                    Key.DirectionLeft -> {
                        if (controlsFocused) {
                            false
                        } else {
                            viewModel.controller.seekBy(-SEEK_STEP_MS); true
                        }
                    }
                    Key.MediaFastForward -> {
                        viewModel.controller.seekBy(SEEK_STEP_MS); true
                    }
                    Key.MediaRewind -> {
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
        MpvVideoSurface(
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
                onOpenAudio = { audioPickerOpen = true; poke() },
                onControlsFocusChanged = { controlsFocused = it },
                playButtonFocusRequester = playButtonFocusRequester,
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

    if (audioPickerOpen) {
        AudioPickerOverlay(
            options = playback.audioTracks,
            activeId = playback.currentAudioId,
            onSelect = { id ->
                viewModel.controller.selectAudioTrack(id)
                audioPickerOpen = false
                poke()
            },
            onDismiss = { audioPickerOpen = false },
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
    onOpenAudio: () -> Unit,
    onControlsFocusChanged: (Boolean) -> Unit,
    playButtonFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // The seek bar and the button row hand focus back and forth on up/down
    // rather than leaving it to Compose's default spatial search: the bar is
    // a thin 6dp target sitting well below the buttons, exactly the kind of
    // geometry that search picks the wrong neighbour for.
    val progressBarFocusRequester = remember { FocusRequester() }

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier
                    // Tracked so the screen's key handler knows when to hand
                    // left/right to focus movement between these buttons
                    // instead of using them as seek shortcuts.
                    .onFocusChanged { onControlsFocusChanged(it.hasFocus) }
                    // Bubbles up from whichever button is actually focused,
                    // so every button gets this without repeating it four
                    // times.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            progressBarFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                OsdIconButton(
                    icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pausar" else "Tocar",
                    onClick = onTogglePlay,
                    modifier = Modifier.focusRequester(playButtonFocusRequester),
                )
                OsdIconButton(
                    icon = Icons.Filled.Subtitles,
                    contentDescription = "Legenda",
                    onClick = onOpenSubtitles,
                    active = subtitleActive,
                )
                OsdIconButton(
                    icon = Icons.Filled.Audiotrack,
                    contentDescription = "Faixa de áudio",
                    onClick = onOpenAudio,
                )
                OsdIconButton(
                    icon = Icons.Filled.AspectRatio,
                    contentDescription = "Esticar: $scaleLabel",
                    onClick = onCycleScale,
                )
            }
        }

        val progressBarInteraction = remember { MutableInteractionSource() }
        val progressBarFocused by progressBarInteraction.collectIsFocusedAsState()
        val progressBarHeight by animateDpAsState(
            if (progressBarFocused) 12.dp else 6.dp,
            focusTween(),
            label = "progress-bar-height",
        )
        val progressBarGlow by animateDpAsState(
            if (progressBarFocused) 3.dp else 0.dp,
            focusTween(),
            label = "progress-bar-glow",
        )

        // A ring around the track, not just a colour swap on the fill: the
        // fill is a thin sliver at the very start of playback, and a colour
        // change alone on almost-no-area is exactly the kind of cue this
        // session's OSD buttons already learned reads as "not evident enough"
        // from a couch. The ring stays legible regardless of how much of the
        // bar is actually filled in.
        Box(
            Modifier
                .fillMaxWidth()
                .height(progressBarHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(HubColors.Border)
                .border(
                    width = progressBarGlow,
                    color = if (progressBarFocused) HubColors.Accent else HubColors.Border.copy(alpha = 0f),
                    shape = RoundedCornerShape(6.dp),
                )
                .focusRequester(progressBarFocusRequester)
                .focusable(interactionSource = progressBarInteraction)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                        playButtonFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                },
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
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (progressBarFocused) HubColors.AccentSoft else HubColors.Accent),
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
 *
 * Every OSD button — play, legenda, áudio, esticar — shares this one size, so
 * play does not read as more important than the controls beside it and the
 * row scans evenly left to right.
 *
 * Every property that changes with focus — scale, background, border —
 * animates on the same [FOCUS_TWEEN], so walking the row with left/right
 * reads as one continuous slide from button to button rather than a series
 * of snaps. `.scale()` is applied outside the `.size()`/`.clip()` chain
 * specifically so growing to 1.15x never breaks the circle back into an
 * ellipse.
 */
@Composable
private fun OsdIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val scale by animateFloatAsState(if (focused) 1.15f else 1f, focusTween(), label = "osd-scale")
    val background by animateColorAsState(
        when {
            focused -> HubColors.Accent
            active -> HubColors.Accent.copy(alpha = 0.28f)
            else -> HubColors.Surface.copy(alpha = 0.7f)
        },
        focusTween(),
        label = "osd-background",
    )
    val borderColor by animateColorAsState(
        when {
            focused -> HubColors.Accent
            active -> HubColors.Accent.copy(alpha = 0.6f)
            else -> HubColors.Border
        },
        focusTween(),
        label = "osd-border-color",
    )
    val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp, focusTween(), label = "osd-border-width")

    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .border(width = borderWidth, color = borderColor, shape = CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused || active) HubColors.Text else HubColors.TextDim,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Shared by every OSD control so the row moves as one consistent motion. */
private fun <T> focusTween(): FiniteAnimationSpec<T> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

@Composable
private fun SubtitlePickerOverlay(
    options: List<SubtitleOption>,
    active: SubtitleOption?,
    onSelect: (SubtitleOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opening the sheet does not move focus on its own — it was left sitting
    // on the OSD's "Legenda" button, now hidden behind the scrim, which is
    // why the remote looked dead. Landing focus on the first row is what
    // gives the d-pad something inside the list to move from.
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocus.requestFocus() }

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
                    SubtitleRow(
                        label = "Sem legenda",
                        selected = active == null,
                        modifier = Modifier.focusRequester(firstRowFocus),
                        onClick = { onSelect(null) },
                    )
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
private fun SubtitleRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(
                when {
                    focused -> HubColors.Accent.copy(alpha = 0.3f)
                    selected -> HubColors.Accent.copy(alpha = 0.14f)
                    else -> HubColors.Background.copy(alpha = 0f)
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused || selected) HubColors.AccentSoft else HubColors.TextDim,
        )
    }
}

/**
 * Same sheet as [SubtitlePickerOverlay], one list over: libVLC's own audio
 * tracks rather than an addon's subtitle options, so there is no "nenhuma"
 * row — a file always has at least one audio track playing.
 */
@Composable
private fun AudioPickerOverlay(
    options: List<TrackInfo>,
    activeId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Same fix as SubtitlePickerOverlay: without this, focus stays on the
    // now-hidden "Áudio" OSD button and the d-pad has nothing to move.
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRowFocus.requestFocus() }

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
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(vertical = 12.dp),
        ) {
            Text(
                text = "Faixa de áudio",
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(options, key = { _, option -> option.id }) { index, option ->
                    SubtitleRow(
                        label = option.label,
                        selected = option.id == activeId,
                        modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        }
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
