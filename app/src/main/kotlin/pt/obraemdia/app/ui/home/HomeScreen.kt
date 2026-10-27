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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AgendaState
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.app.ui.theme.MoneyLarge
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.money.sumMoney
import pt.obraemdia.domain.schedule.TimeSlot
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun HomeScreen(
    snapshot: Snapshot,
    agenda: AgendaState?,
    today: LocalDate,
    onOpenJob: (String) -> Unit,
    onRecordTime: () -> Unit,
    onRecordPayment: () -> Unit,
    onAddExpense: () -> Unit,
) {
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today)) }
    val monthMovements = snapshot.movementsIn(month)
    val received = snapshot.monthReceived(month)
    val paid = snapshot.monthPaid(month)
    val summaries = snapshot.summaries
    val toReceive = summaries.mapNotNull { it.balance?.balance }.filter { it.isPositive }.sum()
    val unknownDue = summaries.count { it.dueGross == null && it.job.status != JobStatus.CANCELLED }
    val todaySlot = TimeSlot.wholeDay(today)
    val todayItems = agenda?.items.orEmpty().filter { it.booking.slot.overlaps(todaySlot) }
    val monthEntries = snapshot.timeEntries.filter { YearMonth.from(it.date) == month }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mês anterior")
                }
                Text(month.label(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mês seguinte")
                }
            }
        }

        item { QuickActions(onRecordTime, onRecordPayment, onAddExpense) }

        item {
            SectionCard("Saldo acumulado", caption = "Real · em ${today.shortLabel()} · movimentos registados na app, não é o saldo bancário") {
                Text(snapshot.accumulatedBalance(today).format(), style = MoneyLarge)
                LabelValue("Saldo inicial", snapshot.openingBalance.format())
            }
        }

        item {
            SectionCard("Movimentos do mês", caption = "Real · ${month.label()}") {
                LabelValue("Recebido", received.format(), AppColors.Positive)
                LabelValue("Pago", paid.format())
                LabelValue("Saldo do mês", (received - paid).format())
            }
        }

        item {
            SectionCard("Em aberto", caption = "Posição em ${today.shortLabel()} · não são movimentos do mês") {
                LabelValue("Por receber de clientes", toReceive.format())
                LabelValue("Por pagar a fornecedores", snapshot.toPaySuppliers.format())
                if (unknownDue > 0) {
                    Text(
                        "$unknownDue obra(s) sem preço ou IVA definido não entram neste total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard("Hoje", caption = today.longLabel()) {
                if (todayItems.isEmpty()) Text("Sem marcações para hoje.")
                todayItems.forEach { item ->
                    val b = item.booking
                    TextButton(onClick = { onOpenJob(b.jobId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(b.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                b.workerIds.sorted().joinToString(" e ") { snapshot.workerName(it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Horas registadas", caption = "Real · ${month.label()}") {
                snapshot.workers.forEach { w ->
                    LabelValue(w.name, formatMinutes(monthEntries.filter { it.workerId == w.id }.sumOf { it.minutes }))
                }
            }
        }

        item {
            SectionCard("Obras") {
                LabelValue("Em curso", summaries.count { it.job.status == JobStatus.IN_PROGRESS }.toString())
                LabelValue("Por confirmar", summaries.count { it.job.status == JobStatus.TO_CONFIRM }.toString())
                LabelValue("Em espera", summaries.count { it.job.status == JobStatus.ON_HOLD }.toString())
                LabelValue(
                    "Concluídas por cobrar",
                    summaries.count { it.job.status == JobStatus.COMPLETED && it.balance?.balance?.isPositive == true }.toString(),
                )
            }
        }
    }
}

@Composable
private fun QuickActions(onRecordTime: () -> Unit, onRecordPayment: () -> Unit, onAddExpense: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRecordTime, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Schedule, contentDescription = null)
            Text("  Registar horas")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAddExpense, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null)
                Text("  Gasto", softWrap = false)
            }
            FilledTonalButton(onClick = onRecordPayment, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Euro, contentDescription = null)
                Text("  Pagamento", softWrap = false)
            }
        }
    }
}
