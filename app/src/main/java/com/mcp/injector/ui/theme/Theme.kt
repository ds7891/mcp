package com.mcp.injector.ui.theme

import android.content.Context
import android.os.Build
import android.util.TypedValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

private val LightScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
)

private val DarkScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
)

/** 动态取色开关（由 MainActivity 依据设置提供）。 */
val LocalDynamicColorEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

// ---- 语义状态色（跟随深浅模式）----

/** 语义状态色组：用于“成功 / 警告”等 M3 colorScheme 未覆盖的业务状态。 */
data class SemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val SemanticLight = SemanticColors(
    success = SuccessLight,
    onSuccess = OnSuccessLight,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warning = WarningLight,
    onWarning = OnWarningLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
)

private val SemanticDark = SemanticColors(
    success = SuccessDark,
    onSuccess = OnSuccessDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = WarningDark,
    onWarning = OnWarningDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
)

val LocalSemanticColors: ProvidableCompositionLocal<SemanticColors> =
    staticCompositionLocalOf { SemanticLight }

/** MaterialTheme 扩展：读取当前语义状态色（成功/警告容器及前景）。 */
val MaterialTheme.semanticColors: SemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSemanticColors.current

/**
 * 应用主题（对齐反编译产物 ThemeKt：MCPInjectorTheme(darkTheme, dynamicColor, content)）。
 *
 * dynamicColor=true 时优先走动态取色，分两条路径：
 * - **Android 12（API 31）及以上**：系统原生 Material You（``dynamic*ColorScheme``）；
 * - **Android 12 以下**：系统没有 ``android:color/system_accent*`` 这些资源，原生方案会抛
 *   ``Resources$NotFoundException``（实测 SDK 29 从设置里打开开关即闪退）。
 *   因此改为 [approximateColorScheme]：取系统主题色当作种子，按 Material 3 的色调分层
 *   派生整套色板，观感近似 Material You，且不会崩。
 */
@Composable
fun MCPInjectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dynamicColor -> remember(darkTheme) { approximateColorScheme(context, darkTheme) }

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val semanticColors = if (darkTheme) SemanticDark else SemanticLight
    CompositionLocalProvider(
        LocalDynamicColorEnabled provides dynamicColor,
        LocalSemanticColors provides semanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Android 12 以下的「近似动态取色」
// ---------------------------------------------------------------------------

/**
 * 近似动态色板（API 26–30）。
 *
 * 低版本拿不到 Material You 的原生色板，这里按同样的思路模仿：以**系统主题色**为种子，
 * 按 Material 3 的色调（tone）分层派生 primary / secondary / tertiary / 各类容器 / 表面色，
 * 因此用户换系统主题色时整机观感会跟着变。语义色（错误/成功/警告）不参与派生，仍用内置色板，
 * 避免把「错误红」也染成主题色。
 *
 * 种子取不到时回退到内置品牌色，效果退化为品牌色系下的深浅变体，同样不会崩。
 */
private fun approximateColorScheme(context: Context, dark: Boolean): ColorScheme {
    val seed = systemAccentColor(context) ?: if (dark) PrimaryDark else PrimaryLight
    val hsl = toHsl(seed.toArgb())
    val hue = hsl[0]
    // 系统主题色有的很灰，抬一个下限保证可辨识；上限避免荧光感
    val sat = hsl[1].coerceIn(0.20f, 0.85f)
    val sSec = sat * 0.42f      // secondary：低饱和
    val sTer = sat * 0.55f      // tertiary：中饱和（色相旋转 +60°）
    val sNeu = sat * 0.10f      // 表面/背景：几乎中性，只留一点色相倾向
    val sVar = sat * 0.14f      // surfaceVariant / outline
    val hueTer = hue + 60f

    return if (dark) {
        darkColorScheme(
            primary = hslToColor(hue, sat, 0.80f),
            onPrimary = hslToColor(hue, sat, 0.20f),
            primaryContainer = hslToColor(hue, sat, 0.30f),
            onPrimaryContainer = hslToColor(hue, sat, 0.90f),
            secondary = hslToColor(hue, sSec, 0.80f),
            onSecondary = hslToColor(hue, sSec, 0.20f),
            secondaryContainer = hslToColor(hue, sSec, 0.30f),
            onSecondaryContainer = hslToColor(hue, sSec, 0.90f),
            tertiary = hslToColor(hueTer, sTer, 0.80f),
            onTertiary = hslToColor(hueTer, sTer, 0.20f),
            tertiaryContainer = hslToColor(hueTer, sTer, 0.30f),
            onTertiaryContainer = hslToColor(hueTer, sTer, 0.90f),
            error = ErrorDark,
            onError = OnErrorDark,
            errorContainer = ErrorContainerDark,
            onErrorContainer = OnErrorContainerDark,
            background = hslToColor(hue, sNeu, 0.10f),
            onBackground = hslToColor(hue, sNeu, 0.92f),
            surface = hslToColor(hue, sNeu, 0.10f),
            onSurface = hslToColor(hue, sNeu, 0.92f),
            surfaceVariant = hslToColor(hue, sVar, 0.28f),
            onSurfaceVariant = hslToColor(hue, sVar, 0.80f),
            outline = hslToColor(hue, sVar, 0.60f),
        )
    } else {
        lightColorScheme(
            primary = hslToColor(hue, sat, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hslToColor(hue, sat, 0.90f),
            onPrimaryContainer = hslToColor(hue, sat, 0.12f),
            secondary = hslToColor(hue, sSec, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hslToColor(hue, sSec, 0.90f),
            onSecondaryContainer = hslToColor(hue, sSec, 0.12f),
            tertiary = hslToColor(hueTer, sTer, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hslToColor(hueTer, sTer, 0.90f),
            onTertiaryContainer = hslToColor(hueTer, sTer, 0.12f),
            error = ErrorLight,
            onError = OnErrorLight,
            errorContainer = ErrorContainerLight,
            onErrorContainer = OnErrorContainerLight,
            background = hslToColor(hue, sNeu, 0.98f),
            onBackground = hslToColor(hue, sNeu, 0.10f),
            surface = hslToColor(hue, sNeu, 0.98f),
            onSurface = hslToColor(hue, sNeu, 0.10f),
            surfaceVariant = hslToColor(hue, sVar, 0.92f),
            onSurfaceVariant = hslToColor(hue, sVar, 0.30f),
            outline = hslToColor(hue, sVar, 0.48f),
        )
    }
}

/** 系统主题强调色；取不到（或不是颜色值）返回 null。 */
private fun systemAccentColor(context: Context): Color? {
    val tv = TypedValue()
    val ok = context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)
    val isColor = tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
        tv.type <= TypedValue.TYPE_LAST_COLOR_INT
    return if (ok && isColor) Color(tv.data) else null
}

/** ARGB → [hue, saturation, lightness]（h∈[0,360)，s/l∈[0,1]）。 */
private fun toHsl(argb: Int): FloatArray {
    val r = android.graphics.Color.red(argb) / 255f
    val g = android.graphics.Color.green(argb) / 255f
    val b = android.graphics.Color.blue(argb) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    // 灰阶没有色相/饱和度，直接返回（同时避免 l 为 0/1 时的除零）
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

/** HSL → Color（h 自动归一到 [0,360)）。 */
private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sf = s.coerceIn(0f, 1f)
    val lf = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * lf - 1f)) * sf
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lf - c / 2f
    val rgb = when {
        hue < 60f -> floatArrayOf(c, x, 0f)
        hue < 120f -> floatArrayOf(x, c, 0f)
        hue < 180f -> floatArrayOf(0f, c, x)
        hue < 240f -> floatArrayOf(0f, x, c)
        hue < 300f -> floatArrayOf(x, 0f, c)
        else -> floatArrayOf(c, 0f, x)
    }
    return Color(rgb[0] + m, rgb[1] + m, rgb[2] + m)
}
