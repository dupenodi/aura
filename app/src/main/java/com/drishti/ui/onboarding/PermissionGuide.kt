package com.drishti.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraPrimaryButton
import kotlinx.coroutines.delay

private val Neon = Color(0xFF4DE8FF)
private val NeonBright = Color(0xFFD5FAFF)
private val NeonDeep = Color(0xFFA06BFF)
private val Scrim = Color.Black.copy(alpha = 0.5f)
private val ScreenBg = Color(0xFFF2F2F7)
private val CardWhite = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1C1C1E)
private val InkSecondary = Color(0xFF8E8E93)
private val Hairline = Color(0xFFE5E5EA)
private val Glide = CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)

private enum class GuidePhase { Accessibility, Overlay, Done }

/**
 * First real task — theatrical guide that looks like Aura's pointer, without needing
 * accessibility yet. Deep-links into Android settings; advances when grants land.
 */
@Composable
fun PermissionGuide(
    permissions: PermissionState,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onComplete: () -> Unit,
) {
    var phase by remember {
        mutableStateOf(
            when {
                !permissions.accessibility -> GuidePhase.Accessibility
                !permissions.overlay -> GuidePhase.Overlay
                else -> GuidePhase.Done
            },
        )
    }

    // Advance as grants arrive (user returns from Settings).
    LaunchedEffect(permissions.accessibility, permissions.overlay) {
        when (phase) {
            GuidePhase.Accessibility -> if (permissions.accessibility) {
                delay(500)
                phase = if (permissions.overlay) GuidePhase.Done else GuidePhase.Overlay
            }
            GuidePhase.Overlay -> if (permissions.overlay) {
                delay(400)
                phase = GuidePhase.Done
            }
            GuidePhase.Done -> Unit
        }
        // Entering with both already granted.
        if (permissions.accessibility && permissions.overlay && phase != GuidePhase.Done) {
            phase = GuidePhase.Done
        }
    }

    LaunchedEffect(phase) {
        if (phase == GuidePhase.Done) {
            delay(650)
            onComplete()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
            label = "permPhase",
        ) { current ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (current) {
                    GuidePhase.Accessibility -> {
                        Text(
                            "first — let me see the screen",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "android keeps this behind a switch.\nfind Drishti and turn me on — i'll wait.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aura.TextDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                    GuidePhase.Overlay -> {
                        Text(
                            "one more — so i can sit on top",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "that's how the orb and the cursor appear.\ni never tap for you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aura.TextDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                    GuidePhase.Done -> {
                        Text(
                            "see? you're in.",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Aura.Cyan,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "okay. phone's ready — let's go.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Aura.TextDim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (phase) {
                GuidePhase.Accessibility -> SettingsMockGuide(
                    mode = MockMode.Accessibility,
                    granted = permissions.accessibility,
                )
                GuidePhase.Overlay -> SettingsMockGuide(
                    mode = MockMode.Overlay,
                    granted = permissions.overlay,
                )
                GuidePhase.Done -> SettingsMockGuide(
                    mode = if (permissions.overlay) MockMode.Overlay else MockMode.Accessibility,
                    granted = true,
                    celebrate = true,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (phase) {
            GuidePhase.Accessibility -> {
                AuraPrimaryButton(
                    text = if (permissions.accessibility) "nice…" else "open settings",
                    onClick = onRequestAccessibility,
                    enabled = !permissions.accessibility,
                )
                if (!permissions.accessibility) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "come back here after you flip the switch",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aura.TextGhost,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            GuidePhase.Overlay -> {
                AuraPrimaryButton(
                    text = if (permissions.overlay) "nice…" else "open settings",
                    onClick = onRequestOverlay,
                    enabled = !permissions.overlay,
                )
                if (!permissions.overlay) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "allow Drishti, then return",
                        style = MaterialTheme.typography.bodySmall,
                        color = Aura.TextGhost,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            GuidePhase.Done -> {
                // Brief beat before onComplete fires.
                Text(
                    "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Aura.TextGhost,
                )
            }
        }
    }
}

private enum class MockMode { Accessibility, Overlay }

@Composable
private fun SettingsMockGuide(
    mode: MockMode,
    granted: Boolean,
    celebrate: Boolean = false,
) {
    val reveal = remember { Animatable(0f) }
    val cursorT = remember { Animatable(0f) }
    val ripple = remember { Animatable(1f) }
    val cursorScale = remember { Animatable(1f) }
    val pulse by rememberInfiniteTransition(label = "permPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    LaunchedEffect(mode, granted, celebrate) {
        while (true) {
            reveal.snapTo(0f)
            cursorT.snapTo(0f)
            ripple.snapTo(1f)
            cursorScale.snapTo(1f)
            delay(100)
            reveal.animateTo(1f, tween(200, easing = Glide))
            cursorT.animateTo(1f, tween(480, easing = Glide))
            cursorScale.animateTo(0.82f, tween(90))
            ripple.snapTo(0f)
            cursorScale.animateTo(1f, tween(170))
            ripple.animateTo(1f, tween(520, easing = Glide))
            delay(if (celebrate || granted) 1600 else 1400)
            if (celebrate) break
            reveal.animateTo(0f, tween(240, easing = Glide))
            delay(280)
        }
    }

    val frame = RoundedCornerShape(32.dp)
    val screen = RoundedCornerShape(26.dp)

    Box(
        Modifier
            .width(200.dp)
            .height(360.dp)
            .shadow(24.dp, frame)
            .clip(frame)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF4A4A52), Color(0xFF1A1A1E), Color(0xFF0E0E12)),
                ),
            )
            .padding(5.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(screen)
                .background(ScreenBg),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val w = constraints.maxWidth.toFloat()
                // Target the Drishti / Allow row.
                val target = with(density) {
                    val top = 118.dp.toPx()
                    val h = 48.dp.toPx()
                    val pad = 14.dp.toPx()
                    Rect(pad, top, w - pad, top + h)
                }

                Column(Modifier.fillMaxSize()) {
                    // Status strip.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("9:41", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("••••", color = InkSecondary, fontSize = 10.sp)
                    }

                    Text(
                        text = when (mode) {
                            MockMode.Accessibility -> "Accessibility"
                            MockMode.Overlay -> "Display over other apps"
                        },
                        color = Ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )

                    Text(
                        text = when (mode) {
                            MockMode.Accessibility -> "Downloaded apps"
                            MockMode.Overlay -> "Allow display over other apps"
                        },
                        color = InkSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardWhite)
                            .border(0.5.dp, Hairline, RoundedCornerShape(12.dp)),
                    ) {
                        when (mode) {
                            MockMode.Accessibility -> {
                                MockAppRow("TalkBack", false)
                                HairlineDivider()
                                MockAppRow("Drishti", granted, highlight = true)
                                HairlineDivider()
                                MockAppRow("Select to Speak", false)
                            }
                            MockMode.Overlay -> {
                                MockAppRow("Chrome", false)
                                HairlineDivider()
                                MockAppRow("Drishti", granted, highlight = true)
                                HairlineDivider()
                                MockAppRow("Messages", false)
                            }
                        }
                    }
                }

                val label = when {
                    celebrate || granted -> "done"
                    mode == MockMode.Accessibility -> "Tap Drishti"
                    else -> "Tap Drishti"
                }
                val start = Offset(target.center.x - 60f, target.center.y - 50f)
                val end = target.center
                val cursor = Offset(
                    start.x + (end.x - start.x) * cursorT.value,
                    start.y + (end.y - start.y) * cursorT.value,
                )

                Canvas(Modifier.fillMaxSize()) {
                    if (reveal.value <= 0.01f) return@Canvas
                    drawFakeGuidance(
                        target = target,
                        reveal = reveal.value,
                        pulse = pulse,
                        cursor = cursor,
                        cursorScale = cursorScale.value,
                        ripple = ripple.value,
                    )
                }

                // Label chip.
                val densityPx = LocalDensity.current
                val chipTop = with(densityPx) { (target.top - 28.dp.toPx()) }
                val chipLeft = with(densityPx) {
                    (target.center.x - 36.dp.toPx()).coerceAtLeast(8.dp.toPx())
                }
                Box(
                    Modifier
                        .padding(
                            start = with(densityPx) { chipLeft.toDp() },
                            top = with(densityPx) { chipTop.toDp() },
                        )
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xF20B1020))
                        .border(0.5.dp, Neon.copy(alpha = 0.4f * reveal.value), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        label,
                        color = Neon.copy(alpha = reveal.value.coerceIn(0.2f, 1f)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Dynamic island.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 7.dp)
                    .width(64.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
            )
        }
    }
}

@Composable
private fun MockAppRow(name: String, on: Boolean, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (highlight) Aura.PurpleDeep else Color(0xFFD1D1D6)),
            contentAlignment = Alignment.Center,
        ) {
            if (highlight) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(listOf(Aura.CyanBright, Aura.Purple)),
                        ),
                )
            }
        }
        Text(
            name,
            color = if (highlight) Color(0xFF007AFF) else Ink,
            fontSize = 13.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        // Toggle.
        Box(
            Modifier
                .width(40.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (on) Brush.horizontalGradient(listOf(Aura.Cyan, Aura.Purple))
                    else Brush.linearGradient(listOf(Color(0xFFE5E5EA), Color(0xFFE5E5EA))),
                ),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 50.dp)
            .height(0.5.dp)
            .background(Hairline),
    )
}

private fun DrawScope.drawFakeGuidance(
    target: Rect,
    reveal: Float,
    pulse: Float,
    cursor: Offset,
    cursorScale: Float,
    ripple: Float,
) {
    val radius = 10.dp.toPx()
    val padded = Rect(
        target.left - 3.dp.toPx(),
        target.top - 3.dp.toPx(),
        target.right + 3.dp.toPx(),
        target.bottom + 3.dp.toPx(),
    )
    val hole = Path().apply {
        addRoundRect(RoundRect(padded, CornerRadius(radius, radius)))
    }
    clipPath(hole, clipOp = ClipOp.Difference) {
        drawRect(Scrim.copy(alpha = Scrim.alpha * reveal))
    }
    val spread = 3.dp.toPx() * pulse
    val ring = Rect(
        padded.left - spread,
        padded.top - spread,
        padded.right + spread,
        padded.bottom + spread,
    )
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

    if (ripple < 1f) {
        drawCircle(
            color = Neon.copy(alpha = 0.78f * (1f - ripple) * reveal),
            radius = 6.dp.toPx() + 28.dp.toPx() * ripple,
            center = cursor,
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    val d = 1.dp.toPx()
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 24 * d)
        lineTo(5.6f * d, 18.6f * d)
        lineTo(9.2f * d, 27 * d)
        lineTo(13.4f * d, 25 * d)
        lineTo(9.8f * d, 17 * d)
        lineTo(16.6f * d, 16.6f * d)
        close()
    }
    translate(cursor.x, cursor.y) {
        scale(cursorScale, cursorScale, pivot = Offset.Zero) {
            drawPath(
                path,
                NeonDeep.copy(alpha = ((110f + 60f * pulse) / 255f) * reveal * 0.5f),
                style = Stroke(width = 6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
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
        }
    }
}
