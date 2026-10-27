package pt.obraemdia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import pt.obraemdia.app.R

/**
 * Identidade: verde-petróleo nas ações, âmbar nos avisos, fundo claro para uso no exterior.
 * Superfícies em camadas tonais (sem sombras pesadas), cantos generosos e movimento "expressive".
 */
object AppColors {
    val Petrol = Color(0xFF0B5D5A)
    val PetrolDeep = Color(0xFF063F3D)
    val PetrolBright = Color(0xFF138A84)
    val PetrolContainer = Color(0xFFC4ECE7)
    val OnPetrolContainer = Color(0xFF00201E)
    val Amber = Color(0xFF8A5300)
    val AmberContainer = Color(0xFFFFE0B8)
    val OnAmberContainer = Color(0xFF2B1700)
    val Background = Color(0xFFF6F8F7)
    val Surface = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFF151D1C)
    val OnSurfaceVariant = Color(0xFF3E4947)
    val Outline = Color(0xFF6E7977)
    val OutlineVariant = Color(0xFFD3DCDA)
    val Error = Color(0xFFB3261E)
    val Positive = Color(0xFF1B6B2F)

    /** Fundo do cartão de destaque (saldo). */
    val HeroGradient = Brush.linearGradient(listOf(PetrolDeep, Petrol, PetrolBright))
}

private val LightColors = lightColorScheme(
    primary = AppColors.Petrol,
    onPrimary = Color.White,
    primaryContainer = AppColors.PetrolContainer,
    onPrimaryContainer = AppColors.OnPetrolContainer,
    secondary = Color(0xFF4A6360),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3EAE6),
    onSecondaryContainer = Color(0xFF06201D),
    tertiary = AppColors.Amber,
    onTertiary = Color.White,
    tertiaryContainer = AppColors.AmberContainer,
    onTertiaryContainer = AppColors.OnAmberContainer,
    background = AppColors.Background,
    onBackground = AppColors.OnSurface,
    surface = AppColors.Background,
    onSurface = AppColors.OnSurface,
    surfaceVariant = Color(0xFFDDE6E4),
    onSurfaceVariant = AppColors.OnSurfaceVariant,
    surfaceContainerLowest = AppColors.Surface,
    surfaceContainerLow = Color(0xFFF0F4F3),
    surfaceContainer = Color(0xFFEAF0EE),
    surfaceContainerHigh = Color(0xFFE4EBE9),
    surfaceContainerHighest = Color(0xFFDEE6E4),
    outline = AppColors.Outline,
    outlineVariant = AppColors.OutlineVariant,
    error = AppColors.Error,
)

/** Plus Jakarta Sans (OFL), fonte variável incluída na app: funciona sem internet. */
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Jakarta = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800))

// Tamanhos em sp para respeitar o tamanho de letra do Android; corpo ligeiramente maior que o padrão.
private val AppTypography = Typography().run {
    fun TextStyle.j(weight: Int, spacing: Float = 0f) = copy(fontFamily = Jakarta, fontWeight = FontWeight(weight), letterSpacing = spacing.em)
    copy(
        displayLarge = displayLarge.j(800, -0.02f),
        displayMedium = displayMedium.j(800, -0.02f),
        displaySmall = displaySmall.j(800, -0.015f),
        headlineLarge = headlineLarge.j(700, -0.01f),
        headlineMedium = headlineMedium.j(700, -0.01f),
        headlineSmall = headlineSmall.j(700, -0.005f),
        titleLarge = titleLarge.j(700),
        titleMedium = titleMedium.j(700),
        titleSmall = titleSmall.j(600, 0.005f),
        bodyLarge = bodyLarge.j(500, 0.01f).copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.j(500, 0.012f).copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = bodySmall.j(500, 0.015f),
        labelLarge = labelLarge.j(700, 0.01f).copy(fontSize = 15.sp),
        labelMedium = labelMedium.j(600, 0.02f),
        labelSmall = labelSmall.j(600, 0.025f),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Valores em euros: algarismos tabulares, para alinharem em coluna. */
val MoneyLarge = TextStyle(
    fontFamily = Jakarta,
    fontSize = 34.sp,
    fontWeight = FontWeight(800),
    lineHeight = 40.sp,
    letterSpacing = (-0.02).em,
    fontFeatureSettings = "tnum",
)

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ObraEmDiaTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = LightColors,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
