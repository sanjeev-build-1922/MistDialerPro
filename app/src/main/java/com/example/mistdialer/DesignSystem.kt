package com.example.mistdialer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Immutable
object NothingTokens {
    // MATERIAL A — DEEP FOUNDATION
    val BgFoundation = Color(0xFF000000)
    val BgElevated = Color(0xFF0A0A0E)
    val BgCanvas = Color(0xFF050507)
    val DotGridColor = Color(0x12FFFFFF)

    // MATERIAL B — FROSTED MIST
    val GlassBBg = Color(0xCC18181D)
    val GlassBBorderTop = Color(0x24FFFFFF)

    // MATERIAL C — SOFT GLASS
    val GlassCBg = Color(0x9926262C)
    val GlassCBorder = Color(0x18FFFFFF)

    // MATERIAL D — FOCAL GLASS
    val GlassDBg = Color(0xF01C1C22)
    val GlassDBorderTop = Color(0x36FFFFFF)

    // TEXT & ACCENTS
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF94949E)
    val TextMuted = Color(0xFF52525C)
    val NothingRed = Color(0xFFD71921)
    val NothingRedSoft = Color(0x2ED71921)
    val NothingRedBorder = Color(0x7AD71921)
    val EmeraldActive = Color(0xFF34D399)
    val AmberHold = Color(0xFFFBBF24)
}

/**
 * Pre-allocated reusable shapes to eliminate per-frame GC allocations during LazyColumn scrolling.
 */
object MistShapes {
    val Pill = RoundedCornerShape(percent = 50)
    val Radius12 = RoundedCornerShape(12.dp)
    val Radius16 = RoundedCornerShape(16.dp)
    val Radius18 = RoundedCornerShape(18.dp)
    val Radius20 = RoundedCornerShape(20.dp)
    val Radius22 = RoundedCornerShape(22.dp)
    val Radius24 = RoundedCornerShape(24.dp)
    val Radius28 = RoundedCornerShape(28.dp)
    val Radius32 = RoundedCornerShape(32.dp)
    val Radius36 = RoundedCornerShape(36.dp)
}

/**
 * 120FPS ZERO-LAG DOT-MATRIX BACKGROUND
 * Caches the static dot Path once per size change; zero per-frame allocations.
 */
@Composable
fun DotMatrixBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // 120Hz Hardware-Tiled Shader: zero Path parsing, zero per-frame loops!
    val tiledBrush = remember {
        val tileSize = 45 // 15dp tile at typical 3x density
        val dotRadius = 2.6f
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(tileSize, tileSize)
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = Paint().apply {
            color = NothingTokens.DotGridColor
            isAntiAlias = true
        }
        canvas.drawCircle(Offset(tileSize / 2f, tileSize / 2f), dotRadius, paint)
        ShaderBrush(
            ImageShader(
                image = bitmap,
                tileModeX = TileMode.Repeated,
                tileModeY = TileMode.Repeated
            )
        )
    }

    Box(
        modifier = modifier
            .background(NothingTokens.BgFoundation)
            .background(tiledBrush),
        content = content
    )
}

@Immutable
private data class CorridorDotPoint(
    val x: Float,
    val y: Float,
    val v: Float,
    val baseWeight: Float
)

/**
 * ULTRA-FAST 120FPS DIRECTIONAL DOT-MATRIX WAVE OVERLAY FOR INCOMING CALL STRETCH GESTURE
 * Pre-computes the ~100 corridor dots in `drawWithCache` and uses fast algebraic smoothstep
 * math instead of expensive per-frame nested loops and `exp()` calls.
 */
@Composable
fun DirectionalStretchDotWaveCanvas(
    wavePhase: Float,
    stretchProgress: Float,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val stepPx = 15.dp.toPx()
                val baseRadius = 1.dp.toPx()
                val extraScalePx = 1.65.dp.toPx()
                val stretchBoostPx = 0.75.dp.toPx()

                val centerX = size.width / 2f
                val startY = size.height * 0.85f
                val endY = size.height * 0.52f
                val corridorHeight = (startY - endY).coerceAtLeast(1f)
                val corridorHalfWidth = 68.dp.toPx()
                val maxDx = corridorHalfWidth * 1.55f

                val corridorDots = ArrayList<CorridorDotPoint>(128)
                var x = stepPx / 2f
                while (x < size.width) {
                    val dx = abs(x - centerX)
                    if (dx <= maxDx) {
                        val nx = (dx / maxDx).coerceIn(0f, 1f)
                        val horizWeight = (1f - nx * nx).let { it * it }
                        var y = stepPx / 2f
                        while (y < size.height) {
                            if (y in endY..startY) {
                                val v = ((startY - y) / corridorHeight).coerceIn(0f, 1f)
                                val vertEnvelope = sin(v * PI).toFloat()
                                val combinedWeight = horizWeight * vertEnvelope
                                if (combinedWeight > 0.03f) {
                                    corridorDots.add(CorridorDotPoint(x, y, v, combinedWeight))
                                }
                            }
                            y += stepPx
                        }
                    }
                    x += stepPx
                }

                onDrawBehind {
                    val maxExtra = extraScalePx + (stretchProgress * stretchBoostPx)
                    val phase2 = (wavePhase + 0.5f) % 1f
                    val fingerPos = stretchProgress * 0.75f
                    val hasStretch = stretchProgress > 0.02f

                    for (i in 0 until corridorDots.size) {
                        val pt = corridorDots[i]
                        val d1 = abs(((pt.v - wavePhase + 1.5f) % 1f) - 0.5f) * 3.2f
                        val w1 = if (d1 < 1f) (1f - d1 * d1).let { it * it } else 0f

                        val d2 = abs(((pt.v - phase2 + 1.5f) % 1f) - 0.5f) * 3.5f
                        val w2 = if (d2 < 1f) 0.45f * (1f - d2 * d2).let { it * it } else 0f

                        val wf = if (hasStretch) {
                            val df = abs(pt.v - fingerPos) * 3.0f
                            if (df < 1f) stretchProgress * (1f - df * df).let { it * it } else 0f
                        } else 0f

                        val intensity = ((w1 + w2 + wf) * pt.baseWeight).coerceIn(0f, 1f)
                        if (intensity > 0.04f) {
                            val radius = baseRadius + (maxExtra * intensity)
                            val alpha = (0.10f + 0.54f * intensity).coerceIn(0f, 0.75f)
                            drawCircle(
                                color = Color.White.copy(alpha = alpha),
                                radius = radius,
                                center = Offset(pt.x, pt.y)
                            )
                        }
                    }
                }
            }
    )
}

@Composable
fun FrostedMistBox(
    modifier: Modifier = Modifier,
    shape: Shape = MistShapes.Radius24,
    borderColor: Color = NothingTokens.GlassBBorderTop,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(NothingTokens.GlassBBg)
            .border(1.dp, borderColor, shape),
        content = content
    )
}

@Composable
fun SoftGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = MistShapes.Pill,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val bgColor = if (isSelected) Color.White else NothingTokens.GlassCBg
    val borderCol = if (isSelected) Color.White else NothingTokens.GlassCBorder
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderCol, shape)
            .then(clickMod),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun FocalGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = MistShapes.Radius32,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(NothingTokens.GlassDBg)
            .border(1.dp, NothingTokens.GlassDBorderTop, shape),
        content = content
    )
}

@Composable
fun NdotText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    color: Color = NothingTokens.TextPrimary,
    fontWeight: FontWeight = FontWeight.Bold,
    letterSpacing: TextUnit = 1.6.sp,
    maxLines: Int = 1
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun MonoNumText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    color: Color = NothingTokens.TextSecondary,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun PillMonochromeAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    hasAccentBorder: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    CircleMonochromeAvatar(
        initials = initials,
        size = 44.dp,
        modifier = modifier,
        hasAccentBorder = hasAccentBorder,
        onClick = onClick
    )
}

@Composable
fun CircleMonochromeAvatar(
    initials: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    hasAccentBorder: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val cleanInitials = if (initials.length <= 2) initials.uppercase() else initials.take(2).uppercase()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF18181F))
            .border(
                width = 1.dp,
                color = if (hasAccentBorder) Color(0x55FFFFFF) else Color(0x24FFFFFF),
                shape = CircleShape
            )
            .then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cleanInitials,
            color = NothingTokens.TextPrimary,
            fontSize = (size.value * 0.30f).sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

// ============================================================================
// PREMIUM MONOCHROME B&W VECTOR ICONS (Zero PNG / Emoji Clutter)
// ============================================================================

@Composable
fun BareThreeDotsIcon(
    tint: Color = NothingTokens.TextPrimary,
    size: Dp = 22.dp,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) {
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp)
    } else Modifier

    Canvas(modifier = clickMod.size(size)) {
        val cx = this.size.width / 2f
        val r = 1.8.dp.toPx()
        drawCircle(color = tint, radius = r, center = Offset(cx, this.size.height * 0.22f))
        drawCircle(color = tint, radius = r, center = Offset(cx, this.size.height * 0.50f))
        drawCircle(color = tint, radius = r, center = Offset(cx, this.size.height * 0.78f))
    }
}

@Composable
fun BareCallIcon(
    tint: Color = NothingTokens.TextPrimary,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.16f)
                    lineTo(w * 0.38f, h * 0.16f)
                    lineTo(w * 0.46f, h * 0.36f)
                    lineTo(w * 0.35f, h * 0.46f)
                    cubicTo(
                        w * 0.42f, h * 0.58f,
                        w * 0.52f, h * 0.65f,
                        w * 0.65f, h * 0.54f
                    )
                    lineTo(w * 0.84f, h * 0.62f)
                    lineTo(w * 0.84f, h * 0.78f)
                    cubicTo(
                        w * 0.78f, h * 0.88f,
                        w * 0.52f, h * 0.86f,
                        w * 0.32f, h * 0.68f
                    )
                    cubicTo(
                        w * 0.14f, h * 0.48f,
                        w * 0.12f, h * 0.22f,
                        w * 0.22f, h * 0.16f
                    )
                    close()
                }
                onDrawBehind {
                    drawPath(path = path, color = tint)
                }
            }
    )
}

@Composable
fun BareSearchIcon(
    tint: Color = NothingTokens.TextPrimary,
    size: Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokePx = 1.8.dp.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.32f,
            center = Offset(w * 0.44f, h * 0.44f),
            style = Stroke(width = strokePx)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.68f, h * 0.68f),
            end = Offset(w * 0.88f, h * 0.88f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun VectorMuteIcon(
    tint: Color = Color.White,
    isMuted: Boolean = false,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.8.dp.toPx()

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.36f, h * 0.14f),
            size = Size(w * 0.28f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
            style = Stroke(width = stroke)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.28f),
            size = Size(w * 0.56f, h * 0.44f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.72f),
            end = Offset(w * 0.50f, h * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.34f, h * 0.88f),
            end = Offset(w * 0.66f, h * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (isMuted) {
            drawLine(
                color = NothingTokens.NothingRed,
                start = Offset(w * 0.18f, h * 0.18f),
                end = Offset(w * 0.82f, h * 0.82f),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun VectorKeypadIcon(
    tint: Color = Color.White,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = 2.0.dp.toPx()
        
        // 7-dot asymmetrical matrix matching image 2 (red circled icon):
        // Row 1: 3 dots (left & right bold, center soft accent)
        // Row 2: 3 dots (horizontal row)
        // Row 3: 1 single dot centered beneath
        val xs = floatArrayOf(0.25f, 0.50f, 0.75f)
        
        // Row 1
        drawCircle(color = tint, radius = r, center = Offset(w * xs[0], h * 0.22f))
        drawCircle(color = tint.copy(alpha = 0.55f), radius = r * 0.95f, center = Offset(w * xs[1], h * 0.22f))
        drawCircle(color = tint, radius = r, center = Offset(w * xs[2], h * 0.22f))
        
        // Row 2
        drawCircle(color = tint.copy(alpha = 0.65f), radius = r * 0.95f, center = Offset(w * xs[0], h * 0.50f))
        drawCircle(color = tint.copy(alpha = 0.65f), radius = r * 0.95f, center = Offset(w * xs[1], h * 0.50f))
        drawCircle(color = tint.copy(alpha = 0.65f), radius = r * 0.95f, center = Offset(w * xs[2], h * 0.50f))
        
        // Row 3 (single centered dot)
        drawCircle(color = tint, radius = r, center = Offset(w * 0.50f, h * 0.78f))
    }
}

/**
 * SPEED-DIAL / FAST-FORWARD ARROW ICON (Image 1, Red-Circled Icon)
 * Replaces the Recents clock icon with the aerodynamic rounded chevron + 3 speed motion trails.
 */
@Composable
fun VectorSpeedDialIcon(
    tint: Color = Color.White,
    size: Dp = 18.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val dashHeight = (h * 0.09f).coerceAtLeast(1.5f)
        val dashRadius = CornerRadius(dashHeight / 2f, dashHeight / 2f)

        // 3 Speed Motion Trails (left)
        // Top dash
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.33f),
            size = Size(w * 0.16f, dashHeight),
            cornerRadius = dashRadius
        )
        // Middle dash (longer)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.04f, h * 0.47f),
            size = Size(w * 0.28f, dashHeight),
            cornerRadius = dashRadius
        )
        // Bottom dash
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, h * 0.61f),
            size = Size(w * 0.16f, dashHeight),
            cornerRadius = dashRadius
        )

        // Aerodynamic Rounded Chevron / Playhead Head (right)
        val headPath = Path().apply {
            moveTo(w * 0.44f, h * 0.26f)
            // Top curve to front tip
            cubicTo(w * 0.52f, h * 0.20f, w * 0.60f, h * 0.30f, w * 0.82f, h * 0.44f)
            // Nose tip curve
            cubicTo(w * 0.92f, h * 0.50f, w * 0.92f, h * 0.50f, w * 0.82f, h * 0.56f)
            // Bottom curve to lower wing
            cubicTo(w * 0.60f, h * 0.70f, w * 0.52f, h * 0.80f, w * 0.44f, h * 0.74f)
            // Lower wing corner to inner notch
            cubicTo(w * 0.38f, h * 0.68f, w * 0.44f, h * 0.56f, w * 0.62f, h * 0.50f)
            // Inner notch back to upper wing corner
            cubicTo(w * 0.44f, h * 0.44f, w * 0.38f, h * 0.32f, w * 0.44f, h * 0.26f)
            close()
        }
        drawPath(path = headPath, color = tint)
    }
}

/**
 * CASSETTE TAPE / VOICEMAIL BADGE ICON (Image 2, Green-Circled Icon)
 * Replaces the Favourites icon with the iconic tape badge outline + dual reel circles and connecting bridge.
 */
@Composable
fun VectorVoicemailTapeIcon(
    tint: Color = Color.White,
    size: Dp = 18.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.085f).coerceAtLeast(1.4f)
        val corner = CornerRadius(w * 0.22f, w * 0.22f)

        // Outer rounded cartridge badge
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.16f),
            size = Size(w * 0.84f, h * 0.68f),
            cornerRadius = corner,
            style = Stroke(width = stroke)
        )

        // Left Tape Reel Circle
        drawCircle(
            color = tint,
            radius = w * 0.13f,
            center = Offset(w * 0.35f, h * 0.50f)
        )

        // Right Tape Reel Circle
        drawCircle(
            color = tint,
            radius = w * 0.13f,
            center = Offset(w * 0.65f, h * 0.50f)
        )

        // Connecting bridge between tape reels
        drawLine(
            color = tint,
            start = Offset(w * 0.35f, h * 0.56f),
            end = Offset(w * 0.65f, h * 0.56f),
            strokeWidth = stroke * 1.1f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun VectorSpeakerIcon(
    tint: Color = Color.White,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.8.dp.toPx()
        val cone = Path().apply {
            moveTo(w * 0.16f, h * 0.38f)
            lineTo(w * 0.32f, h * 0.38f)
            lineTo(w * 0.52f, h * 0.20f)
            lineTo(w * 0.52f, h * 0.80f)
            lineTo(w * 0.32f, h * 0.62f)
            lineTo(w * 0.16f, h * 0.62f)
            close()
        }
        drawPath(path = cone, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawArc(
            color = tint,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.44f, h * 0.32f),
            size = Size(w * 0.26f, h * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.48f, h * 0.18f),
            size = Size(w * 0.38f, h * 0.64f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun VectorAddCallIcon(
    tint: Color = Color.White,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 2.dp.toPx()
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.22f),
            end = Offset(w * 0.50f, h * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.22f, h * 0.50f),
            end = Offset(w * 0.78f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun VectorHoldIcon(
    tint: Color = Color.White,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barWidth = w * 0.16f
        val barHeight = h * 0.56f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.22f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(4f, 4f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.58f, h * 0.22f),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(4f, 4f)
        )
    }
}

@Composable
fun VectorRecordIcon(
    tint: Color = Color.White,
    isRecording: Boolean = false,
    size: Dp = 22.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.8.dp.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.36f,
            center = Offset(w * 0.50f, h * 0.50f),
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = if (isRecording) NothingTokens.NothingRed else tint,
            radius = w * 0.16f,
            center = Offset(w * 0.50f, h * 0.50f)
        )
    }
}

@Composable
fun VectorSaveContactIcon(
    tint: Color = Color.White,
    size: Dp = 18.dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.7.dp.toPx()
        drawCircle(
            color = tint,
            radius = w * 0.18f,
            center = Offset(w * 0.40f, h * 0.32f),
            style = Stroke(width = stroke)
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.14f, h * 0.56f),
            size = Size(w * 0.52f, h * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.78f, h * 0.28f),
            end = Offset(w * 0.78f, h * 0.56f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.64f, h * 0.42f),
            end = Offset(w * 0.92f, h * 0.42f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
