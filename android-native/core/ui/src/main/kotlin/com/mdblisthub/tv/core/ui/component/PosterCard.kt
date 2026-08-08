package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
 * Focus is expressed two ways at once — an accent border and a brightening
 * title, no scale — because on a television the viewer is metres away and a
 * single cue is easy to lose. The zoom this used to add on top fought the
 * grid's own spacing (a scaled-up card overlaps its neighbours) and read as
 * restless when moving quickly through a row; the border alone is enough.
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
    val targetBorderWidth = if (focused) {
        if (HubColors.isCyberpunk) 4.5.dp else 2.5.dp
    } else 0.dp
    val borderWidth by animateDpAsState(
        targetValue = targetBorderWidth,
        animationSpec = posterFocusTween(),
        label = "poster-border-width",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) HubColors.Accent else HubColors.Border,
        animationSpec = posterFocusTween(),
        label = "poster-border-color",
    )
    val titleColor by animateColorAsState(
        targetValue = if (focused) HubColors.Text else HubColors.TextDim,
        animationSpec = posterFocusTween(),
        label = "poster-title-color",
    )

    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) onFocused(item)
    }

    Column(
        modifier = modifier.width(HubDimens.PosterWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 10.dp
        Box(
            Modifier
                .width(HubDimens.PosterWidth)
                .height(HubDimens.PosterHeight)
                .let {
                    if (HubColors.isCyberpunk && focused) {
                        it.animatedCyberpunkGlow(shape = RoundedCornerShape(cornerRadius))
                    } else it
                }
                .clip(RoundedCornerShape(cornerRadius))
                .background(HubColors.Surface)
                .let {
                    if (!HubColors.isCyberpunk || !focused) {
                        it.border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(cornerRadius))
                    } else it
                }
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
            color = titleColor,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(HubDimens.PosterWidth),
        )
    }
}

/** Shared by every focus-driven property on the card, so they move as one. */
private fun <T> posterFocusTween() = tween<T>(durationMillis = 200, easing = FastOutSlowInEasing)

@Composable
private fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    val cornerRadius = if (HubColors.isCyberpunk) 0.dp else 6.dp
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(cornerRadius))
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

fun Modifier.animatedCyberpunkGlow(
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape
) = composed {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "glow_rot")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "glow_rot_anim"
    )

    this.drawBehind {
        val colorsInt = intArrayOf(
            android.graphics.Color.parseColor("#9D00FF"),
            android.graphics.Color.parseColor("#00F3FF"),
            android.graphics.Color.parseColor("#9D00FF")
        )
        val shader = android.graphics.SweepGradient(size.width / 2f, size.height / 2f, colorsInt, null)
        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotation, size.width / 2f, size.height / 2f)
        shader.setLocalMatrix(matrix)

        val paint1 = androidx.compose.ui.graphics.Paint().apply {
            asFrameworkPaint().apply {
                this.shader = shader
                maskFilter = android.graphics.BlurMaskFilter(
                    16.dp.toPx() * 1.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }
        }
        val paint2 = androidx.compose.ui.graphics.Paint().apply {
            asFrameworkPaint().apply {
                this.shader = shader
                maskFilter = android.graphics.BlurMaskFilter(
                    16.dp.toPx() * 0.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }
        }
        val borderPaint = androidx.compose.ui.graphics.Paint().apply {
            asFrameworkPaint().apply {
                this.shader = shader
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4.5.dp.toPx()
            }
        }

        drawIntoCanvas { canvas ->
            val outline = shape.createOutline(size, layoutDirection, this)
            when (outline) {
                is androidx.compose.ui.graphics.Outline.Rectangle -> {
                    canvas.drawRect(outline.rect, paint1)
                    canvas.drawRect(outline.rect, paint2)
                    canvas.drawRect(outline.rect, borderPaint)
                }
                is androidx.compose.ui.graphics.Outline.Rounded -> {
                    val roundRect = outline.roundRect
                    canvas.nativeCanvas.drawRoundRect(
                        roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
                        roundRect.bottomLeftCornerRadius.x, roundRect.bottomLeftCornerRadius.y,
                        paint1.asFrameworkPaint()
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
                        roundRect.bottomLeftCornerRadius.x, roundRect.bottomLeftCornerRadius.y,
                        paint2.asFrameworkPaint()
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        roundRect.left, roundRect.top, roundRect.right, roundRect.bottom,
                        roundRect.bottomLeftCornerRadius.x, roundRect.bottomLeftCornerRadius.y,
                        borderPaint.asFrameworkPaint()
                    )
                }
                is androidx.compose.ui.graphics.Outline.Generic -> {
                    canvas.drawPath(outline.path, paint1)
                    canvas.drawPath(outline.path, paint2)
                    canvas.drawPath(outline.path, borderPaint)
                }
            }
        }
    }
}
