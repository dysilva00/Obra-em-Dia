package pt.obraemdia.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.demo.DemoData
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors

@Composable
fun AccountsScreen(onNotReady: (String) -> Unit) {
    val d = DemoData
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Resumo de ${d.month.label()}", caption = "Real · movimentos efetivos") {
                LabelValue("Recebimentos", d.monthReceived.format(), AppColors.Positive)
                LabelValue("Pagamentos", d.monthPaid.format())
                LabelValue("Saldo do mês", (d.monthReceived - d.monthPaid).format())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNotReady("Projeções 30/60/90 dias") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Projeções")
                }
                OutlinedButton(onClick = { onNotReady("Exportar CSV") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Exportar")
                }
            }
        }
        item { Text("Movimentos", style = MaterialTheme.typography.titleMedium) }
        items(d.movements.sortedByDescending { it.date }) { m ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.description, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${m.date.shortLabel()} · ${m.jobTitle ?: "Despesa geral"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Sinal explícito: não depender só da cor para distinguir entradas de saídas.
                    val text = if (m.amount.isPositive) "+${m.amount.format()}" else m.amount.format()
                    Text(text, color = if (m.amount.isPositive) AppColors.Positive else MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}
