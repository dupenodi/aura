package com.drishti.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraGhostButton
import com.drishti.ui.theme.AuraOrb
import com.drishti.ui.theme.AuraPrimaryButton
import com.drishti.ui.theme.GlowLevel
import com.drishti.ui.theme.OrbSkin
import kotlinx.coroutines.delay

/** Permission state as the onboarding flow needs to see it. */
data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val microphone: Boolean,
)

private enum class OnboardingStep { Welcome, Ready, PermGuide }

/**
 * Welcome → pick presence / try a task → permissions as the first guided task
 * (theatrical pointer, then Android settings). Mic stays out of onboarding.
 */
@Composable
fun OnboardingFlow(
    permissions: PermissionState,
    orbSkin: OrbSkin,
    glow: GlowLevel,
    onOrbSkin: (OrbSkin) -> Unit,
    onGlow: (GlowLevel) -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onFinish: (firstTask: String?) -> Unit,
) {
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var pendingTask by remember { mutableStateOf<String?>(null) }

    fun proceed(task: String?) {
        val essentials = permissions.accessibility && permissions.overlay
        if (essentials) {
            onFinish(task)
        } else {
            pendingTask = task
            step = OnboardingStep.PermGuide
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1B1440), Aura.Bg),
                    radius = 1400f,
                ),
            )
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
            label = "onboarding",
        ) { current ->
            when (current) {
                OnboardingStep.Welcome -> WelcomeStep(onContinue = { step = OnboardingStep.Ready })
                OnboardingStep.Ready -> ReadyStep(
                    skin = orbSkin,
                    glow = glow,
                    onOrbSkin = onOrbSkin,
                    onGlow = onGlow,
                    onFinish = ::proceed,
                )
                OnboardingStep.PermGuide -> PermissionGuide(
                    permissions = permissions,
                    onRequestAccessibility = onRequestAccessibility,
                    onRequestOverlay = onRequestOverlay,
                    onComplete = { onFinish(pendingTask) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    val tasks = listOf(
        "make text bigger",
        "open whatsapp",
        "turn up brightness",
        "turn on wifi",
        "put phone on silent",
    )
    var taskIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1800)
            taskIndex = (taskIndex + 1) % tasks.size
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(top = 44.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "hii",
            style = MaterialTheme.typography.headlineLarge,
            color = Aura.TextHi,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "i'm here to help you learn how to",
            style = MaterialTheme.typography.titleLarge,
            color = Aura.TextMid,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(14.dp))

        AnimatedContent(
            targetState = taskIndex,
            transitionSpec = { fadeIn(tween(340)) togetherWith fadeOut(tween(240)) },
            label = "basic_tasks",
        ) { idx ->
            Text(
                tasks[idx],
                style = MaterialTheme.typography.titleLarge,
                color = Aura.Cyan,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 32.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(32.dp))
        GuideDemoPhone(onCaptionChange = {})

        Spacer(Modifier.weight(1f))
        Text(
            "you stay in control. i never tap for you.",
            style = MaterialTheme.typography.bodySmall,
            color = Aura.TextGhost,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        AuraPrimaryButton("okay, show me", onContinue)
    }
}

@Composable
private fun ReadyStep(
    skin: OrbSkin,
    glow: GlowLevel,
    onOrbSkin: (OrbSkin) -> Unit,
    onGlow: (GlowLevel) -> Unit,
    onFinish: (String?) -> Unit,
) {
    val suggestions = listOf(
        "make text bigger everywhere",
        "put my phone on silent",
        "open whatsapp",
        "turn wifi back on",
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 36.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "ready when you are.",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "this is me. pick a colour, then try one.",
                style = MaterialTheme.typography.bodyMedium,
                color = Aura.TextDim,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            AuraOrb(size = 72.dp, skin = skin, glow = glow)
            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbSkin.entries.forEach { option ->
                    val selected = option == skin
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to option.inner,
                                        0.56f to option.mid,
                                        1f to option.outer,
                                    ),
                                    center = Offset(16f, 14f),
                                    radius = 90f,
                                ),
                            )
                            .border(
                                width = 2.dp,
                                color = if (selected) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(22.dp),
                            )
                            .clickable { onOrbSkin(option) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clickable {
                        onGlow(GlowLevel.fromOrdinal((glow.ordinal + 1) % GlowLevel.entries.size))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "glow",
                    style = MaterialTheme.typography.bodySmall,
                    color = Aura.TextGhost,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    glow.label.lowercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Aura.Cyan,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1C1C28))
                    .clickable {
                        onGlow(GlowLevel.fromOrdinal((glow.ordinal + 1) % GlowLevel.entries.size))
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(glow.fraction)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Aura.AccentGradient),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "try one",
                style = MaterialTheme.typography.bodySmall,
                color = Aura.TextGhost,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Aura.SurfaceRaised)
                            .border(1.dp, Aura.LineBright, RoundedCornerShape(14.dp))
                            .clickable { onFinish(s) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(s, style = MaterialTheme.typography.bodyMedium, color = Aura.TextMid)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(Modifier.padding(horizontal = 28.dp, vertical = 18.dp)) {
            AuraGhostButton("skip for now", { onFinish(null) })
        }
    }
}
