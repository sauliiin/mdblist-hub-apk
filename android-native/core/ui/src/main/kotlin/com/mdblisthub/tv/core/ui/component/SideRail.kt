package com.mdblisthub.tv.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.ui.theme.HubColors

data class RailItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Estuary's side menu.
 *
 * Collapsed it is a strip of icons; the moment focus enters it, it widens and
 * the labels appear. That behaviour is the signature of the skin this
 * interface is modelled on, and it earns its place: it costs no screen while
 * you are browsing posters, and needs no discovery when you want it.
 */
@Composable
fun SideRail(
    items: List<RailItem>,
    selectedKey: String,
    onSelect: (RailItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { expanded = it.hasFocus }
            .animateContentSize()
            .width(if (expanded) 232.dp else 84.dp)
            .background(
                Brush.horizontalGradient(
                    0f to HubColors.Background,
                    1f to HubColors.Background.copy(alpha = if (expanded) 0.92f else 0f),
                )
            )
            .padding(vertical = 32.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            RailRow(
                item = item,
                expanded = expanded,
                selected = item.key == selectedKey,
                onSelect = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun RailRow(
    item: RailItem,
    expanded: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val background by animateColorAsState(
        targetValue = when {
            focused -> HubColors.Accent
            selected -> HubColors.SurfaceStrong
            else -> HubColors.Background.copy(alpha = 0f)
        },
        label = "rail-background",
    )
    val tint = when {
        focused -> HubColors.Text
        selected -> HubColors.AccentSoft
        else -> HubColors.TextFaint
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        if (expanded) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
        }
    }
}
