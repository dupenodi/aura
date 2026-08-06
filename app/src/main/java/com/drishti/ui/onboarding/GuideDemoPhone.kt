package com.drishti.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishti.ui.theme.Aura
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Neon accents matching [com.drishti.overlay.PointerOverlay]. */
private val Neon = Color(0xFF4DE8FF)
private val NeonBright = Color(0xFFD5FAFF)
private val NeonDeep = Color(0xFFA06BFF)
private val Scrim = Color.Black.copy(alpha = 0.48f)
private val LabelBg = Color(0xF20B1020)
private val CursorEdge = Color(0x66101828)

private val Chassis = Color(0xFF1A1A1E)
private val ChassisRim = Color(0xFF3A3A42)
private val ChassisHighlight = Color(0xFF5C5C68)
private val ScreenBg = Color(0xFFF2F2F7)
private val CardWhite = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1C1C1E)
private val InkSecondary = Color(0xFF8E8E93)
private val Hairline = Color(0xFFE5E5EA)

/** Same glide curve as PointerOverlay's PathInterpolator(0.22, 0.9, 0.24, 1). */
private val GlideEasing = CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)

private enum class DemoKind { Text, Silent, WhatsApp }

private data class DemoBeat(
    val page: Int,
    val row: Int,
    val label: String,
    val homeGrid: Boolean = false,
)

private data class DemoScenario(
    val kind: DemoKind,
    val caption: String,
    val beats: List<DemoBeat>,
)

private val Scenarios = listOf(
    DemoScenario(
        kind = DemoKind.Text,
        caption = "hii. want to make the text bigger? i'll show you where.",
        beats = listOf(
            DemoBeat(0, 0, "Tap Display"),
            DemoBeat(1, 1, "Tap Font size"),
            DemoBeat(2, 2, "Tap Larger"),
        ),
    ),
    DemoScenario(
        kind = DemoKind.Silent,
        caption = "how do we put this on silent for dinner…",
        beats = listOf(
            DemoBeat(0, 1, "Tap Sound"),
            DemoBeat(1, 0, "Tap Silent"),
        ),
    ),
    DemoScenario(
        kind = DemoKind.WhatsApp,
        caption = "wait, where did whatsapp go. we'll find it.",
        beats = listOf(
            DemoBeat(0, 1, "Tap WhatsApp", homeGrid = true),
        ),
    ),
)

// Layout constants shared by fake UI + hit targets (content area below status bar).
private val StatusBarH = 28.dp
private val TitleBlockH = 36.dp
private val RowH = 42.dp
private val ListHPad = 10.dp
private val HomeGridTop = 36.dp
private val HomeCell = 44.dp
private val HomeGap = 12.dp
private val HomeHPad = 18.dp

/**
 * Mini phone that loops elder-hard tasks using the real overlay's cursor,
 * spotlight ring, scrim cutout and label chip.
 */
@Composable
fun GuideDemoPhone(
    modifier: Modifier = Modifier,
    onCaptionChange: (String) -> Unit = {},
) {
    var scenarioIndex by remember { mutableIntStateOf(0) }
    var beatIndex by remember { mutableIntStateOf(0) }
    val scenario = Scenarios[scenarioIndex]
    val beat = scenario.beats[beatIndex]

    val reveal = remember { Animatable(0f) }
    val cursorT = remember { Animatable(0f) }
    val ripple = remember { Animatable(1f) }
    val cursorScale = remember { Animatable(1f) }

    val pulse by rememberInfiniteTransition(label = "demoPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    LaunchedEffect(scenarioIndex) {
        onCaptionChange(Scenarios[scenarioIndex].caption)
    }

    LaunchedEffect(Unit) {
        while (true) {
            for (s in Scenarios.indices) {
                scenarioIndex = s
                val beats = Scenarios[s].beats
                for (b in beats.indices) {
                    beatIndex = b
                    reveal.snapTo(0f)
                    cursorT.snapTo(0f)
                    ripple.snapTo(1f)
                    cursorScale.snapTo(1f)
                    delay(120)
                    reveal.animateTo(1f, tween(200, easing = GlideEasing))
                    cursorT.animateTo(1f, tween(480, easing = GlideEasing))
                    cursorScale.animateTo(0.82f, tween(90))
                    ripple.snapTo(0f)
                    cursorScale.animateTo(1f, tween(170))
                    ripple.animateTo(1f, tween(520, easing = GlideEasing))
                    delay(1100)
                    reveal.animateTo(0f, tween(240, easing = GlideEasing))
                    delay(200)
                }
                delay(400)
            }
        }
    }

    val outerShape = RoundedCornerShape(36.dp)
    val screenShape = RoundedCornerShape(30.dp)

    Box(
        modifier
            .width(188.dp)
            .height(384.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft ground shadow under the device.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = 10.dp, vertical = 14.dp)
                .shadow(28.dp, outerShape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black)
                .background(Color.Transparent, outerShape),
        )

        // Volume rocker (left).
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-2).dp, y = (-36).dp)
                .width(3.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ChassisRim),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-2).dp, y = (-6).dp)
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ChassisRim),
        )
        // Power button (right).
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 2.dp, y = (-28).dp)
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ChassisRim),
        )

        // Chassis.
        Box(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .clip(outerShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(ChassisHighlight, Chassis, Color(0xFF0E0E12)),
                        start = Offset.Zero,
                        end = Offset(400f, 900f),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, ChassisRim),
                    ),
                    shape = outerShape,
                )
                .padding(5.dp),
        ) {
            // Screen glass.
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(screenShape)
                    .background(Color.Black)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), screenShape),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Content + status share one coordinate space for guidance targets.
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val density = LocalDensity.current
                        val contentW = constraints.maxWidth.toFloat()
                        val target = remember(beat, scenario.kind, contentW, density) {
                            targetRect(beat, scenario.kind, contentW, density)
                        }

                        Column(Modifier.fillMaxSize()) {
                            RealisticStatusBar(
                                light = scenario.kind != DemoKind.WhatsApp,
                            )
                            Box(Modifier.fillMaxSize()) {
                                AnimatedContent(
                                    targetState = scenario.kind to beat.page,
                                    transitionSpec = {
                                        (slideInHorizontally { it / 5 } + fadeIn(tween(220))) togetherWith
                                            (slideOutHorizontally { -it / 6 } + fadeOut(tween(160)))
                                    },
                                    label = "screen",
                                ) { (kind, page) ->
                                    FakeScreen(kind = kind, page = page)
                                }

                                // Summon orb — floating overlay presence.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 6.dp, bottom = 10.dp)
                                        .size(22.dp)
                                        .shadow(6.dp, CircleShape)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    Aura.CyanBright,
                                                    Aura.PurpleDeep,
                                                    Color(0xFF2A1361),
                                                ),
                                                center = Offset(8f, 7f),
                                            ),
                                        )
                                        .border(1.dp, Aura.Cyan.copy(alpha = 0.5f), CircleShape),
                                )
                            }
                        }

                        val start = Offset(target.center.x - 64f, target.center.y - 58f)
                        val end = target.center
                        val cursorPos = Offset(
                            start.x + (end.x - start.x) * cursorT.value,
                            start.y + (end.y - start.y) * cursorT.value,
                        )

                        Canvas(Modifier.fillMaxSize()) {
                            if (reveal.value <= 0.01f) return@Canvas
                            drawGuidance(
                                target = target,
                                reveal = reveal.value,
                                pulse = pulse,
                                cursor = cursorPos,
                                cursorScale = cursorScale.value,
                                ripple = ripple.value,
                            )
                        }

                        GuidanceLabel(
                            target = target,
                            text = beat.label,
                            reveal = reveal.value,
                            canvasWidth = contentW,
                        )
                    }

                    // Home indicator.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(
                                if (scenario.kind == DemoKind.WhatsApp) Color.Transparent
                                else ScreenBg,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(88.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (scenario.kind == DemoKind.WhatsApp) {
                                        Color.White.copy(alpha = 0.55f)
                                    } else {
                                        Color.Black.copy(alpha = 0.28f)
                                    },
                                ),
                        )
                    }
                }

                // Dynamic Island.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .width(72.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(0.5.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
                ) {
                    // Camera lens.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF1A2233), Color(0xFF050508)),
                                ),
                            )
                            .border(0.5.dp, Color(0xFF2A3344), CircleShape),
                    )
                }

                // Subtle glass glare across the top of the screen.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                            ),
                        ),
                )
            }
        }
    }
}

private fun targetRect(
    beat: DemoBeat,
    kind: DemoKind,
    contentW: Float,
    density: Density,
): Rect = with(density) {
    val status = StatusBarH.toPx()
    if (beat.homeGrid || kind == DemoKind.WhatsApp) {
        val gridTop = status + HomeGridTop.toPx()
        val cell = HomeCell.toPx()
        val gap = HomeGap.toPx()
        val hPad = HomeHPad.toPx()
        val col = beat.row % 3
        val left = hPad + col * (cell + gap)
        return Rect(left, gridTop, left + cell, gridTop + cell)
    }
    val rowH = RowH.toPx()
    val title = TitleBlockH.toPx()
    val hPad = ListHPad.toPx()
    val top = status + title + beat.row * rowH
    Rect(
        left = hPad,
        top = top,
        right = contentW - hPad,
        bottom = top + rowH - 4.dp.toPx(),
    )
}

@Composable
private fun GuidanceLabel(
    target: Rect,
    text: String,
    reveal: Float,
    canvasWidth: Float,
) {
    val density = LocalDensity.current
    val gap = with(density) { 7.dp.toPx() }
    val chipH = with(density) { 22.dp.toPx() }
    val approxW = with(density) { (text.length * 6.8f).dp.toPx() + 18.dp.toPx() }

    var top = target.top - gap - chipH
    if (top < with(density) { 30.dp.toPx() }) top = target.bottom + gap
    val left = (target.center.x - approxW / 2f)
        .coerceIn(
            with(density) { 6.dp.toPx() },
            (canvasWidth - approxW - with(density) { 6.dp.toPx() }).coerceAtLeast(0f),
        )

    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .alpha(reveal)
            .clip(RoundedCornerShape(50))
            .background(LabelBg)
            .border(0.5.dp, Neon.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = Neon,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun RealisticStatusBar(light: Boolean) {
    val fg = if (light) Ink else Color.White
    val muted = if (light) InkSecondary else Color.White.copy(alpha = 0.75f)

    Row(
        Modifier
            .fillMaxWidth()
            .height(StatusBarH)
            .padding(horizontal = 14.dp)
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "9:41",
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Signal bars.
            Row(
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(4.dp, 6.dp, 8.dp, 10.dp).forEachIndexed { i, h ->
                    Box(
                        Modifier
                            .width(2.5.dp)
                            .height(h)
                            .clip(RoundedCornerShape(0.5.dp))
                            .background(if (i < 3) fg else muted.copy(alpha = 0.35f)),
                    )
                }
            }
            // Wifi arcs (simplified).
            Box(
                Modifier
                    .size(11.dp)
                    .drawBehind {
                        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                        val c = Offset(size.width / 2f, size.height * 0.72f)
                        drawCircle(fg, radius = 1.3.dp.toPx(), center = c)
                        drawArc(
                            color = fg,
                            startAngle = 210f,
                            sweepAngle = 120f,
                            useCenter = false,
                            topLeft = Offset(c.x - 4.dp.toPx(), c.y - 5.dp.toPx()),
                            size = GeomSize(8.dp.toPx(), 8.dp.toPx()),
                            style = stroke,
                        )
                        drawArc(
                            color = fg,
                            startAngle = 210f,
                            sweepAngle = 120f,
                            useCenter = false,
                            topLeft = Offset(c.x - 6.5.dp.toPx(), c.y - 8.dp.toPx()),
                            size = GeomSize(13.dp.toPx(), 13.dp.toPx()),
                            style = stroke,
                        )
                    },
            )
            // Battery.
            Box(
                Modifier
                    .width(18.dp)
                    .height(9.dp)
                    .border(1.dp, fg, RoundedCornerShape(2.dp))
                    .padding(1.5.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.72f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(fg),
                )
            }
            Box(
                Modifier
                    .offset(x = (-1).dp)
                    .width(1.5.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(0.5.dp))
                    .background(fg),
            )
        }
    }
}

@Composable
private fun FakeScreen(kind: DemoKind, page: Int) {
    when (kind) {
        DemoKind.WhatsApp -> HomeApps()
        DemoKind.Silent -> SettingsFlow(silent = true, page = page)
        DemoKind.Text -> SettingsFlow(silent = false, page = page)
    }
}

@Composable
private fun SettingsFlow(silent: Boolean, page: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ScreenBg)
            .padding(horizontal = ListHPad),
    ) {
        val title = when {
            page == 0 -> "Settings"
            silent && page == 1 -> "Sound & vibration"
            !silent && page == 1 -> "Display"
            else -> "Font size"
        }
        Text(
            title,
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .height(TitleBlockH)
                .padding(start = 4.dp, top = 4.dp),
        )

        when {
            page == 0 -> {
                SettingsCard {
                    SettingsRow("Display", "Brightness, dark theme", Color(0xFF007AFF), showDivider = true)
                    SettingsRow("Sound & vibration", "Volume, haptics", Color(0xFFFF9500), showDivider = true)
                    SettingsRow("Notifications", "Banners, badges", Color(0xFFFF3B30), showDivider = false)
                }
            }
            silent && page == 1 -> {
                SettingsCard {
                    SettingsRow("Silent", "No rings or alerts", Color(0xFF8E8E93), showDivider = true, trailing = "On")
                    SettingsRow("Vibrate", "On ring", Color(0xFF34C759), showDivider = true)
                    SettingsRow("Media volume", "70%", Color(0xFF5856D6), showDivider = false)
                }
            }
            !silent && page == 1 -> {
                SettingsCard {
                    SettingsRow("Brightness", "Auto", Color(0xFFFFCC00), showDivider = true)
                    SettingsRow("Font size", "Default", Color(0xFF007AFF), showDivider = true)
                    SettingsRow("Dark theme", "Off", Color(0xFF5856D6), showDivider = false)
                }
            }
            else -> {
                SettingsCard {
                    SettingsRow("Smaller", "", Color(0xFF8E8E93), showDivider = true)
                    SettingsRow("Default", "", Color(0xFF8E8E93), showDivider = true)
                    SettingsRow("Larger", "Selected", Color(0xFF007AFF), showDivider = false, selected = true)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardWhite)
            .border(0.5.dp, Hairline, RoundedCornerShape(12.dp)),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    iconColor: Color,
    showDivider: Boolean,
    trailing: String? = null,
    selected: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(RowH - 1.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (selected) Color(0xFF007AFF) else Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        color = InkSecondary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(trailing, color = InkSecondary, fontSize = 10.sp)
            }
            Text("›", color = InkSecondary.copy(alpha = 0.7f), fontSize = 16.sp)
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 46.dp)
                    .height(0.5.dp)
                    .background(Hairline),
            )
        }
    }
}

@Composable
private fun HomeApps() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1B3A5F),
                        Color(0xFF3D6B8A),
                        Color(0xFFC4A882),
                        Color(0xFFE8D4B8),
                    ),
                ),
            ),
    ) {
        // Soft sun glow.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 20.dp, y = (-10).dp)
                .size(90.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFE4A8).copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = HomeGridTop - 8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeHPad),
                horizontalArrangement = Arrangement.spacedBy(HomeGap),
            ) {
                AppIcon("Phone", Color(0xFF34C759), glyph = AppGlyph.Phone)
                AppIcon("WhatsApp", Color(0xFF25D366), glyph = AppGlyph.Chat)
                AppIcon("Photos", Color(0xFFFF9500), glyph = AppGlyph.Image)
            }
            Spacer(Modifier.height(HomeGap))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomeHPad),
                horizontalArrangement = Arrangement.spacedBy(HomeGap),
            ) {
                AppIcon("Clock", Color(0xFF5856D6), glyph = AppGlyph.Clock)
                AppIcon("Messages", Color(0xFF007AFF), glyph = AppGlyph.Chat)
                AppIcon("Camera", Color(0xFF8E8E93), glyph = AppGlyph.Camera)
            }

            Spacer(Modifier.weight(1f))

            // Dock.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DockIcon(Color(0xFF34C759))
                DockIcon(Color(0xFF007AFF))
                DockIcon(Color(0xFFFF3B30))
                DockIcon(Color(0xFFAF52DE))
            }
        }
    }
}

private enum class AppGlyph { Phone, Chat, Image, Clock, Camera }

@Composable
private fun AppIcon(label: String, color: Color, glyph: AppGlyph) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(HomeCell),
    ) {
        Box(
            Modifier
                .size(HomeCell)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f)),
                        start = Offset(0f, 0f),
                        end = Offset(80f, 100f),
                    ),
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                drawGlyph(glyph)
            }
        }
        Text(
            label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

private fun DrawScope.drawGlyph(glyph: AppGlyph) {
    val w = size.width
    val h = size.height
    val white = Color.White
    when (glyph) {
        AppGlyph.Phone -> {
            drawRoundRect(
                color = white,
                topLeft = Offset(w * 0.32f, h * 0.12f),
                size = GeomSize(w * 0.36f, h * 0.76f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        AppGlyph.Chat -> {
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = w * 0.15f,
                        top = h * 0.18f,
                        right = w * 0.85f,
                        bottom = h * 0.68f,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    ),
                )
                moveTo(w * 0.28f, h * 0.68f)
                lineTo(w * 0.22f, h * 0.88f)
                lineTo(w * 0.45f, h * 0.68f)
            }
            drawPath(path, white)
        }
        AppGlyph.Image -> {
            drawRoundRect(
                color = white,
                topLeft = Offset(w * 0.15f, h * 0.22f),
                size = GeomSize(w * 0.7f, h * 0.56f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(white, radius = 2.5.dp.toPx(), center = Offset(w * 0.35f, h * 0.4f))
        }
        AppGlyph.Clock -> {
            drawCircle(white, radius = w * 0.36f, center = center, style = Stroke(2.dp.toPx()))
            drawLine(white, center, Offset(center.x, h * 0.28f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(white, center, Offset(w * 0.68f, h * 0.55f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        AppGlyph.Camera -> {
            drawRoundRect(
                color = white,
                topLeft = Offset(w * 0.12f, h * 0.3f),
                size = GeomSize(w * 0.76f, h * 0.42f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(white, radius = 4.dp.toPx(), center = center, style = Stroke(1.8.dp.toPx()))
        }
    }
}

@Composable
private fun DockIcon(color: Color) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color),
    )
}

private fun DrawScope.drawGuidance(
    target: Rect,
    reveal: Float,
    pulse: Float,
    cursor: Offset,
    cursorScale: Float,
    ripple: Float,
) {
    val radius = 10.dp.toPx()
    val padded = target.inflate(3.dp.toPx())

    val hole = Path().apply {
        addRoundRect(RoundRect(padded, CornerRadius(radius, radius)))
    }
    clipPath(hole, clipOp = ClipOp.Difference) {
        drawRect(Scrim.copy(alpha = Scrim.alpha * reveal))
    }

    val spread = 3.dp.toPx() * pulse
    val ring = padded.inflate(spread)
    drawRoundRect(
        color = Neon.copy(alpha = ((140f + 70f * pulse) / 255f) * reveal),
        topLeft = Offset(ring.left, ring.top),
        size = GeomSize(ring.width, ring.height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 5.dp.toPx()),
    )
    drawRoundRect(
        color = Neon.copy(alpha = reveal),
        topLeft = Offset(padded.left, padded.top),
        size = GeomSize(padded.width, padded.height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2.5.dp.toPx()),
    )

    drawNeonCursor(cursor, cursorScale, reveal, pulse, ripple)
}

private fun DrawScope.drawNeonCursor(
    tip: Offset,
    scaleFactor: Float,
    reveal: Float,
    pulse: Float,
    ripple: Float,
) {
    if (ripple < 1f) {
        drawCircle(
            color = Neon.copy(alpha = 0.78f * (1f - ripple) * reveal),
            radius = 6.dp.toPx() + 28.dp.toPx() * ripple,
            center = tip,
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    val d = 1.dp.toPx()
    val path = cursorPath(d)
    translate(tip.x, tip.y) {
        scale(scaleFactor, scaleFactor, pivot = Offset.Zero) {
            drawPath(
                path,
                NeonDeep.copy(alpha = ((110f + 60f * pulse) / 255f) * reveal * 0.5f),
                style = Stroke(width = 6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
            )
            drawPath(
                path,
                Neon.copy(alpha = 0.55f * reveal),
                style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
            )
            drawPath(
                path,
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to NeonBright.copy(alpha = reveal),
                        0.55f to Neon.copy(alpha = reveal),
                        1f to NeonDeep.copy(alpha = reveal),
                    ),
                    start = Offset.Zero,
                    end = Offset(17 * d, 27 * d),
                ),
            )
            drawPath(
                path,
                color = CursorEdge.copy(alpha = CursorEdge.alpha * reveal),
                style = Stroke(
                    width = d,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

private fun cursorPath(pxPerDp: Float): Path {
    fun x(v: Float) = v * pxPerDp
    fun y(v: Float) = v * pxPerDp
    return Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, y(24f))
        lineTo(x(5.6f), y(18.6f))
        lineTo(x(9.2f), y(27f))
        lineTo(x(13.4f), y(25f))
        lineTo(x(9.8f), y(17f))
        lineTo(x(16.6f), y(16.6f))
        close()
    }
}

private fun Rect.inflate(amount: Float): Rect = Rect(
    left = left - amount,
    top = top - amount,
    right = right + amount,
    bottom = bottom + amount,
)
