package com.mdblisthub.tv.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The video output.
 *
 * A plain `SurfaceView`, not `TextureView`: a set-top box gets a hardware
 * overlay out of it, which is both cheaper and the only path to HDR
 * passthrough on most of them — same reasoning the libVLC surface this
 * replaced was built on. mpv owns everything past the `Surface` itself; the
 * holder callback is only there to hand that surface over and take it back,
 * which is also what stops a torn-down screen from leaving mpv pointed at a
 * dead one.
 */
@Composable
fun MpvVideoSurface(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        controller.mpv.attachSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        controller.mpv.detachSurface()
                    }
                })
            }
        },
    )
}
