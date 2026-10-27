package pt.obraemdia.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.app.ui.theme.AppColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** O que mostrar num dia do calendário. */
data class DayMark(
    val firm: Int = 0,
    val tentative: Int = 0,
    val conflict: Boolean = false,
    val unavailable: Boolean = false,
) {
    val isEmpty get() = firm == 0 && tentative == 0 && !unavailable

    /** Texto para o leitor de ecrã: a cor nunca é a única informação. */
    fun describe(): String = buildList {
        if (firm > 0) add("$firm marcação(ões)")
        if (tentative > 0) add("$tentative pré-reserva(s)")
        if (conflict) add("com sobreposição")
        if (unavailable) add("indisponibilidade")
    }.joinToString(", ").ifEmpty { "livre" }
}

private val weekdayInitials = listOf("S", "T", "Q", "Q", "S", "S", "D") // segunda a domingo

@Composable
fun MonthCalendar(
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    marks: Map<LocalDate, DayMark>,
    today: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mês anterior")
            }
            Text(month.label(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mês seguinte")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            weekdayInitials.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Grelha a começar à segunda-feira, com células vazias antes do dia 1.
        val leading = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f)) {
                        if (day != null) DayCell(day, marks[day] ?: DayMark(), day == today, day == selected) { onSelect(day) }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Legend()
    }
}

@Composable
private fun DayCell(day: LocalDate, mark: DayMark, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        mark.firm > 0 -> scheme.primaryContainer
        else -> Color.Transparent
    }
    val shape = RoundedCornerShape(8.dp)
    var cell = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .heightIn(min = 48.dp)
        .background(background, shape)
    // Pré-reserva sem marcação firme: só contorno (distinto da ocupação firme).
    if (mark.firm == 0 && mark.tentative > 0) cell = cell.border(BorderStroke(2.dp, scheme.primary), shape)
    if (isSelected) cell = cell.border(BorderStroke(3.dp, scheme.onSurface), shape)
    Box(
        cell
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${day.longLabel()}${if (isToday) ", hoje" else ""}: ${mark.describe()}" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) scheme.primary else scheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (mark.conflict) Text("!", color = AppColors.Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                if (mark.unavailable) Box(Modifier.size(6.dp).background(scheme.outline, CircleShape))
            }
        }
        if (isToday) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp, height = 2.dp).background(scheme.primary))
    }
}

@Composable
private fun Legend() {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        LegendItem(Modifier.background(scheme.primaryContainer, RoundedCornerShape(3.dp)), "Marcado")
        LegendItem(Modifier.border(2.dp, scheme.primary, RoundedCornerShape(3.dp)), "Pré-reserva")
        Text("! Sobreposição", style = MaterialTheme.typography.labelSmall, color = AppColors.Amber)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(scheme.outline, CircleShape))
            Spacer(Modifier.size(4.dp))
            Text("Indisponível", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LegendItem(swatch: Modifier, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(swatch.size(14.dp))
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
