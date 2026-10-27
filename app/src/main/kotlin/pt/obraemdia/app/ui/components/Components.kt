package pt.obraemdia.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import pt.obraemdia.app.ui.longLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.label

/** Faixa permanente enquanto existirem dados fictícios na base. */
@Composable
fun DemoBanner(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Inclui DADOS DE DEMONSTRAÇÃO fictícios",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remover") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remover dados de demonstração?") },
            text = { Text("São apagadas as obras, horas, pagamentos e marcações fictícias. As pessoas e os registos que criou ficam.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove() }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

/** Seletor simples em lista; mostra [placeholder] quando nada está escolhido. */
@Composable
fun <T> Picker(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    placeholder: String = "Escolher…",
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(selected?.let(optionLabel) ?: placeholder, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = { onSelect(option); open = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** Chip que abre o calendário e devolve a data escolhida. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerChip(label: String, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) { DatePicker(state) }
    }
}

/** Escolha de data com atalhos Ontem/Hoje e calendário. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, today: LocalDate, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = date == today.minusDays(1), onClick = { onChange(today.minusDays(1)) }, label = { Text("Ontem") })
            FilterChip(selected = date == today, onClick = { onChange(today) }, label = { Text("Hoje") })
            AssistChip(
                onClick = { open = true },
                label = { Text(date.longLabel()) },
                leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
    if (open) {
        // O DatePicker trabalha em milissegundos UTC à meia-noite.
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Etiqueta com texto por extenso; a cor é apenas reforço. */
@Composable
fun Tag(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatusTag(status: JobStatus) {
    val (bg, fg) = when (status) {
        JobStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        JobStatus.ON_HOLD -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurface
    }
    Tag(status.label, bg, fg)
}

@Composable
fun PriorityTag(priority: Priority) {
    when (priority) {
        Priority.URGENT, Priority.HIGH -> Tag(
            "Prioridade ${priority.label.lowercase()}",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Warning,
        )
        else -> Unit
    }
}

/** Linha "rótulo …… valor" usada nos cartões de totais. */
@Composable
fun LabelValue(label: String, value: String, valueColor: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}

@Composable
fun WarningBox(text: String, modifier: Modifier = Modifier) {
    Surface(color = AppColors.AmberContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = "Aviso", tint = AppColors.Amber)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.OnAmberContainer)
        }
    }
}
