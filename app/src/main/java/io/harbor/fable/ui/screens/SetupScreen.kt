package io.harbor.fable.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import io.harbor.fable.data.models.VulkanProbeResult
import io.harbor.fable.data.models.VulkanSource
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.nativebridge.VulkanProbe
import io.harbor.fable.ui.theme.FableBlue
import io.harbor.fable.ui.theme.FableBlueDim
import io.harbor.fable.ui.theme.TileTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.R
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.RecommendedKind
import io.harbor.fable.data.RecommendedStatus
import io.harbor.fable.data.SetupState
import io.harbor.fable.data.formatBytes
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableTextFaint
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTrack
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.PillRadius
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

private enum class SetupStep { DOWNLOAD, READY }

/**
 * First-open setup. Two steps on a plain black canvas: a download step that shows the real
 * progress of the recommended Wine, Box64, graphics driver (Turnip on Adreno, RADV Xclipse
 * elsewhere), DXVK and VKD3D-Proton packages (through [io.harbor.fable.data.SetupManager]), and a ready step that
 * hands over to the app. [onFinished] is called when the user continues or skips; the caller
 * persists it.
 *
 * There is no welcome page any more: a first install lands straight on the download step and
 * setup starts by itself (once per process, see [io.harbor.fable.data.SetupManager.installRecommendedOnce]).
 * What the welcome page showed — the device's GPU, Vulkan version and driver — now sits on the
 * download step under the progress ring. "Skip" still leaves setup for later.
 */
@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val setupManager = app.setupManager
    val setup by setupManager.state.collectAsStateWithLifecycle()
    val preparing by setupManager.installing.collectAsStateWithLifecycle()
    val fableUi = LocalFableUi.current

    var step by rememberSaveable { mutableStateOf(SetupStep.DOWNLOAD) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    // Downloads run as a foreground service; on Android 13+ its notification needs permission.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startDownloads(firstRun: Boolean = false) {
        fableUi.scope.launch {
            val result = if (firstRun) {
                // Null when this process already kicked setup off (an Activity recreation).
                setupManager.installRecommendedOnce() ?: return@launch
            } else {
                setupManager.installRecommended()
            }
            lastMessage = when {
                result.busy -> null
                result.failed.isNotEmpty() || (!result.catalogReachable && result.unavailable.isNotEmpty()) -> result.message
                else -> null
            }
        }
    }

    // First launch goes straight into setup: no welcome page, no "Set Up" tap. The permission
    // prompt for the download notification and the downloads themselves start right away.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoStarted && step == SetupStep.DOWNLOAD) {
            autoStarted = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            startDownloads(firstRun = true)
        }
    }

    SetupContent(
        step = step,
        setup = setup,
        preparing = preparing,
        message = lastMessage,
        onRetry = {
            lastMessage = null
            startDownloads()
        },
        onContinue = { step = SetupStep.READY },
        onSkip = onFinished,
        onFinish = onFinished,
    )
}

@Composable
private fun SetupContent(
    step: SetupStep,
    setup: SetupState,
    preparing: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(FableBg),
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val sign = if (forward) 1 else -1
                (
                    fadeIn(Motion.enter(Motion.Slow, delay = 60)) +
                        slideInHorizontally(Motion.enter(Motion.Entrance)) { sign * it / 6 }
                    ) togetherWith (
                    fadeOut(Motion.exit(Motion.Quick)) +
                        slideOutHorizontally(Motion.exit(Motion.Standard)) { -sign * it / 10 }
                    )
            },
            label = "setupStep",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                SetupStep.DOWNLOAD -> DownloadStep(
                    setup = setup,
                    preparing = preparing,
                    message = message,
                    onRetry = onRetry,
                    onContinue = onContinue,
                    onSkip = onSkip,
                )
                SetupStep.READY -> ReadyStep(setup = setup, onFinish = onFinish)
            }
        }
    }
}

// --- Device card (shown on the download step) ------------------------------------------------

/**
 * What this phone can actually run — the GPU, the Vulkan version and driver the system ships,
 * and a one-line verdict on whether DXVK 2.x / VKD3D-Proton (both need Vulkan 1.3) will work.
 * This used to be the welcome page; it now rides along under the progress ring so first launch
 * goes straight to setup. The probe runs off the main thread; the card fills in as results arrive.
 */
@Composable
private fun DeviceSummary() {
    var gpu by remember { mutableStateOf<DeviceGpuInfo?>(null) }
    var vulkan by remember { mutableStateOf<VulkanProbeResult?>(null) }
    LaunchedEffect(Unit) {
        gpu = withContext(Dispatchers.IO) { DeviceProbe.read() }
        vulkan = VulkanProbe.probe(VulkanSource.SYSTEM, null)
    }
    DeviceCapabilityCard(gpu = gpu, vulkan = vulkan)
}

/**
 * GPU hero row, three capability stats on toned gradient tiles (Vulkan, driver, extensions)
 * and a verdict line, on one glass card. Values morph in from a placeholder as the probe lands.
 */
@Composable
private fun DeviceCapabilityCard(gpu: DeviceGpuInfo?, vulkan: VulkanProbeResult?) {
    val device = vulkan?.primaryDevice
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(shape = shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column {
            MorphText(
                text = device?.name ?: gpu?.gpu?.takeIf { it != "Unknown" } ?: if (gpu == null) "Checking…" else "Unknown GPU",
                style = MaterialTheme.typography.titleMedium,
                color = FableText,
            )
            MorphText(
                text = listOfNotNull(gpu?.device?.takeIf { it.isNotBlank() }, gpu?.abi?.takeIf { it.isNotBlank() }, gpu?.sdk?.let { "API $it" })
                    .joinToString(" · ")
                    .ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = FableTextDim,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile(
                label = "Vulkan",
                value = when {
                    device != null -> device.apiGeneration
                    vulkan != null -> "—"
                    else -> "…"
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Driver",
                value = when {
                    device != null -> driverShortName(device.driverName, device.driverInfo, device.vendorName)
                    vulkan != null -> "—"
                    else -> "…"
                },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Extensions",
                value = when {
                    vulkan == null -> "…"
                    vulkan.ok -> vulkan.totalCount.toString()
                    else -> "—"
                },
                modifier = Modifier.weight(1f),
            )
        }
        val verdict = vulkanVerdict(vulkan)
        AnimatedContent(
            targetState = verdict,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
            label = "deviceVerdict",
        ) { (good, line) ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (good) FableText else FableTextDim)
        }
    }
}

/** One capability on a plain card: a small grey label over a large value that morphs in. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = FableTextDim, maxLines = 1)
        Spacer(Modifier.height(Spacing.xxs))
        MorphText(text = value, style = MaterialTheme.typography.titleMedium, color = FableText)
    }
}

/** Text that slides up and fades when it changes, so probe results arrive with motion. */
@Composable
private fun MorphText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 2 }) togetherWith
                (fadeOut(Motion.exit(Motion.Fast)) + slideOutVertically(Motion.exit(Motion.Fast)) { -it / 2 }) using
                SizeTransform(clip = false) { _, _ -> Motion.morph() }
        },
        label = "morphText",
    ) { current ->
        Text(current, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "radv" / "Mesa 24.1" / vendor, short enough for a stat tile. */
private fun driverShortName(driverName: String?, driverInfo: String?, vendor: String): String {
    val name = driverName?.trim().orEmpty()
    return when {
        name.isNotEmpty() && name.length <= 10 -> name.uppercase().takeIf { it == "RADV" } ?: name.replaceFirstChar { it.uppercase() }
        !driverInfo.isNullOrBlank() -> driverInfo.trim().substringBefore(' ').take(10)
        else -> vendor
    }
}

/** True with a positive line when DXVK 2.x / VKD3D-Proton can run (Vulkan 1.3), else a caution. */
private fun vulkanVerdict(vulkan: VulkanProbeResult?): Pair<Boolean, String> {
    val device = vulkan?.primaryDevice
    if (vulkan == null) return true to "Checking Vulkan…"
    if (device == null) return false to "No Vulkan device found. Games will need WineD3D"
    val parts = device.apiGeneration.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return if (major > 1 || (major == 1 && minor >= 3)) {
        true to "Vulkan ${device.apiGeneration}: ready for DXVK 2.x and VKD3D-Proton"
    } else {
        // Adreno needs Turnip; RADV Xclipse is only for Samsung's Xclipse GPUs.
        val driver = io.harbor.fable.nativebridge.GpuDetector.fromVulkan(device).let { id ->
            if (id.kind == io.harbor.fable.nativebridge.GpuKind.UNKNOWN) io.harbor.fable.nativebridge.GpuDetector.detect() else id
        }.recommendedFamily.displayName
        false to "Vulkan ${device.apiGeneration}: a $driver driver unlocks DXVK 2.x"
    }
}

/** The app mark, plain white on black. It settles into place once, without a halo. */
@Composable
private fun AppMark(size: androidx.compose.ui.unit.Dp = 120.dp) {
    val appear = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.settle()) }
    Image(
        painter = painterResource(R.drawable.ic_launcher_monochrome),
        contentDescription = null,
        colorFilter = ColorFilter.tint(FableText),
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value },
    )
}

// --- Step 2: download -------------------------------------------------------------------------

@Composable
private fun DownloadStep(
    setup: SetupState,
    preparing: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    // Only the required packages take part in setup; optional ones (FEX) are picked per container.
    val items = setup.required
    val tracked = items.filter { it.status != RecommendedStatus.UNAVAILABLE }
    val readyCount = items.count { it.status == RecommendedStatus.INSTALLED }
    val allDone = items.isNotEmpty() && !preparing && !setup.needsSetup
    val everythingReady = allDone && setup.unavailable.isEmpty()

    // When everything landed, move on by itself after a beat so the user sees the full ring.
    LaunchedEffect(everythingReady) {
        if (everythingReady) {
            delay(900)
            onContinue()
        }
    }

    SetupColumn {
        Spacer(Modifier.weight(0.6f))
        Staggered(index = 1) {
            Text(
                text = if (allDone) "Almost Done" else "Setting Up",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        val requiredCount = items.size.coerceAtLeast(RecommendedKind.entries.count { it.required })
        val indeterminate = preparing && tracked.isEmpty()
        // Just the ring — no status pill above it. The ring's own percentage and count label
        // are enough; the pill was clutter that clipped on narrow screens.
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ringSize = (maxWidth * 0.62f).coerceIn(RingMinSize, RingMaxSize)
            ProgressRing(
                progress = setup.progress,
                indeterminate = indeterminate,
                label = "$readyCount of $requiredCount",
                modifier = Modifier.size(ringSize),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = when {
                setup.pendingBytes > 0 -> "${formatBytes(setup.pendingBytes)} left"
                allDone -> "Downloaded"
                else -> " "
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xl))
        Staggered(index = 3) { DeviceSummary() }
        Spacer(Modifier.height(Spacing.md))
        AnimatedVisibility(visible = message != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Column {
                Spacer(Modifier.height(Spacing.md))
                NoticeCard(
                    icon = FableIcons.Error,
                    title = "Setup failed",
                    lines = listOfNotNull(message),
                    tint = FableError,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Staggered(index = 4) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = Triple(allDone, message != null, setup.unavailable.isNotEmpty()),
                    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                    label = "setupAction",
                ) { (done, failed, missing) ->
                    when {
                        done && missing -> FableButton(
                            text = "Continue Anyway",
                            primary = true,
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        failed -> FableButton(
                            text = "Try Again",
                            primary = true,
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Spacer(Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                TextAction(text = if (allDone) "Finish Later" else "Skip", onClick = onSkip)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** Overall progress as a thin white ring with the percentage in the middle. Real progress only. */
@Composable
private fun ProgressRing(progress: Float, indeterminate: Boolean, label: String, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.settle(), label = "ringProgress")
    val spin = remember { Animatable(0f) }
    LaunchedEffect(indeterminate) {
        if (indeterminate) {
            while (true) {
                spin.snapTo(0f)
                spin.animateTo(360f, androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing))
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = FableTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val sweep = if (indeterminate) 80f else 360f * animated
            val start = if (indeterminate) spin.value - 90f else -90f
            if (sweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(FableBlueDim, FableBlue, FableText, FableBlueDim)),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (indeterminate) "…" else "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.headlineLarge,
                color = FableText,
            )
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val RingMinSize = 168.dp
private val RingMaxSize = 240.dp

/**
 * Glass status capsule at the top of the download step. It drops in from the top edge and
 * unfolds from a dot to its full width; its text morphs (size and content) as the step moves
 * from package to package. A blue dot pulses inside while work is running.
 */
@Composable
private fun StatusPill(text: String, active: Boolean) {
    val unfold = remember { Animatable(0f) }
    LaunchedEffect(Unit) { unfold.animateTo(1f, Motion.slideInFromTop(Motion.Slow)) }
    val pulse = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "statusPulseAlpha",
    )
    val density = LocalDensity.current
    Row(
        Modifier
            .graphicsLayer {
                val u = unfold.value
                alpha = u
                translationY = (1f - u) * with(density) { -24.dp.toPx() }
                // Unfolds sideways from a dot into the capsule.
                scaleX = 0.35f + 0.65f * u
            }
            .glassSurface(shape = RoundedCornerShape(PillRadius))
            .animateContentSize(Motion.morph())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = if (active) pulseAlpha else 1f }
                .clip(CircleShape)
                .background(FableBlue),
        )
        Spacer(Modifier.width(Spacing.sm))
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 2 }) togetherWith
                    (fadeOut(Motion.exit(Motion.Fast)) + slideOutVertically(Motion.exit(Motion.Fast)) { -it / 2 }) using
                    SizeTransform(clip = false) { _, _ -> Motion.morph() }
            },
            label = "statusPillText",
        ) { current ->
            Text(current, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = FableText, maxLines = 1)
        }
    }
}

/** The thin blue line that draws down from the status pill to the top of the ring. */
@Composable
private fun PillExtension() {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.morph(Motion.Slow, delay = 220)) }
    Box(
        Modifier
            .size(width = 2.dp, height = 28.dp)
            .graphicsLayer {
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                scaleY = grow.value
            }
            .background(Brush.verticalGradient(listOf(FableBlue.copy(alpha = 0f), FableBlue.copy(alpha = 0.7f)))),
    )
}

/** Grows its content (the ring) out of the line above it: from the top centre, after the line. */
@Composable
private fun RingFromPill(content: @Composable () -> Unit) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.morph(Motion.Entrance, delay = 380)) }
    Box(
        Modifier.graphicsLayer {
            val g = grow.value
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            alpha = g
            scaleX = 0.6f + 0.4f * g
            scaleY = 0.6f + 0.4f * g
        },
    ) { content() }
}

// --- Step 3: ready ----------------------------------------------------------------------------

@Composable
private fun ReadyStep(setup: SetupState, onFinish: () -> Unit) {
    val missing = setup.required.filter { it.status != RecommendedStatus.INSTALLED }.map { it.kind.label }
    SetupColumn {
        Spacer(Modifier.weight(1f))
        Staggered(index = 0) { AppMark(size = 96.dp) }
        Spacer(Modifier.height(Spacing.xxxl))
        Staggered(index = 1) {
            Text(
                text = "Ready",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Staggered(index = 2) {
            Text(
                text = if (missing.isEmpty()) "" else "Get ${missing.joinToString(", ")} later in Assets.",
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
        Spacer(Modifier.weight(1.2f))
        Staggered(index = 3) {
            FableButton(
                text = "Open Fable",
                primary = true,
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

// --- Shared ---------------------------------------------------------------------------------

@Composable
private fun SetupColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** Quiet text button for secondary choices ("Skip"). */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = FableTextDim,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}
