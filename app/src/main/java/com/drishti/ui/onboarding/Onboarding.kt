package com.drishti.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drishti.ui.theme.Aura
import com.drishti.ui.theme.AuraField
import com.drishti.ui.theme.PrimaryButton
import com.drishti.ui.theme.SecondaryButton
import kotlinx.coroutines.delay

/** Permission state as the app needs to see it. */
data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val microphone: Boolean,
) {
    /** Enough to guide (typing works without the microphone). */
    val essentials: Boolean get() = accessibility && overlay
    val all: Boolean get() = essentials && microphone
}

/** What onboarding asks the activity to do; each opens Android's own screen or dialog. */
class PermissionActions(
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val openOverlay: () -> Unit,
    val requestMicrophone: () -> Unit,
)

private enum class Step { Welcome, HowItWorks, Permissions, Try }

/**
 * Four calm screens: what aura is, how a step looks, three permissions, a first task.
 * Permissions are re-read every time the app comes back from Android's settings, so the
 * rows tick themselves off.
 */
@Composable
fun OnboardingFlow(
    permissions: PermissionState,
    actions: PermissionActions,
    onFinish: (firstTask: String?) -> Unit,
) {
    var step by remember { mutableStateOf(Step.Welcome) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Aura.Bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Progress(step.ordinal, Step.entries.size, Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 8 })
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                Step.Welcome -> Welcome { step = Step.HowItWorks }
                Step.HowItWorks -> HowItWorks { step = if (permissions.all) Step.Try else Step.Permissions }
                Step.Permissions -> PermissionsStep(permissions, actions, onDone = { step = Step.Try })
                Step.Try -> TryOne(onFinish)
            }
        }
    }
}

@Composable
private fun Progress(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == index) 28.dp else 8.dp, tween(260), label = "dot")
            Box(
                Modifier
                    .width(w)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (i <= index) Aura.Text else Aura.Line),
            )
        }
    }
}

// ---- 1. Welcome ----------------------------------------------------------------------------

/** Things people ask, in the languages they ask in. */
private val examples = listOf(
    "turn on bluetooth",
    "व्हाट्सऐप पर अम्मा को वीडियो कॉल करो",
    "make the letters bigger",
    "எழுத்தை பெரிதாக்கு",
    "wifi on karo",
    "ছবি কীভাবে পাঠাব?",
    "set an alarm for 6",
)

@Composable
private fun Welcome(onNext: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2200)
            i = (i + 1) % examples.size
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AuraField(Modifier.size(280.dp), energy = 0.45f)
        }
        Text("aura", style = MaterialTheme.typography.labelMedium, color = Aura.TextTertiary)
        Spacer(Modifier.height(10.dp))
        Text("help with your phone, in your language", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "just ask. i'll light up what to press and tell you what to do — you stay in control, i never tap for you.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = i,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) },
            label = "example",
        ) { idx ->
            Text(
                "“${examples[idx]}”",
                style = MaterialTheme.typography.titleMedium,
                color = Aura.Violet,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton("get started", onNext)
        Spacer(Modifier.height(16.dp))
    }
}

// ---- 2. How it works -----------------------------------------------------------------------

@Composable
private fun HowItWorks(onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Text("how it works", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            DemoPhone()
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Line(1, "hold the light at the edge and say what you need, or tap it to type")
            Line(2, "a ring shows you exactly what to press")
            Line(3, "you press it — then i show the next step")
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton("continue", onNext)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Line(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Aura.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = Aura.Text)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Aura.Text, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * A tiny settings screen the way a real session looks: light along the edges, the handle
 * resting on the right, the ring moving down the list and the step in the dock below.
 */
@Composable
private fun DemoPhone() {
    val rows = listOf("network & internet", "connected devices", "display & touch", "battery")
    val captions = listOf("tap network & internet", "now tap internet", "turn on wi-fi", "all done")
    var at by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1700)
            at = (at + 1) % rows.size
        }
    }
    val drift by rememberInfiniteTransition(label = "edge").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)),
        label = "drift",
    )
    val spectrum = Aura.Spectrum + Aura.Violet
    Box(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Aura.Surface)
            .drawWithContent {
                drawContent()
                // The edge glow: the aura's colours drifting round the frame.
                val shift = size.width * 2f * drift
                val brush = Brush.linearGradient(
                    spectrum,
                    start = Offset(shift, 0f),
                    end = Offset(shift + size.width, size.height),
                    tileMode = TileMode.Mirror,
                )
                val r = CornerRadius(28.dp.toPx())
                for (pass in 3 downTo 1) {
                    drawRoundRect(brush, cornerRadius = r, style = Stroke(2.dp.toPx() * (1 + pass * 1.6f)), alpha = 0.16f / pass)
                }
                drawRoundRect(brush, cornerRadius = r, style = Stroke(2.dp.toPx()), alpha = 0.85f)
            },
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            rows.forEachIndexed { i, label ->
                val ringed = i == at
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (ringed) Aura.Violet.copy(alpha = 0.14f) else Color.Transparent)
                        .border(if (ringed) 2.dp else 0.dp, if (ringed) Aura.Violet else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (ringed) Aura.Text else Aura.TextSecondary)
                }
            }
            Spacer(Modifier.height(18.dp))
            // The dock: the step, in big type, with the aura dot.
            AnimatedContent(targetState = at, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "caption") { idx ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Aura.SurfaceHi)
                        .border(1.dp, Aura.Line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Brush.sweepGradient(spectrum)))
                    Text(captions[idx], style = MaterialTheme.typography.bodyMedium, color = Aura.Text)
                }
            }
        }
        // The handle: a sliver of light on the right edge.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .size(width = 4.dp, height = 44.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(spectrum)),
        )
    }
}

// ---- 3. Permissions ------------------------------------------------------------------------

@Composable
private fun PermissionsStep(permissions: PermissionState, actions: PermissionActions, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text("three things to allow", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("android asks for each one on its own screen. come back here after each.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PermissionList(permissions, actions)
        }
        Spacer(Modifier.height(16.dp))
        when {
            permissions.all -> PrimaryButton("continue", onDone)
            permissions.essentials -> {
                PrimaryButton("allow the microphone", actions.requestMicrophone)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("continue without voice", onDone)
            }
            else -> PrimaryButton("continue", onDone, enabled = false)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The three permissions with their state — also used from settings. */
@Composable
fun PermissionList(permissions: PermissionState, actions: PermissionActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionCard(
            title = "see your screen",
            body = "so i know what to point at. i never tap or type for you, and i skip banking and health apps.",
            granted = permissions.accessibility,
            action = actions.openAccessibility,
            actionLabel = "open accessibility",
            hint = if (!permissions.accessibility) {
                "find aura under downloaded apps and turn it on. if the switch is greyed out, open app info, tap ⋮, then allow restricted settings."
            } else {
                null
            },
            hintAction = if (!permissions.accessibility) actions.openAppInfo else null,
            hintActionLabel = "open app info",
        )
        PermissionCard(
            title = "show over other apps",
            body = "so the glow and the ring can appear on top of whatever app you're in.",
            granted = permissions.overlay,
            action = actions.openOverlay,
            actionLabel = "allow",
        )
        PermissionCard(
            title = "hear you",
            body = "so you can just say what you need, in any language. only while you hold the glow.",
            granted = permissions.microphone,
            action = actions.requestMicrophone,
            actionLabel = "allow",
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    granted: Boolean,
    action: () -> Unit,
    actionLabel: String,
    hint: String? = null,
    hintAction: (() -> Unit)? = null,
    hintActionLabel: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Aura.CardShape)
            .background(Aura.Surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (granted) {
                Text("on", style = MaterialTheme.typography.labelMedium, color = Aura.Positive)
            }
        }
        Text(body, style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            Spacer(Modifier.height(4.dp))
            Pill(actionLabel, primary = true, onClick = action)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (hintAction != null && hintActionLabel != null) Pill(hintActionLabel, primary = false, onClick = hintAction)
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Aura.Bg else Aura.Text,
        modifier = Modifier
            .clip(Aura.PillShape)
            .background(if (primary) Aura.Text else Aura.SurfaceHi)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

// ---- 4. Try one ----------------------------------------------------------------------------

@Composable
private fun TryOne(onFinish: (String?) -> Unit) {
    val suggestions = listOf("turn on bluetooth", "make the text bigger", "video call someone on whatsapp", "show battery percentage")
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AuraField(Modifier.size(200.dp), energy = 0.6f)
        }
        Text("you're set", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "the glow lives at the edge of your screen. hold it and talk, or tap it to type. try one now:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                Text(
                    s,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Aura.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Aura.ButtonShape)
                        .background(Aura.Surface)
                        .clickable(role = Role.Button) { onFinish(s) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryButton("i'll try it myself", { onFinish(null) })
        Spacer(Modifier.height(16.dp))
    }
}
