package dev.dogsc.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dogsc.app.ui.excludeSystemGestures
import dev.dogsc.app.ui.theme.DogScColors
import dev.dogsc.app.ui.theme.DogScFontMedium
import kotlin.math.roundToInt

enum class PillVariant { Primary, Secondary, Ghost, Danger, AccentSoft }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: PillVariant = PillVariant.Secondary,
    enabled: Boolean = true,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when (variant) {
        PillVariant.Primary -> DogScColors.Accent
        PillVariant.Secondary -> DogScColors.Panel
        PillVariant.Ghost -> Color.Transparent
        PillVariant.Danger -> DogScColors.Danger
        PillVariant.AccentSoft -> DogScColors.ControlWell
    }
    val foreground = when (variant) {
        PillVariant.Primary -> DogScColors.OnAccent
        PillVariant.Danger -> Color.White
        PillVariant.Ghost -> DogScColors.SecondaryText
        PillVariant.Secondary, PillVariant.AccentSoft -> DogScColors.PrimaryText
    }
    val shape = RoundedCornerShape(if (compact) 11.dp else 14.dp)
    val animatedAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .height(if (compact) 34.dp else 42.dp)
            .clip(shape)
            .background(background.copy(alpha = background.alpha * animatedAlpha))
            .then(
                if (variant == PillVariant.Secondary) {
                    Modifier.border(1.dp, DogScColors.Hairline, shape)
                } else {
                    Modifier
                }
            )
            .then(
                if (pressed) Modifier.background(DogScColors.PrimaryText.copy(alpha = 0.06f)) else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground.copy(alpha = animatedAlpha),
                modifier = Modifier.size(if (compact) 15.dp else 17.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = foreground.copy(alpha = animatedAlpha),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let {
            Spacer(Modifier.width(6.dp))
            it()
        }
    }
}

@Composable
fun SoftIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color = DogScColors.SecondaryText,
) {
    val background = if (selected) DogScColors.SelectionWash else Color.Transparent
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.32f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = DogScColors.Panel,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color(0x14101A2E),
                spotColor = Color(0x14101A2E),
            )
            .clip(shape)
            .background(color)
            .border(1.dp, DogScColors.Hairline, shape),
    ) {
        content()
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 9.dp else 11.dp))
            .background(DogScColors.ControlWell)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                if (selected) DogScColors.Panel else Color.Transparent,
                animationSpec = tween(140),
                label = "segment-bg",
            )
            val fg by animateColorAsState(
                if (selected) DogScColors.PrimaryText else DogScColors.SecondaryText,
                animationSpec = tween(140),
                label = "segment-fg",
            )
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(if (compact) 7.dp else 9.dp))
                    .background(bg)
                    .then(
                        if (selected) Modifier.border(1.dp, DogScColors.Hairline, RoundedCornerShape(if (compact) 7.dp else 9.dp))
                        else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 5.dp else 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = fg,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = DogScColors.SecondaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(valueLabel(value), color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = DogScColors.PrimaryText,
                inactiveTrackColor = DogScColors.ControlWell,
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp).excludeSystemGestures(),
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { it() }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) DogScColors.PrimaryText else DogScColors.TertiaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = DogScColors.TertiaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DogScSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun DogScSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val offset by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "switch")
    Box(
        modifier = Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> DogScColors.ControlWell
                    checked -> DogScColors.Accent
                    else -> DogScColors.ControlWell
                }
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(if (checked || !enabled) Color.White else Color.White.copy(alpha = 0.96f)),
        )
    }
}

@Composable
fun ChoiceTile(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val bg = if (selected) DogScColors.SelectionWash else Color.Transparent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .border(
                1.dp,
                if (selected) DogScColors.PrimaryText.copy(alpha = 0.16f) else DogScColors.Hairline,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { it() }
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = DogScColors.PrimaryText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, color = DogScColors.TertiaryText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, null, tint = DogScColors.PrimaryText, modifier = Modifier.size(15.dp))
        }
    }
}

/** A compact horizontal value scrubber used by inspector knobs. */
@Composable
fun Scrubber(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
) {
    val current by rememberUpdatedState(onValueChange)
    val range = valueRange.endInclusive - valueRange.start
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(DogScColors.ControlWell)
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = dragAmount / size.width * range
                    current((value + delta).coerceIn(valueRange.start, valueRange.endInclusive))
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    current(valueRange.start + range * fraction)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(DogScColors.Accent.copy(alpha = 0.92f)),
        )
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        )
        Text(
            text = "${(value * 100).roundToInt()}%",
            color = if (fraction > 0.5f) Color.White else DogScColors.PrimaryText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(DogScColors.Hairline))
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = DogScColors.TertiaryText,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DogScColors.ControlWell)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = DogScColors.SecondaryText,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(color)
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit, size: Dp = 28.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) DogScColors.PrimaryText else DogScColors.Hairline,
                CircleShape,
            )
            .clickable(onClick = onClick),
    )
}
