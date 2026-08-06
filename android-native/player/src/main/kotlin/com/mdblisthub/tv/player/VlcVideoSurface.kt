package com.mdblisthub.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The video output.
 *
 * `VLCVideoLayout` is libVLC's own container — it owns the surface, the
 * subtitle overlay and the aspect handling, which is why this is an
 * `AndroidView` rather than a Compose-drawn surface. Attaching is done in the
 * factory and detaching on dispose; leaving a player attached to a destroyed
 * view is how a rotation or a back press turns into a native crash.
 */
@Composable
fun VlcVideoSurface(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VLCVideoLayout(context).also { layout ->
                controller.player.attachViews(
                    layout,
                    /* displayManager = */ null,
                    /* enableSubtitles = */ true,
                    // SurfaceView, not TextureView: a set-top box gets a
                    // hardware overlay out of it, which is both cheaper and
                    // the only path to HDR passthrough on most of them.
                    /* useTextureView = */ false,
                )
            }
        },
    )

    DisposableEffect(controller) {
        onDispose { runCatching { controller.player.detachViews() } }
    }
}
