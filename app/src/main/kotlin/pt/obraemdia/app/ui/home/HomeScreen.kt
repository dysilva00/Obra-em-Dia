package pt.obraemdia.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.demo.DemoData
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.app.ui.theme.MoneyLarge
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.schedule.AGENDA_ZONE

@Composable
fun HomeScreen(onOpenJob: (String) -> Unit, onNotReady: (String) -> Unit) {
    val d = DemoData
    val monthBalance = d.monthReceived - d.monthPaid
    val accumulated = d.openingBalance + monthBalance
    val todayBookings = d.bookings.filter { it.slot.start.atZone(AGENDA_ZONE).toLocalDate() == d.today }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { onNotReady("Mudar de mês") }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mês anterior")
                }
                Text(d.month.label(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { onNotReady("Mudar de mês") }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mês seguinte")
                }
            }
        }

        item { QuickActions(onNotReady) }

        item {
            SectionCard("Saldo acumulado", caption = "Real · em ${d.today.shortLabel()} · movimentos registados na app, não é o saldo bancário") {
                Text(accumulated.format(), style = MoneyLarge)
                LabelValue("Saldo inicial", d.openingBalance.format())
            }
        }

        item {
            SectionCard("Este mês", caption = "Real · ${d.month.label()}") {
                LabelValue("Recebido", d.monthReceived.format(), AppColors.Positive)
                LabelValue("Pago", d.monthPaid.format())
                LabelValue("Saldo do mês", monthBalance.format())
            }
        }

        item {
            SectionCard("Em aberto", caption = "Posição em ${d.today.shortLabel()} · não são movimentos do mês") {
                LabelValue("Por receber de clientes", d.toReceive.format())
                LabelValue("Por pagar a fornecedores", "—")
            }
        }

        item {
            SectionCard("Hoje", caption = d.today.longLabel()) {
                if (todayBookings.isEmpty()) {
                    Text("Sem marcações para hoje.")
                }
                todayBookings.forEach { b ->
                    val job = d.jobs.first { it.id == b.jobId }
                    TextButton(onClick = { onOpenJob(job.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(job.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${job.address} · ${b.workerIds.joinToString(" e ") { d.workerName(it) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Horas registadas", caption = "Real · ${d.month.label()}") {
                d.workers.forEach { w ->
                    val minutes = d.jobs.flatMap { it.sale.timeEntries }.filter { it.workerId == w.id }.sumOf { it.minutes }
                    LabelValue(w.name, formatMinutes(minutes))
                }
            }
        }

        item {
            SectionCard("Obras") {
                LabelValue("Em curso", d.jobs.count { it.status == JobStatus.IN_PROGRESS }.toString())
                LabelValue("Concluídas por cobrar", d.jobs.count { it.status == JobStatus.COMPLETED && it.balance.balance.isPositive }.toString())
                LabelValue("Em espera", d.jobs.count { it.status == JobStatus.ON_HOLD }.toString())
            }
        }
    }
}

@Composable
private fun QuickActions(onNotReady: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onNotReady("Registar horas") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null)
            Text("  Registar horas")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onNotReady("Adicionar gasto") }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null)
                Text("  Gasto")
            }
            FilledTonalButton(onClick = { onNotReady("Registar pagamento") }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Euro, contentDescription = null)
                Text("  Pagamento")
            }
        }
    }
}
