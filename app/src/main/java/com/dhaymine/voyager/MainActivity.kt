package com.dhaymine.voyager

import android.content.Context
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================
// Morse config: change timings here (milliseconds)
// =====================================================================

object MorseConfig {
    const val MESSAGE = "VGR"
    const val DOT_MS = 200L
    const val DASH_MS = 600L
    const val ELEMENT_GAP_MS = 200L
    const val LETTER_GAP_MS = 600L
    const val WORD_GAP_MS = 1200L
    val loops = true
    val table = mapOf('V' to "...-", 'G' to "--.", 'R' to ".-.")
}

data class MorseStep(val on: Boolean, val ms: Long, val letterIndex: Int)

fun buildSteps(): List<MorseStep> {
    val steps = ArrayList<MorseStep>()
    val msg = MorseConfig.MESSAGE
    for (li in msg.indices) {
        val code = MorseConfig.table[msg[li]] ?: continue
        for (si in code.indices) {
            val dur = if (code[si] == '.') MorseConfig.DOT_MS else MorseConfig.DASH_MS
            steps.add(MorseStep(true, dur, li))
            val lastSymbol = si == code.length - 1
            val lastLetter = li == msg.length - 1
            val gap = if (!lastSymbol) MorseConfig.ELEMENT_GAP_MS
            else if (lastLetter) MorseConfig.WORD_GAP_MS
            else MorseConfig.LETTER_GAP_MS
            steps.add(MorseStep(false, gap, li))
        }
    }
    return steps
}

// =====================================================================
// Real flashlight (no CAMERA permission needed for the torch)
// =====================================================================

class TorchController(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String? = try {
        manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        null
    }

    val isAvailable: Boolean get() = cameraId != null

    fun set(on: Boolean) {
        val id = cameraId ?: throw IllegalStateException("This device has no flashlight.")
        try {
            manager.setTorchMode(id, on)
        } catch (e: Exception) {
            throw IllegalStateException("The flashlight is unavailable (is another app using the camera?).")
        }
    }
}

class MorseTransmitter(private val torch: TorchController) {
    var isRunning by mutableStateOf(false)
        private set
    var isLit by mutableStateOf(false)
        private set
    var activeLetter by mutableStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)

    val letters: List<Pair<Char, String>> =
        MorseConfig.MESSAGE.mapNotNull { c -> MorseConfig.table[c]?.let { c to it } }

    private val steps = buildSteps()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null
    private var gen = 0

    fun start() {
        if (isRunning) return
        error = null
        isRunning = true
        gen += 1
        val my = gen
        job = scope.launch {
            try {
                if (!torch.isAvailable) throw IllegalStateException("This device has no flashlight.")
                do {
                    for (s in steps) {
                        torch.set(s.on)
                        isLit = s.on
                        activeLetter = if (s.on) s.letterIndex else -1
                        delay(s.ms)
                    }
                } while (MorseConfig.loops)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (my == gen) error = e.message
            } finally {
                if (my == gen) {
                    runCatching { torch.set(false) }
                    isLit = false
                    activeLetter = -1
                    isRunning = false
                }
            }
        }
    }

    fun stop() {
        gen += 1
        job?.cancel()
        job = null
        runCatching { torch.set(false) }
        isLit = false
        activeLetter = -1
        isRunning = false
    }

    fun replay() {
        stop()
        start()
    }

    fun release() {
        stop()
        scope.cancel()
    }
}

// =====================================================================
// Theme
// =====================================================================

val Gold = Color(0xFFEBBD5C)
val GoldLight = Color(0xFFF7E6A8)
val GoldDark = Color(0xFFB88538)
val Dim = Color(0xC7D9C79E)
val GoldBrush: Brush get() = Brush.linearGradient(listOf(GoldLight, Gold, GoldLight, GoldDark))

object Fonts {
    var bold: FontFamily = FontFamily.SansSerif
    var medium: FontFamily = FontFamily.SansSerif
    var boldLoaded = false

    fun load(ctx: Context) {
        try {
            bold = FontFamily(Typeface.createFromAsset(ctx.assets, "bold.ttf"))
            boldLoaded = true
        } catch (e: Exception) {
        }
        try {
            medium = FontFamily(Typeface.createFromAsset(ctx.assets, "medium.ttf"))
        } catch (e: Exception) {
        }
    }
}

@Composable
fun AppText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    color: Color = Gold,
    tracking: Float = 0f,
    brush: Brush? = null,
    align: TextAlign = TextAlign.Center
) {
    val density = LocalDensity.current
    val sp = with(density) { size.dp.toSp() }
    val ls = with(density) { tracking.dp.toSp() }
    val base = TextStyle(
        fontFamily = if (bold) Fonts.bold else Fonts.medium,
        fontWeight = if (bold && !Fonts.boldLoaded) FontWeight.Bold else FontWeight.Normal,
        fontSize = sp,
        letterSpacing = ls,
        textAlign = align
    )
    if (brush != null) {
        Text(text, modifier, style = base.copy(brush = brush))
    } else {
        Text(text, modifier, color = color, style = base)
    }
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .height(40.dp)
            .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
            .clip(shape)
            .then(if (filled) Modifier.background(GoldBrush, shape) else Modifier)
            .border(1.5.dp, Gold.copy(alpha = 0.9f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppText(
            text, 14f, bold = true, tracking = 2f,
            color = if (filled) Color(0xFF1A1205) else Gold
        )
    }
}

@Composable
fun GearButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Gold, radius = r * 0.55f, center = c, style = Stroke(2.dp.toPx()))
            for (i in 0 until 8) {
                val a = i * PI / 4.0
                val s = Offset(c.x + (r * 0.75f * cos(a)).toFloat(), c.y + (r * 0.75f * sin(a)).toFloat())
                val e = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                drawLine(Gold, s, e, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun Footer() {
    AppText("Made by Dhaymine", 11f, color = Dim, tracking = 2f, modifier = Modifier.padding(bottom = 4.dp))
}

// =====================================================================
// Screen ratio: everything fits on one page, no scrolling
// =====================================================================

enum class RatioMode(val title: String) {
    AUTO("Auto"), CLASSIC("16:9"), MODERN("19.5:9"), CUSTOM("Custom")
}

@Composable
fun FitContainer(mode: RatioMode, custom: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val ratio = when (mode) {
            RatioMode.AUTO -> (h / w).coerceIn(1.65f, 2.4f)
            RatioMode.CLASSIC -> 16f / 9f
            RatioMode.MODERN -> 19.5f / 9f
            RatioMode.CUSTOM -> custom
        }
        val designW = 375f
        val designH = designW * ratio
        val scale = min(w / designW, h / designH)
        Box(
            Modifier
                .requiredSize(designW.dp, designH.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) { content() }
    }
}

// =====================================================================
// Golden Record drawn in code
// =====================================================================

object RecordArt {
    fun circle(x: Double, y: Double, r: Double): Path = Path().apply {
        addOval(Rect((x - r).toFloat(), (y - r).toFloat(), (x + r).toFloat(), (y + r).toFloat()))
    }

    fun line(vararg c: Double): Path = Path().apply {
        var i = 0
        while (i + 1 < c.size) {
            if (i == 0) moveTo(c[i].toFloat(), c[i + 1].toFloat()) else lineTo(c[i].toFloat(), c[i + 1].toFloat())
            i += 2
        }
    }

    fun arc(x: Double, y: Double, r: Double, a0: Double, a1: Double): Path {
        val p = Path()
        for (i in 0..8) {
            val a = (a0 + (a1 - a0) * i / 8.0) * PI / 180.0
            val px = (x + r * cos(a)).toFloat()
            val py = (y + r * sin(a)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        return p
    }

    val engraving: Path by lazy {
        val p = Path()
        p.addPath(circle(-0.42, -0.40, 0.30))
        p.addPath(circle(-0.42, -0.40, 0.10))
        p.addPath(circle(-0.42, -0.40, 0.03))
        for (k in 0 until 18) p.addPath(arc(-0.42, -0.40, 0.36, k * 20.0, k * 20.0 + 13.0))
        val rays = listOf(
            0.0 to 0.55, 25.0 to 0.36, 50.0 to 0.30, 75.0 to 0.30, 100.0 to 0.26,
            125.0 to 0.25, 155.0 to 0.26, 180.0 to 0.28, 205.0 to 0.30, 235.0 to 0.34,
            262.0 to 0.28, 285.0 to 0.38, 315.0 to 0.60
        )
        for ((a, l) in rays) {
            val rad = a * PI / 180.0
            p.addPath(line(-0.42, 0.52, -0.42 + l * cos(rad), 0.52 + l * sin(rad)))
        }
        val wave = ArrayList<Double>()
        for (i in 0 until 31) {
            wave.add(0.02 + i * 0.02)
            wave.add(-0.46 - 0.08 * abs(sin(i * 0.9 + 0.3)))
        }
        p.addPath(line(*wave.toDoubleArray()))
        p.addPath(line(0.06, -0.14, 0.16, 0.0, 0.19, -0.16, 0.30, 0.0, 0.33, -0.16, 0.44, 0.0, 0.47, -0.14))
        p.addPath(line(0.12, 0.14, 0.46, 0.14, 0.46, 0.34, 0.12, 0.34, 0.12, 0.14))
        for (i in 0 until 6) {
            val x = 0.12 + i * 0.012
            p.addPath(line(x, 0.14, x, 0.34))
        }
        p.addPath(line(0.12, 0.38, 0.46, 0.38, 0.46, 0.62, 0.12, 0.62, 0.12, 0.38))
        p.addPath(circle(0.29, 0.50, 0.09))
        p.addPath(circle(0.22, 0.80, 0.07))
        p.addPath(circle(0.45, 0.80, 0.07))
        p.addPath(line(0.29, 0.80, 0.38, 0.80))
        p.addPath(line(-0.80, 0.04, -0.30, 0.04))
        p.addPath(line(-0.34, -0.03, -0.28, -0.03, -0.28, 0.04, -0.34, 0.04, -0.34, -0.03))
        for (i in 0 until 5) {
            val y = 0.10 + i * 0.03
            p.addPath(line(-0.55, y, -0.30, y))
        }
        p
    }
}

@Composable
object RecordArt {
    /** Engraving in pixel space: disc centre (cx, cy), disc radius r. */
    fun engraving(cx: Float, cy: Float, r: Float): Path {
        fun fx(x: Double) = (cx + x * r).toFloat()
        fun fy(y: Double) = (cy + y * r).toFloat()
        val p = Path()
        fun circle(x: Double, y: Double, rad: Double) {
            p.addOval(Rect(fx(x - rad), fy(y - rad), fx(x + rad), fy(y + rad)))
        }
        fun line(vararg c: Double) {
            var i = 0
            while (i + 1 < c.size) {
                if (i == 0) p.moveTo(fx(c[i]), fy(c[i + 1])) else p.lineTo(fx(c[i]), fy(c[i + 1]))
                i += 2
            }
        }
        fun arc(x: Double, y: Double, rad: Double, a0: Double, a1: Double) {
            for (i in 0..8) {
                val a = (a0 + (a1 - a0) * i / 8.0) * PI / 180.0
                val px = fx(x + rad * cos(a))
                val py = fy(y + rad * sin(a))
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
        }

        circle(-0.42, -0.40, 0.30)
        circle(-0.42, -0.40, 0.10)
        circle(-0.42, -0.40, 0.03)
        for (k in 0 until 18) arc(-0.42, -0.40, 0.36, k * 20.0, k * 20.0 + 13.0)
        val rays = listOf(
            0.0 to 0.55, 25.0 to 0.36, 50.0 to 0.30, 75.0 to 0.30, 100.0 to 0.26,
            125.0 to 0.25, 155.0 to 0.26, 180.0 to 0.28, 205.0 to 0.30, 235.0 to 0.34,
            262.0 to 0.28, 285.0 to 0.38, 315.0 to 0.60
        )
        for ((a, l) in rays) {
            val rad = a * PI / 180.0
            line(-0.42, 0.52, -0.42 + l * cos(rad), 0.52 + l * sin(rad))
        }
        val wave = ArrayList<Double>()
        for (i in 0 until 31) {
            wave.add(0.02 + i * 0.02)
            wave.add(-0.46 - 0.08 * abs(sin(i * 0.9 + 0.3)))
        }
        line(*wave.toDoubleArray())
        line(0.06, -0.14, 0.16, 0.0, 0.19, -0.16, 0.30, 0.0, 0.33, -0.16, 0.44, 0.0, 0.47, -0.14)
        line(0.12, 0.14, 0.46, 0.14, 0.46, 0.34, 0.12, 0.34, 0.12, 0.14)
        for (i in 0 until 6) {
            val x = 0.12 + i * 0.012
            line(x, 0.14, x, 0.34)
        }
        line(0.12, 0.38, 0.46, 0.38, 0.46, 0.62, 0.12, 0.62, 0.12, 0.38)
        circle(0.29, 0.50, 0.09)
        circle(0.22, 0.80, 0.07)
        circle(0.45, 0.80, 0.07)
        line(0.29, 0.80, 0.38, 0.80)
        line(-0.80, 0.04, -0.30, 0.04)
        line(-0.34, -0.03, -0.28, -0.03, -0.28, 0.04, -0.34, 0.04, -0.34, -0.03)
        for (i in 0 until 5) {
            val y = 0.10 + i * 0.03
            line(-0.55, y, -0.30, y)
        }
        return p
    }
}

@Composable
fun GoldenRecord(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.98f
        val discPath = Path().apply { addOval(Rect(c, r)) }
        val lightGold = Color(0xFFFDE39C)
        clipPath(discPath) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFF2E1C0A),
                    0.45f to Color(0xFF69441A),
                    0.85f to Color(0xFFB08038),
                    1f to Color(0xFF785221),
                    center = c, radius = r
                ),
                radius = r, center = c
            )
            drawCircle(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.25f)),
                    start = Offset(c.x + 0.7f * r, c.y - 0.7f * r),
                    end = Offset(c.x - 0.7f * r, c.y + 0.7f * r)
                ),
                radius = r, center = c
            )
            for (i in 0 until 14) {
                drawCircle(
                    Color.Black.copy(alpha = 0.16f),
                    radius = r * (0.22f + i * 0.052f), center = c, style = Stroke(1f)
                )
            }
            drawPath(
                RecordArt.engraving(c.x, c.y, r),
                lightGold.copy(alpha = 0.92f),
                style = Stroke(width = r * 0.014f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawCircle(
                Color(0xFFFFD682).copy(alpha = 0.6f),
                radius = r - r * 0.005f, center = c, style = Stroke(r * 0.01f)
            )
            drawCircle(Color(0xFF0A0502), radius = r * 0.035f, center = c)
            drawCircle(lightGold, radius = r * 0.035f, center = c, style = Stroke(r * 0.007f))
        }
    }
}

// =====================================================================
// Background: black, a little blue, stars, golden record
// =====================================================================

data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val blue: Boolean)

@Composable
fun SpaceBackground(reduceMotion: Boolean) {
    val stars = remember {
        List(90) {
            Star(Random.nextFloat(), Random.nextFloat(), 0.5f + Random.nextFloat(),
                Random.nextFloat() * 6.28f, Random.nextInt(5) == 0)
        }
    }
    val transition = rememberInfiniteTransition(label = "bg")
    val spin = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(300_000, easing = LinearEasing)), label = "spin")
    val twinkle = transition.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(8_000, easing = LinearEasing)), label = "tw")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF040306)).clipToBounds()) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF1A2E73).copy(alpha = 0.38f), Color.Transparent),
                    center = Offset(0f, 0f), radius = size.height * 0.65f
                )
            )
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFF0D1F59).copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(0f, size.height), radius = size.height * 0.5f
                )
            )
        }
        GoldenRecord(
            Modifier
                .align(Alignment.Center)
                .offset(w * 0.28f, h * 0.30f)
                .requiredSize(w * 1.5f)
                .graphicsLayer {
                    rotationZ = if (reduceMotion) 0f else spin.value
                    alpha = 0.45f
                }
        )
        Canvas(Modifier.fillMaxSize()) {
            for (s in stars) {
                val a = if (reduceMotion) 0.6f else 0.35f + 0.35f * sin(twinkle.value + s.phase)
                val c = if (s.blue) Color(0xFF99BFFF) else Color(0xFFFFF5D9)
                drawCircle(c.copy(alpha = a), radius = s.r.dp.toPx(), center = Offset(s.x * size.width, s.y * size.height))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.65f))
                )
            )
        )
    }
}

// =====================================================================
// Voyager spacecraft (original illustration drawn in code)
// =====================================================================

@Composable
fun VoyagerArt(glint: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1.2f)) {
        val w = size.width
        val h = size.height
        fun pt(x: Float, y: Float) = Offset(w * x, h * y)
        val metal = Color(0xFF9E9E9E)
        drawCircle(
            Brush.radialGradient(
                listOf(Gold.copy(alpha = 0.55f * glint), Color.Transparent),
                center = pt(0.5f, 0.25f), radius = w * 0.55f
            ),
            radius = w * 0.55f, center = pt(0.5f, 0.25f)
        )
        val booms = Path().apply {
            moveTo(w * 0.42f, h * 0.55f); lineTo(w * 0.08f, h * 0.80f)
            moveTo(w * 0.58f, h * 0.55f); lineTo(w * 0.92f, h * 0.72f)
            moveTo(w * 0.50f, h * 0.58f); lineTo(w * 0.50f, h * 0.95f)
        }
        drawPath(booms, metal, style = Stroke(2.dp.toPx()))
        drawCircle(metal, radius = w * 0.035f, center = pt(0.915f, 0.715f))
        drawRect(metal, pt(0.05f, 0.77f), Size(w * 0.06f, h * 0.05f))
        drawRoundRect(Color(0xFF808080), pt(0.40f, 0.42f), Size(w * 0.20f, h * 0.16f), cornerRadius = CornerRadius(5.dp.toPx()))
        drawOval(Color(0xFFD1D1D1), pt(0.18f, 0.04f), Size(w * 0.64f, h * 0.42f))
        drawOval(
            Brush.linearGradient(
                listOf(Gold.copy(alpha = (0.35f + glint * 0.6f).coerceAtMost(1f)), Color(0xFF737373)),
                start = pt(0.25f, 0.08f), end = pt(0.75f, 0.42f)
            ),
            pt(0.22f, 0.075f), Size(w * 0.56f, h * 0.35f)
        )
        drawLine(Color(0xFFB3B3B3), pt(0.38f, 0.38f), pt(0.5f, 0.06f), 1.5.dp.toPx())
        drawLine(Color(0xFFB3B3B3), pt(0.62f, 0.38f), pt(0.5f, 0.06f), 1.5.dp.toPx())
        drawOval(Color.White, pt(0.47f, 0.03f), Size(w * 0.06f, h * 0.04f))
    }
}

// =====================================================================
// "ONE LIGHT DAY" lettering drawn as vector shapes (design space 805 x 872)
// =====================================================================

@Composable
fun OneLightDayLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(805f / 872f)) {
        val k = size.width / 805f
        withTransform({ scale(k, k, Offset.Zero) }) {
            val brush = Brush.linearGradient(
                listOf(GoldLight, Gold, GoldLight, GoldDark),
                start = Offset(0f, 0f), end = Offset(805f, 872f)
            )
            fun rect(x: Float, y: Float, w: Float, h: Float) = drawRect(brush, Offset(x, y), Size(w, h))
            fun poly(vararg c: Float) {
                val p = Path()
                p.moveTo(c[0], c[1])
                var i = 2
                while (i + 1 < c.size) {
                    p.lineTo(c[i], c[i + 1]); i += 2
                }
                p.close()
                drawPath(p, brush)
            }
            fun ring(cx: Float, cy: Float, r: Float) =
                drawCircle(brush, radius = r, center = Offset(cx, cy), style = Stroke(40f))
            fun arcStroke(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) {
                val p = Path()
                val n = 48
                for (i in 0..n) {
                    val a = (a0 + (a1 - a0) * i / n) * PI / 180.0
                    val x = cx + r * cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, brush, style = Stroke(width = 40f, cap = StrokeCap.Butt, join = StrokeJoin.Round))
            }
            // ONE
            ring(324.5f, 138f, 121.5f)
            rect(483f, 0f, 40f, 276f); rect(592f, 0f, 40f, 276f)
            poly(483f, 0f, 530f, 0f, 632f, 276f, 585f, 276f)
            rect(655f, 0f, 40f, 276f); rect(655f, 0f, 150f, 40f)
            rect(655f, 118f, 150f, 40f); rect(655f, 236f, 150f, 40f)
            // LIGHT
            rect(0f, 300f, 40f, 276f); rect(0f, 536f, 102f, 40f)
            rect(127f, 300f, 38f, 276f)
            arcStroke(324.5f, 438f, 121.5f, 325f, 0f); rect(324f, 418f, 142f, 40f)
            rect(483f, 300f, 40f, 276f); rect(592f, 300f, 40f, 276f); rect(483f, 418f, 149f, 40f)
            rect(655f, 300f, 150f, 40f); rect(710f, 300f, 40f, 276f)
            // DAY
            rect(127f, 598f, 40f, 273f); rect(127f, 598f, 79f, 40f); rect(127f, 831f, 79f, 40f)
            arcStroke(206.5f, 734.5f, 116.5f, 270f, 450f)
            rect(360f, 598f, 40f, 273f)
            poly(360f, 598f, 420f, 598f, 531f, 871f, 493f, 871f)
            rect(400f, 773f, 52f, 40f)
            poly(445f, 598f, 482f, 598f, 593f, 871f, 555f, 871f)
            poly(595f, 598f, 633f, 598f, 574f, 745f, 536f, 745f)
        }
    }
}

// =====================================================================
// Screens
// =====================================================================

enum class Stage { Opening, Transmit, Complete }
enum class Overlay { None, Learn, Settings }

@Composable
fun TopBar(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        GearButton(onSettings)
    }
}

@Composable
fun OpeningScreen(onBegin: () -> Unit, onLearn: () -> Unit, onSettings: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(1400)) }
    Column(
        Modifier.fillMaxSize().graphicsLayer { alpha = appear.value },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(onSettings)
        Spacer(Modifier.weight(1f))
        VoyagerArt(0.7f, Modifier.width(120.dp))
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(240.dp), horizontalAlignment = Alignment.End) {
            AppText("VOYAGER", 17f, bold = true, tracking = 4f, brush = GoldBrush)
            Spacer(Modifier.height(8.dp))
            OneLightDayLogo(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GoldButton("BEGIN", Modifier.fillMaxWidth(), true, onBegin)
            GoldButton("LEARN MORE", Modifier.fillMaxWidth(), false, onLearn)
        }
        Spacer(Modifier.weight(1f))
        AppText(
            "Independent fan project. Not affiliated with or endorsed by NASA.", 10f,
            color = Dim, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        Footer()
    }
}

@Composable
fun TransmitScreen(
    tx: MorseTransmitter,
    onSkip: () -> Unit,
    onLearn: () -> Unit,
    onSettings: () -> Unit
) {
    var showWarning by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    val lit = tx.isLit

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(onSettings)
        AppText("SHINE BRIGHT\nFOR ONE LIGHT-DAY", 22f, bold = true, tracking = 2f, brush = GoldBrush)
        AppText(
            "Point your phone at the sky. Your flashlight sends V-G-R in Morse code toward Voyager 1.",
            12f, color = Dim, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp)
        )
        Spacer(Modifier.weight(1f))
        VoyagerArt(if (lit) 1f else 0.15f, Modifier.width(120.dp))
        Box(
            Modifier
                .width(3.dp).height(26.dp)
                .graphicsLayer { alpha = if (lit) 1f else 0.12f }
                .background(Brush.verticalGradient(listOf(Gold, Color.Transparent)))
        )
        Box(
            Modifier
                .size(44.dp)
                .graphicsLayer { alpha = if (lit) 1f else 0.15f }
                .background(Brush.radialGradient(listOf(Gold, Color.Transparent)), CircleShape)
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            tx.letters.forEachIndexed { i, (ch, code) ->
                val c = if (tx.activeLetter == i) GoldLight else Dim.copy(alpha = 0.55f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText(ch.toString(), 30f, bold = true, color = c)
                    AppText(code.replace('.', '•').replace('-', '–'), 15f, bold = true, color = c, tracking = 2f)
                }
            }
        }
        AppText(
            if (tx.isRunning) "TRANSMITTING…" else "READY", 11f,
            bold = true, tracking = 3f, modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tx.isRunning) {
                GoldButton("STOP", Modifier.fillMaxWidth(), true) { tx.stop() }
            } else {
                GoldButton("START", Modifier.fillMaxWidth(), true) {
                    if (acknowledged) tx.start() else showWarning = true
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GoldButton("REPLAY", Modifier.weight(1f), false) {
                    if (acknowledged) tx.replay() else showWarning = true
                }
                GoldButton("SKIP", Modifier.weight(1f), false) { tx.stop(); onSkip() }
            }
            GoldButton("LEARN MORE", Modifier.fillMaxWidth(), false, onLearn)
        }
        Spacer(Modifier.weight(1f))
        AppText(
            "⚠ Do not look directly at the LED. Avoid flashing lights near people with photosensitive conditions.",
            10f, color = Gold.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
        )
        Footer()
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Safety notice") },
            text = {
                Text("The flashlight will flash. Don't look directly at it, and don't use it near anyone sensitive to flashing lights.")
            },
            confirmButton = {
                TextButton(onClick = { showWarning = false; acknowledged = true; tx.start() }) { Text("I understand") }
            },
            dismissButton = { TextButton(onClick = { showWarning = false }) { Text("Cancel") } }
        )
    }
    tx.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { tx.error = null },
            title = { Text("Flashlight problem") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { tx.error = null }) { Text("OK") } }
        )
    }
}

@Composable
fun CompleteScreen(onReplay: () -> Unit, onLearn: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(onSettings)
        Spacer(Modifier.weight(1f))
        VoyagerArt(0.9f, Modifier.width(170.dp))
        AppText(
            "YOUR LIGHT IS\nON ITS WAY", 26f, bold = true, tracking = 2f, brush = GoldBrush,
            modifier = Modifier.padding(top = 24.dp)
        )
        AppText(
            "Light takes one day to reach Voyager 1 at this distance, and one more day for a reply to come back.",
            13f, color = Dim, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp)
        )
        Spacer(Modifier.weight(1f))
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GoldButton("REPLAY", Modifier.fillMaxWidth(), true, onReplay)
            GoldButton("LEARN MORE", Modifier.fillMaxWidth(), false, onLearn)
        }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

// =====================================================================
// Overlays: Learn more + Settings
// =====================================================================

@Composable
fun OverlayScaffold(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08070B))
            .systemBarsPadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppText(title, 18f, bold = true, brush = GoldBrush, align = TextAlign.Start, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done", color = Gold) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun Section(title: String, body: String) {
    AppText(title, 13f, bold = true, tracking = 3f, align = TextAlign.Start, modifier = Modifier.padding(top = 20.dp))
    Spacer(Modifier.height(8.dp))
    AppText(body, 15f, color = Color.White.copy(alpha = 0.9f), align = TextAlign.Start)
}

@Composable
fun LearnOverlay(onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    OverlayScaffold("Learn More", onClose) {
        Section("VOYAGER 1", "Launched on September 5, 1977, Voyager 1 flew past Jupiter (1979) and Saturn (1980), then kept going. It entered interstellar space in 2012 and is the most distant human-made object. It carries the Golden Record, a message from Earth.")
        Section("WHAT IS A LIGHT-DAY?", "Light travels about 25.9 billion km (over 16 billion miles) in one day. On November 18, 2026, Voyager 1 reaches that distance from Earth. A radio signal then needs a day to arrive, and a reply needs another day: a 48-hour round trip.")
        Section("MORSE CODE: V-G-R", "V = • • • –    G = – – •    R = • – •\nVGR is Voyager's call sign. Dot = short flash, dash = long flash (three times a dot).")
        Section("WHY SEND LIGHT?", "NASA invited people everywhere to point a light at the sky after sunset on November 17 and flash V-G-R, a symbolic wave of golden light honoring the mission. A flashlight beam will not literally reach Voyager; it is a shared gesture.")
        Section("ABOUT THIS APP", "An independent project inspired by NASA's \"Shine Bright for Voyager's Light-Day\". It is not affiliated with or endorsed by NASA. Made by Dhaymine.")
        Spacer(Modifier.height(20.dp))
        Box(Modifier.clickable { uri.openUri("https://science.nasa.gov/mission/voyager/voyager-1/shine-bright-for-voyagers-light-day/") }) {
            AppText("NASA: SHINE BRIGHT FOR VOYAGER'S LIGHT-DAY", 13f, bold = true, align = TextAlign.Start)
        }
    }
}

@Composable
fun SettingsOverlay(
    mode: RatioMode,
    custom: Float,
    onMode: (RatioMode) -> Unit,
    onCustom: (Float) -> Unit,
    onClose: () -> Unit
) {
    OverlayScaffold("Settings", onClose) {
        AppText("SCREEN SHAPE (HEIGHT / WIDTH)", 13f, bold = true, tracking = 2f, align = TextAlign.Start, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatioMode.values().forEach { m ->
                GoldButton(m.title, Modifier.weight(1f), filled = m == mode) { onMode(m) }
            }
        }
        Spacer(Modifier.height(12.dp))
        val explanation = when (mode) {
            RatioMode.AUTO -> "Fits the page to your screen automatically. Try this first."
            RatioMode.CLASSIC -> "16:9, for older phones with a short screen."
            RatioMode.MODERN -> "19.5:9, for modern tall screens."
            RatioMode.CUSTOM -> "Drag the slider until the whole page fits without anything cut off."
        }
        AppText(explanation, 14f, color = Dim, align = TextAlign.Start)
        if (mode == RatioMode.CUSTOM) {
            Spacer(Modifier.height(16.dp))
            AppText(String.format(Locale.US, "Ratio: %.2f", custom), 14f, color = Color.White, align = TextAlign.Start)
            Slider(
                value = custom,
                onValueChange = onCustom,
                valueRange = 1.65f..2.3f,
                colors = SliderDefaults.colors(
                    thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Gold.copy(alpha = 0.3f)
                )
            )
        }
        Spacer(Modifier.height(24.dp))
        GoldButton("RESET TO AUTO", Modifier.fillMaxWidth(), false) { onMode(RatioMode.AUTO); onCustom(1.78f) }
    }
}

// =====================================================================
// Root
// =====================================================================

fun reduceMotionEnabled(ctx: Context): Boolean =
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun VoyagerApp(tx: MorseTransmitter) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("voyager", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching { RatioMode.valueOf(prefs.getString("mode", "AUTO") ?: "AUTO") }.getOrDefault(RatioMode.AUTO))
    }
    var custom by remember { mutableStateOf(prefs.getFloat("custom", 1.78f)) }
    var stage by remember { mutableStateOf(Stage.Opening) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    val view = LocalView.current

    LaunchedEffect(tx.isRunning) { view.keepScreenOn = tx.isRunning }
    LaunchedEffect(stage) { if (stage != Stage.Transmit) tx.stop() }
    LaunchedEffect(overlay) { if (overlay != Overlay.None) tx.stop() }
    BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

    Box(Modifier.fillMaxSize()) {
        SpaceBackground(reduceMotionEnabled(ctx))
        FitContainer(mode, custom) {
            Crossfade(targetState = stage, animationSpec = tween(600), label = "stage") { s ->
                when (s) {
                    Stage.Opening -> OpeningScreen(
                        onBegin = { stage = Stage.Transmit },
                        onLearn = { overlay = Overlay.Learn },
                        onSettings = { overlay = Overlay.Settings }
                    )
                    Stage.Transmit -> TransmitScreen(
                        tx = tx,
                        onSkip = { stage = Stage.Complete },
                        onLearn = { overlay = Overlay.Learn },
                        onSettings = { overlay = Overlay.Settings }
                    )
                    Stage.Complete -> CompleteScreen(
                        onReplay = { stage = Stage.Transmit },
                        onLearn = { overlay = Overlay.Learn },
                        onSettings = { overlay = Overlay.Settings }
                    )
                }
            }
        }
        when (overlay) {
            Overlay.Learn -> LearnOverlay { overlay = Overlay.None }
            Overlay.Settings -> SettingsOverlay(
                mode = mode,
                custom = custom,
                onMode = { mode = it; prefs.edit().putString("mode", it.name).apply() },
                onCustom = { custom = it; prefs.edit().putFloat("custom", it).apply() },
                onClose = { overlay = Overlay.None }
            )
            Overlay.None -> {}
        }
    }
}

class MainActivity : ComponentActivity() {
    private var tx: MorseTransmitter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        Fonts.load(this)
        val transmitter = MorseTransmitter(TorchController(this))
        tx = transmitter
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { VoyagerApp(transmitter) }
        }
    }

    // Safety: LED off when the app leaves the foreground.
    override fun onStop() {
        super.onStop()
        tx?.stop()
    }

    override fun onDestroy() {
        tx?.release()
        super.onDestroy()
    }
}
