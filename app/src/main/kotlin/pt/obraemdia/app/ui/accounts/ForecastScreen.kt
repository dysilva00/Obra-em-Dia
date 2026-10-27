package pt.obraemdia.app.ui.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.DatePickerChip
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.app.ui.theme.MoneyLarge
import pt.obraemdia.domain.cash.ForecastItem
import pt.obraemdia.domain.cash.Scenario
import java.time.LocalDate

/** Previsão de caixa: valores previstos, nunca apresentados como reais. */
@Composable
fun ForecastScreen(vm: AppViewModel, snapshot: Snapshot) {
    var horizon by rememberSaveable { mutableStateOf(30L) }
    var proposals by rememberSaveable { mutableStateOf(false) }
    // Adiamentos simulados: só existem neste ecrã, não alteram dados.
    val postponed = remember { mutableStateMapOf<String, LocalDate>() }
    val (result, assumptions) = vm.forecast(snapshot, horizon, proposals, postponed.toMap())
    val today = vm.today

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30L, 60L, 90L).forEach { d ->
                    FilterChip(horizon == d, { horizon = d }, { Text("$d dias") }, Modifier.heightIn(min = 48.dp))
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(proposals, role = Role.Switch, onValueChange = { proposals = it }),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Incluir propostas por confirmar", style = MaterialTheme.typography.bodyLarge)
                    Text("Sem probabilidades: entram por inteiro ou não entram.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(proposals, onCheckedChange = null)
            }
        }
        item {
            SectionCard(icon = Icons.AutoMirrored.Filled.ShowChart, title = "Saldo previsto a ${result.horizonEnd.shortLabel()}",
                caption = "PREVISTO · " + if (proposals) "cenário com propostas" else "cenário confirmado",
            ) {
                Text(result.endBalance.format(), style = MoneyLarge)
                LabelValue("Saldo atual (real)", result.currentBalance.format())
                LabelValue("Entradas previstas", result.inflow.format(), AppColors.Positive)
                LabelValue("Saídas previstas", result.outflow.format())
                LabelValue("Ponto mais baixo", "${result.lowest.second.format()} a ${result.lowest.first.shortLabel()}")
                if (result.lowest.second.isNegative) WarningBox("O saldo previsto fica negativo dentro deste período.")
                if (postponed.isNotEmpty()) {
                    TextButton(onClick = { postponed.clear() }) { Text("Limpar ${postponed.size} adiamento(s) simulado(s)") }
                }
            }
        }
        if (result.isIncomplete) {
            item {
                SectionCard(icon = Icons.Filled.Info, title = "Previsão incompleta", caption = "Valores em falta não são tratados como zero") {
                    result.unknowns.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    result.undated.forEach { Text("• ${it.label}: ${it.amount.format()} sem data", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        if (result.months.size > 1) {
            item {
                SectionCard(icon = Icons.Filled.CalendarMonth, title = "Por mês", caption = "Previsto") {
                    result.months.forEach { m ->
                        LabelValue(m.month.label(), "+${m.inflow.format()} / −${m.outflow.format()}")
                    }
                }
            }
        }
        item {
            Text("Movimentos previstos", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use \"Simular adiamento\" num recebimento para ver o efeito de um atraso (não altera dados).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.included.isEmpty()) item { Text("Nada previsto neste período.") }
        items(result.included, key = { it.id }) { item -> ForecastRow(item, today) { postponed[item.id] = it } }
        item {
            SectionCard(icon = Icons.Filled.Lightbulb, title = "Pressupostos", caption = "Como esta previsão foi feita") {
                assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ForecastRow(item: ForecastItem, today: LocalDate, onPostpone: (LocalDate) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        item.date?.shortLabel(),
                        "VENCIDO".takeIf { item.overdue },
                        "proposta".takeIf { item.scenario == Scenario.PROPOSAL },
                        item.assumption,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (item.amount.isPositive) "+${item.amount.format()}" else item.amount.format(),
                color = if (item.amount.isPositive) AppColors.Positive else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (item.amount.isPositive) {
            DatePickerChip("Simular adiamento", (item.date ?: today).plusDays(30), onPostpone)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}
