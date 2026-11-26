package io.novadeck.control.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType

private val DeckCorner = RoundedCornerShape(2.dp)

@Composable
fun DeckPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    ledColor: Color? = null,
    headerAction: (@Composable RowScope.() -> Unit)? = null,
    borderColor: Color = BrutalistColors.HairlineBorder,
    backgroundColor: Color = BrutalistColors.CarbonSurface,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(DeckCorner)
            .background(backgroundColor)
            .border(1.dp, borderColor, DeckCorner)
    ) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrutalistColors.SteelElevated)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (ledColor != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(ledColor)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                    }
                    Text(
                        text = title,
                        style = DeckType.IndexTag,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (headerAction != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        content = headerAction
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrutalistColors.HairlineBorder)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun DeckButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    val bg = when {
        !enabled -> BrutalistColors.SteelElevated.copy(alpha = 0.45f)
        danger -> BrutalistColors.StatusFault
        primary -> BrutalistColors.SignalAccent
        else -> BrutalistColors.SteelElevated
    }
    val fg = when {
        !enabled -> BrutalistColors.GraphiteFaint
        primary || danger -> BrutalistColors.ObsidianBg
        else -> BrutalistColors.BoneInk
    }
    val border = when {
        !enabled -> BrutalistColors.HairlineBorder
        primary -> BrutalistColors.SignalAccent
        danger -> BrutalistColors.StatusFault
        else -> BrutalistColors.ActiveBorder
    }

    Box(
        modifier = modifier
            .clip(DeckCorner)
            .background(bg)
            .border(1.dp, border, DeckCorner)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            letterSpacing = 0.8.sp,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DeckChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = BrutalistColors.SignalAccent
) {
    val bg = if (selected) accent else BrutalistColors.CarbonSurface
    val fg = if (selected) BrutalistColors.ObsidianBg else BrutalistColors.TungstenMute
    val border = if (selected) accent else BrutalistColors.HairlineBorder

    Box(
        modifier = modifier
            .clip(DeckCorner)
            .background(bg)
            .border(1.dp, border, DeckCorner)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp,
            letterSpacing = 0.6.sp,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = BrutalistColors.BoneInk
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = DeckType.Caption,
            color = BrutalistColors.TungstenMute,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = DeckType.DataMono,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
