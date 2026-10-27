package pt.obraemdia.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import pt.obraemdia.app.ui.components.listRowColors
import pt.obraemdia.app.ui.components.IconTile
import pt.obraemdia.app.ui.theme.TabularNumbers
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import pt.obraemdia.app.data.repo.Alert
import pt.obraemdia.app.data.repo.AlertKind
import pt.obraemdia.app.data.repo.Snapshot
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
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
    alerts: List<Alert>,
    onForecast: () -> Unit,
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
            HeroBalance(
                balance = snapshot.accumulatedBalance(today),
                opening = snapshot.openingBalance,
                today = today,
                month = month,
                received = received,
                paid = paid,
                onPrev = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
            )
        }

        item { QuickActions(onRecordTime, onRecordPayment, onAddExpense) }

        if (alerts.isNotEmpty()) {
            item {
                SectionCard("Alertas", caption = "${alerts.size} a ver", icon = Icons.Filled.NotificationsActive) {
                    alerts.take(6).forEach { a ->
                        TextButton(
                            onClick = { a.jobId?.let(onOpenJob) },
                            enabled = a.jobId != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(
                                    if (a.kind == AlertKind.MISSING_DATA) Icons.Filled.Info else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (a.kind == AlertKind.MISSING_DATA) MaterialTheme.colorScheme.onSurfaceVariant else AppColors.Amber,
                                )
                                Text(
                                    a.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    if (alerts.size > 6) Text("+ ${alerts.size - 6} alerta(s)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard("Em aberto", caption = "Posição em ${today.shortLabel()} · não são movimentos do mês", icon = Icons.Filled.AccountBalanceWallet) {
                LabelValue("Por receber de clientes", toReceive.format())
                LabelValue("Por pagar a fornecedores", snapshot.toPaySuppliers.format())
                if (unknownDue > 0) {
                    Text(
                        "$unknownDue obra(s) sem preço ou IVA definido não entram neste total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onForecast, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ver projeções a 30/60/90 dias") }
            }
        }

        item {
            SectionCard("Hoje", caption = today.longLabel(), icon = Icons.Filled.Today) {
                if (todayItems.isEmpty()) Text("Sem marcações para hoje.")
                todayItems.forEach { item ->
                    val b = item.booking
                    TextButton(onClick = { onOpenJob(b.jobId) }, colors = listRowColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
            SectionCard("Horas registadas", caption = "Real · ${month.label()}", icon = Icons.Filled.Schedule) {
                snapshot.workers.forEach { w ->
                    LabelValue(w.name, formatMinutes(monthEntries.filter { it.workerId == w.id }.sumOf { it.minutes }))
                }
            }
        }

        item {
            SectionCard("Obras", icon = Icons.Filled.Construction) {
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

/**
 * Cartão de destaque: saldo acumulado (real) e movimentos do mês escolhido.
 * Texto branco sobre verde-petróleo escuro: contraste alto também ao sol.
 */
@Composable
private fun HeroBalance(
    balance: Money,
    opening: Money,
    today: java.time.LocalDate,
    month: YearMonth,
    received: Money,
    paid: Money,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val onHero = Color.White
    val soft = Color.White.copy(alpha = 0.78f)
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(AppColors.HeroGradient),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Saldo acumulado", style = MaterialTheme.typography.titleSmall, color = soft)
            Text(balance.format(), style = MoneyLarge, color = onHero)
            Text(
                "Real · em ${today.shortLabel()} · desde o saldo inicial de ${opening.format()}. Não é o saldo bancário.",
                style = MaterialTheme.typography.bodySmall,
                color = soft,
            )
            // Seletor do mês em pílula translúcida.
            Surface(color = Color.White.copy(alpha = 0.14f), shape = CircleShape, modifier = Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrev) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mês anterior", tint = onHero)
                    }
                    Text(month.label(), style = MaterialTheme.typography.titleSmall, color = onHero, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = onNext) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mês seguinte", tint = onHero)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                HeroStat("Recebido", received, Icons.Filled.ArrowDownward, Modifier.weight(1f))
                HeroStat("Pago", paid, Icons.Filled.ArrowUpward, Modifier.weight(1f))
            }
            Text(
                "Saldo do mês ${(received - paid).format()}",
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                color = onHero,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: Money, icon: ImageVector, modifier: Modifier) {
    Surface(color = Color.White.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Text(" $label", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
            }
            Text(value.format(), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), color = Color.White)
        }
    }
}

/** As três ações principais: grandes, fáceis de acertar com o polegar e com luvas. */
@Composable
private fun QuickActions(onRecordTime: () -> Unit, onRecordPayment: () -> Unit, onAddExpense: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onRecordTime,
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null)
            Text("  Registar horas", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("Adicionar gasto", Icons.Filled.ShoppingCart, onAddExpense, Modifier.weight(1f))
            ActionTile("Registar pagamento", Icons.Filled.Euro, onRecordPayment, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(icon, container = MaterialTheme.colorScheme.surfaceContainerLowest, content = MaterialTheme.colorScheme.primary, size = 36.dp)
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
