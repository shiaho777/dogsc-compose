package dev.dogsc.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import dev.dogsc.app.ui.theme.DogScDimens
import dev.dogsc.app.ui.theme.DogScMotion
import dev.dogsc.app.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import dev.dogsc.app.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/**
 * Press scale with a minimum visible duration (upstream AppChoiceFeedback): a fast
 * tap still shows the press for [DogScMotion.MinPressMs] before springing back, so
 * feedback never flashes subliminally. Collapses to 1f under reduced motion.
 */
@Composable
private fun rememberPressScale(interaction: MutableInteractionSource): Float {
    val reduced = rememberReducedMotion()
    val rawPressed by interaction.collectIsPressedAsState()
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(rawPressed) {
        if (rawPressed) {
            held = true
        } else if (held) {
            delay(DogScMotion.MinPressMs.toLong())
            held = false
        }
    }
    val target = if (held && !reduced) DogScDimens.PressScale else 1f
    val scale by animateFloatAsState(target, DogScMotion.snappy(), label = "press-scale")
    return scale
}

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
    val pressScale = rememberPressScale(interaction)
    val washAlpha by animateFloatAsState(
        if (pressed) DogScDimens.PressWashAlpha else 0f,
        DogScMotion.hoverTween(),
        label = "pill-wash",
    )
    Row(
        modifier = modifier
            .scale(pressScale)
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
            .background(DogScColors.PrimaryText.copy(alpha = washAlpha))
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = rememberPressScale(interaction)
    val background = if (selected) DogScColors.SelectionWash else Color.Transparent
    val washAlpha by animateFloatAsState(
        if (pressed) DogScDimens.PressWashAlpha else 0f,
        DogScMotion.hoverTween(),
        label = "icon-wash",
    )
    Box(
        modifier = modifier
            .scale(pressScale)
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(background)
            .background(DogScColors.PrimaryText.copy(alpha = washAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription?.let { dev.dogsc.app.ui.Loc.t(it) },
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
    val shadowColor = if (DogScColors.isDark) Color(0x66000000) else Color(0x14101A2E)
    Box(
        modifier = modifier
            .shadow(
                elevation = if (DogScColors.isDark) 14.dp else 10.dp,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor,
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

/**
 * Bridge between shared sliders and the editor's undo/preview machinery. The editor
 * provides an implementation backed by EditorSession's transient gesture channel;
 * screens without an editor session leave it null and sliders commit directly.
 */
interface SliderGestureSink {
    /** Mid-drag sample; the implementation opens the gesture channel on first call. */
    fun preview(apply: () -> Unit)
    /** Closes the drag as one undo entry (no-op when nothing changed). */
    fun end(label: String)
}

val LocalSliderGestureSink = androidx.compose.runtime.staticCompositionLocalOf<SliderGestureSink?> { null }

/**
 * Bar slider with the value embedded inside the filled track (upstream EditorBarSlider).
 * Drag to adjust, double-tap the value to type an exact number. Same signature as before, so
 * every inspector slider upgrades at once. Long-press fine mode is approximated by the precise
 * numeric entry on touch (no Shift key).
 */
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
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    val range = valueRange.endInclusive - valueRange.start
    val fraction = if (range > 0f) ((value - valueRange.start) / range).coerceIn(0f, 1f) else 0f
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinished by rememberUpdatedState(onValueChangeFinished)
    // The drag coroutine is keyed on valueRange only, so `value` captured at launch
    // goes stale after the first delta; always read the latest through updated state.
    val currentValue by rememberUpdatedState(value)
    // Long-press enters fine mode (touch substitute for desktop Shift-drag): 5x finer travel.
    var fine by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    // Inspector sliders used to commit one undo entry per pointer event. When a
    // gesture sink is provided (the editor), route the whole drag through the
    // session's transient gesture channel: continuous preview, one undo entry.
    val gestureSink = LocalSliderGestureSink.current
    var gestureOpen by remember { mutableStateOf(false) }
    // Mid-drag sample: continuous preview through the gesture channel, no undo spam.
    fun emitDrag(next: Float) {
        val clamped = next.coerceIn(valueRange.start, valueRange.endInclusive)
        if (gestureSink != null) {
            gestureOpen = true
            gestureSink.preview { currentChange(clamped) }
        } else {
            currentChange(clamped)
        }
    }
    // Discrete change (tap / exact entry): a single normal commit.
    fun emitDiscrete(next: Float) {
        currentChange(next.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    fun finish() {
        if (gestureOpen) {
            gestureSink?.end(label)
            gestureOpen = false
        }
        currentFinished?.invoke()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = DogScColors.SecondaryText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(3.dp))
        if (editing) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("取消", onClick = { editing = false }, compact = true, variant = PillVariant.Ghost)
                PillButton("确定", onClick = {
                    val parsed = editText.toFloatOrNull()
                    if (parsed != null) {
                        emitDiscrete(parsed)
                        currentFinished?.invoke()
                    }
                    editing = false
                }, compact = true, variant = PillVariant.Primary)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(DogScDimens.RadiusXS))
                    .background(DogScColors.ControlWell)
                    .excludeSystemGestures()
                    // TalkBack: the slider is a custom-drawn Box; without semantics it
                    // was invisible to screen readers and had no adjust actions.
                    .semantics {
                        contentDescription = label
                        stateDescription = valueLabel(value)
                        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                            current = value,
                            range = valueRange,
                        )
                        // TalkBack activation opens the exact-number entry, the accessible
                        // equivalent of dragging (this Compose version has no slider role
                        // or increase/decrease semantics actions).
                        onClick {
                            editText = currentValue.toString()
                            editing = true
                            true
                        }
                    }
                    .pointerInput(valueRange) {
                        detectHorizontalDragGestures(
                            onDragStart = { fine = false },
                            onDragEnd = { finish(); fine = false },
                            onDragCancel = { finish(); fine = false },
                        ) { change, dragAmount ->
                            change.consume()
                            val mult = if (fine) 0.2f else 1f
                            val delta = dragAmount / size.width * range * mult
                            emitDrag(currentValue + delta)
                        }
                    }
                    .pointerInput(valueRange) {
                        detectTapGestures(
                            onTap = { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                emitDiscrete(valueRange.start + range * f)
                                currentFinished?.invoke()
                            },
                            onDoubleTap = {
                                editText = value.toString()
                                editing = true
                            },
                            onLongPress = {
                                fine = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(
                            if (fine) DogScColors.Mint.copy(alpha = 0.92f)
                            else DogScColors.Accent.copy(alpha = 0.92f),
                        ),
                )
                Text(
                    text = if (fine) "精调 · ${valueLabel(value)}" else valueLabel(value),
                    color = if (fraction > 0.5f) DogScColors.OnAccent else DogScColors.PrimaryText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
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
    val reduced = rememberReducedMotion()
    val haptic = LocalHapticFeedback.current
    val offset by animateFloatAsState(
        if (checked) 1f else 0f,
        if (reduced) tween(0) else DogScMotion.snappyTween(),
        label = "switch",
    )
    val trackColor by animateColorAsState(
        when {
            !enabled -> DogScColors.ControlWell
            checked -> DogScColors.Accent
            else -> DogScColors.ControlWell
        },
        if (reduced) tween(0) else DogScMotion.snappyTween(),
        label = "switch-track",
    )
    // Track 38dp, thumb 18dp, 2dp inset each side -> 16dp travel. The animated
    // offset drives the thumb's start padding so it glides instead of snapping.
    Box(
        modifier = Modifier
            .width(38.dp)
            .height(22.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = 16.dp * offset)
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
    // Keep the visual swatch small but give the finger a ≥32dp target around it.
    val target = if (size < 32.dp) 32.dp else size
    Box(Modifier.size(target).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) DogScColors.PrimaryText else DogScColors.Hairline,
                    CircleShape,
                ),
        )
    }
}
