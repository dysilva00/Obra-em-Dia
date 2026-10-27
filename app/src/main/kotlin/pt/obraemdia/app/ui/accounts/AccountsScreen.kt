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
import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sumMoney
import java.time.LocalDate
import java.time.YearMonth

val CashKind.label: String
    get() = when (this) {
        CashKind.CLIENT_RECEIPT -> "Recebimento"
        CashKind.CLIENT_REFUND -> "Devolução a cliente"
        CashKind.SUPPLIER_PAYMENT -> "Pagamento"
        CashKind.CAPITAL_IN -> "Entrada de capital"
        CashKind.WITHDRAWAL -> "Retirada pessoal"
        CashKind.ADJUSTMENT_IN, CashKind.ADJUSTMENT_OUT -> "Ajuste"
    }

@Composable
fun AccountsScreen(snapshot: Snapshot, today: LocalDate, onNotReady: (String) -> Unit) {
    val month = YearMonth.from(today)
    val monthMovements = snapshot.movementsIn(month)
    val received = monthMovements.filter { it.kind.inflow }.sumMoney { Money(it.amountCents) }
    val paid = monthMovements.filter { !it.kind.inflow }.sumMoney { Money(it.amountCents) }
    val jobTitles = snapshot.jobs.associate { it.job.id to it.job.title }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Resumo de ${month.label()}", caption = "Real · movimentos efetivos") {
                LabelValue("Recebimentos", received.format(), AppColors.Positive)
                LabelValue("Pagamentos", paid.format())
                LabelValue("Saldo do mês", (received - paid).format())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNotReady("Projeções 30/60/90 dias") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Projeções", softWrap = false)
                }
                OutlinedButton(onClick = { onNotReady("Exportar CSV") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Exportar", softWrap = false)
                }
            }
        }
        item { Text("Movimentos", style = MaterialTheme.typography.titleMedium) }
        if (snapshot.movements.isEmpty()) {
            item { Text("Ainda não há movimentos registados.") }
        }
        items(snapshot.movements, key = { it.id }) { m ->
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(m.kind.label, m.counterparty).joinToString(" — "), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${m.date.shortLabel()} · ${m.jobId?.let(jobTitles::get) ?: "Despesa geral"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Sinal explícito: não depender só da cor para distinguir entradas de saídas.
                    val amount = Money(m.signedCents)
                    Text(
                        if (amount.isPositive) "+${amount.format()}" else amount.format(),
                        color = if (amount.isPositive) AppColors.Positive else MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}
