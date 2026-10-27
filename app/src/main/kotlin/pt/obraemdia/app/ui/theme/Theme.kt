package pt.obraemdia.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Verde-petróleo nas ações principais, âmbar nos avisos, fundo claro para uso no exterior. */
object AppColors {
    val Petrol = Color(0xFF0B5D5A)
    val PetrolContainer = Color(0xFFCDEBE8)
    val OnPetrolContainer = Color(0xFF00201E)
    val Amber = Color(0xFF8A5300)
    val AmberContainer = Color(0xFFFFE2B8)
    val OnAmberContainer = Color(0xFF2B1700)
    val Background = Color(0xFFF7F8F7)
    val Surface = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFF1A1C1C)
    val OnSurfaceVariant = Color(0xFF3F4948)
    val Outline = Color(0xFF6F7978)
    val Error = Color(0xFFB3261E)
    val Positive = Color(0xFF1B6B2F)
}

private val LightColors = lightColorScheme(
    primary = AppColors.Petrol,
    onPrimary = Color.White,
    primaryContainer = AppColors.PetrolContainer,
    onPrimaryContainer = AppColors.OnPetrolContainer,
    secondary = AppColors.Petrol,
    secondaryContainer = AppColors.PetrolContainer,
    onSecondaryContainer = AppColors.OnPetrolContainer,
    tertiary = AppColors.Amber,
    tertiaryContainer = AppColors.AmberContainer,
    onTertiaryContainer = AppColors.OnAmberContainer,
    background = AppColors.Background,
    surface = AppColors.Surface,
    surfaceContainer = Color(0xFFEFF2F1),
    onSurface = AppColors.OnSurface,
    onSurfaceVariant = AppColors.OnSurfaceVariant,
    outline = AppColors.Outline,
    error = AppColors.Error,
)

// Tamanhos em sp para respeitar o tamanho de letra do Android; corpo ligeiramente maior que o padrão.
private val AppTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    )
}

val MoneyLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)

@Composable
fun ObraEmDiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, content = content)
}
