package pt.obraemdia.app.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.repo.JobSummary
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.ReasonDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AgendaState
import pt.obraemdia.app.ui.SaveResult
import pt.obraemdia.app.ui.agenda.agendaMarks
import pt.obraemdia.app.ui.components.DatePickerChip
import pt.obraemdia.app.ui.components.MonthCalendar
import pt.obraemdia.app.ui.forms.describe
import pt.obraemdia.app.ui.longLabel
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.job.label
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.PriorityTag
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.StatusTag
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.PaymentSituation
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Vat

@Composable
fun JobsScreen(snapshot: Snapshot, onOpenJob: (String) -> Unit, onNewJob: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val jobs = snapshot.summaries
        .filter { !it.job.archived }
        .filter { s ->
            query.isBlank() ||
                listOfNotNull(s.job.title, s.row.clientName, s.row.siteAddress, s.job.description).any { it.contains(query, ignoreCase = true) }
        }
        .sortedWith(compareByDescending<JobSummary> { it.job.priority }.thenByDescending { it.job.updatedAt })

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Pesquisar obra, cliente ou morada") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = onNewJob, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Nova obra")
            }
        }
        if (jobs.isEmpty()) {
            item {
                Text(
                    if (query.isBlank()) "Ainda não há obras. Crie a primeira com \"Nova obra\"." else "Nenhuma obra encontrada.",
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(jobs, key = { it.job.id }) { s ->
            OutlinedCard(onClick = { onOpenJob(s.job.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.job.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(s.row.clientName ?: "Sem cliente", s.row.siteAddress).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusTag(s.job.status)
                        PriorityTag(s.job.priority)
                    }
                    LabelValue("Falta receber", s.balance?.balance?.format() ?: "Valor por definir")
                }
            }
        }
    }
}

@Composable
fun JobDetailScreen(
    snapshot: Snapshot,
    agenda: AgendaState?,
    today: LocalDate,
    jobId: String,
    onRecordTime: () -> Unit,
    onRecordPayment: () -> Unit,
    onSchedule: () -> Unit,
    onAddExpense: () -> Unit,
    onChangeStatus: suspend (StatusChange) -> SaveResult,
    onReviewClosure: () -> Unit,
    onUpdateTime: suspend (id: String, date: LocalDate, minutes: Int, kind: TimeEntryKind, billable: Boolean, note: String?) -> SaveResult,
    onDeleteTime: suspend (id: String, reason: String) -> SaveResult,
    onMessage: (String) -> Unit,
) {
    val s = snapshot.summary(jobId)
    if (s == null) {
        Text("Obra não encontrada.", modifier = Modifier.padding(16.dp))
        return
    }
    val job = s.job
    var statusDialog by rememberSaveable { mutableStateOf(false) }
    val closed = snapshot.isClosed(jobId)
    var editing by remember { mutableStateOf<TimeEntryEntity?>(null) }
    val jobBookings = agenda?.items.orEmpty().filter { it.booking.jobId == jobId }
    val bookedDays = jobBookings.map { it.booking.slot.start.atZone(AGENDA_ZONE).toLocalDate() }.distinct().sorted()
    var month by rememberSaveable { mutableStateOf(YearMonth.from(bookedDays.firstOrNull { it >= today } ?: today)) }
    var selected by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(job.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(s.row.clientName ?: "Sem cliente associado", s.row.siteAddress).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(job.status)
                    PriorityTag(job.priority)
                    AssistChip(onClick = { statusDialog = true }, enabled = !closed, label = { Text("Mudar estado") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                job.urgentReason?.let { Text("Motivo da urgência: $it", style = MaterialTheme.typography.bodyMedium) }
                if (job.status == JobStatus.ON_HOLD) {
                    val reason = job.holdReason?.label ?: "—"
                    Text(
                        "Em espera: $reason" + (job.holdNote?.let { " · $it" } ?: "") + (job.holdReviewDate?.let { " · rever a ${it.shortLabel()}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (closed) {
            item {
                val c = snapshot.closureOf(jobId)!!
                WarningBox("Obra encerrada (revisão ${c.revision}). Para alterar horas, gastos ou pagamentos, reabra-a em \"Ver encerramento\".")
            }
        }
        item {
            // FlowRow: com letra grande, os botões passam para a linha seguinte em vez de partir palavras.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRecordTime, enabled = !closed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Horas", softWrap = false) }
                FilledTonalButton(onClick = onAddExpense, enabled = !closed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Gasto", softWrap = false) }
                FilledTonalButton(onClick = onRecordPayment, enabled = !closed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pagamento", softWrap = false) }
                FilledTonalButton(onClick = onSchedule, enabled = !closed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Agendar", softWrap = false) }
            }
        }
        item {
            val upcoming = bookedDays.count { it >= today }
            SectionCard(
                "Dias marcados",
                caption = if (bookedDays.isEmpty()) "Ainda sem dias marcados" else "${bookedDays.size} dia(s) · $upcoming a partir de hoje",
            ) {
                MonthCalendar(
                    month = month,
                    onMonthChange = { month = it },
                    marks = agendaMarks(agenda, jobId = jobId),
                    today = today,
                    selected = selected,
                    onSelect = { selected = it },
                )
                selected?.let { day ->
                    val onDay = jobBookings.filter { it.booking.slot.start.atZone(AGENDA_ZONE).toLocalDate() == day }
                    if (onDay.isEmpty()) {
                        Text("${day.longLabel()}: sem marcação desta obra.", style = MaterialTheme.typography.bodyMedium)
                    }
                    onDay.forEach { item ->
                        val b = item.booking
                        val hhmm = DateTimeFormatter.ofPattern("HH:mm")
                        Text(
                            "${day.longLabel()} · ${b.slot.start.atZone(AGENDA_ZONE).format(hhmm)}–${b.slot.end.atZone(AGENDA_ZONE).format(hhmm)} · " +
                                b.workerIds.sorted().joinToString(" e ") { snapshot.workerName(it) } +
                                if (b.firmness == BookingFirmness.TENTATIVE) " · pré-reserva" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        item.conflicts.forEach { WarningBox(it.describe(snapshot)) }
                    }
                }
            }
        }
        item {
            val mode = when (job.billingMode) {
                BillingMode.FIXED_PRICE -> "Preço fixo"
                BillingMode.HOURLY -> "À hora"
            }
            val vat = job.vatRateBp?.let { "IVA ${Vat.label(it)}" } ?: "IVA por definir"
            SectionCard("Cliente e pagamentos", caption = "$mode · $vat") {
                LabelValue("Valor sem IVA", s.saleNet?.format() ?: "Por definir")
                LabelValue("Total devido com IVA", s.dueGross?.format() ?: "Por definir")
                LabelValue("Recebido", s.received.format(), AppColors.Positive)
                val balance = s.balance
                if (balance != null) {
                    LabelValue(if (balance.balance.isNegative) "A regularizar" else "Falta receber", balance.balance.format())
                    LabelValue("Situação", balance.situation.label)
                } else {
                    WarningBox("Sem preço ou IVA definido: não é possível calcular quanto falta receber.")
                }
            }
        }
        item {
            SectionCard("Horas", caption = "Real · ${s.entries.size} registo(s)") {
                LabelValue("Total horas-pessoa", formatMinutes(s.minutes))
                snapshot.workers.forEach { w ->
                    val mine = s.entries.filter { it.workerId == w.id }
                    if (mine.isNotEmpty()) LabelValue(w.name, formatMinutes(mine.sumOf { it.minutes }))
                }
                if (!closed && s.entries.isNotEmpty()) {
                    Text("Toque num registo para o corrigir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.entries.sortedByDescending { it.date }.take(12).forEach { e ->
                    TextButton(
                        onClick = { editing = e },
                        enabled = !closed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            "${e.date.shortLabel()} · ${snapshot.workerName(e.workerId)} · ${formatMinutes(e.minutes)}" +
                                (if (!e.billable) " · não faturável" else "") +
                                if (job.billingMode == BillingMode.HOURLY) " · ${HourlyRate(e.saleRateCents).amountFor(60).format()}/h" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (s.entries.size > 12) Text("+ ${s.entries.size - 12} registo(s) mais antigos", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard("Resultado de gestão", caption = "Antes de impostos sobre o rendimento · não é um valor fiscal") {
                val r = s.result
                if (r == null) {
                    Text("Sem valor de venda definido.")
                } else {
                    LabelValue("Venda sem IVA", r.saleNet.format())
                    LabelValue("Custo interno de mão de obra", r.internalLaborCost.format())
                    LabelValue("Materiais e despesas (${s.costDocuments} doc.)", s.costs.format())
                    LabelValue("Resultado", r.result.format())
                    LabelValue("Margem", r.marginPercent?.let { "${it.toPlainString().replace('.', ',')} %" } ?: "—")
                    if (s.costsWithUnknownVat) {
                        Text(
                            "Há despesas com IVA por classificar: o IVA foi contado como custo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onReviewClosure, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (closed) "Ver encerramento" else "Rever encerramento")
            }
        }
    }

    editing?.let { e ->
        TimeEntryDialog(
            entry = e,
            workerName = snapshot.workerName(e.workerId),
            onDismiss = { editing = null },
            onSave = { date, minutes, kind, billable, note -> onUpdateTime(e.id, date, minutes, kind, billable, note) },
            onDelete = { reason -> onDeleteTime(e.id, reason) },
            onDone = { editing = null; onMessage(it) },
        )
    }

    if (statusDialog) {
        StatusDialog(
            current = job.status,
            received = s.received,
            onDismiss = { statusDialog = false },
            onConfirm = onChangeStatus,
            onResult = { statusDialog = false; onMessage(it) },
        )
    }
}

@Composable
private fun StatusDialog(
    current: JobStatus,
    received: Money,
    onDismiss: () -> Unit,
    onConfirm: suspend (StatusChange) -> SaveResult,
    onResult: (String) -> Unit,
) {
    var target by rememberSaveable { mutableStateOf<JobStatus?>(null) }
    var reason by rememberSaveable { mutableStateOf<HoldReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var review by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Estado do trabalho") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Atual: ${current.label}", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (JobStatus.entries - current).forEach { st ->
                        FilterChip(target == st, { target = st; error = null }, { Text(st.label) }, Modifier.heightIn(min = 48.dp))
                    }
                }
                if (target == JobStatus.ON_HOLD) {
                    Text("Motivo da espera", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoldReason.entries.forEach { r ->
                            FilterChip(reason == r, { reason = r }, { Text(r.label) }, Modifier.heightIn(min = 48.dp))
                        }
                    }
                    OutlinedTextField(
                        note, { note = it },
                        label = { Text(if (reason == HoldReason.OTHER) "Descrição (obrigatória)" else "Nota (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DatePickerChip(review?.let { "Rever a ${it.shortLabel()}" } ?: "Data de revisão (opcional)", review ?: LocalDate.now(AGENDA_ZONE)) {
                        review = it
                    }
                }
                if (target == JobStatus.CANCELLED) {
                    WarningBox(
                        "As reservas futuras são libertadas." +
                            if (received.isPositive) " Já foram recebidos ${received.format()}: registe a devolução ou o acerto com o cliente." else "",
                    )
                }
                if (target == JobStatus.COMPLETED) {
                    Text(
                        "Concluir não encerra a obra nem a dá como paga. O encerramento é uma revisão à parte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = target != null && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val result = onConfirm(StatusChange(target!!, reason, note, review))
                        busy = false
                        if (result.ok) onResult(result.message) else error = result.message
                    }
                },
            ) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

val HoldReason.label: String
    get() = when (this) {
        HoldReason.MATERIAL -> "Material"
        HoldReason.CLIENT -> "Cliente"
        HoldReason.WEATHER -> "Meteorologia"
        HoldReason.OTHER -> "Outro"
    }

val PaymentSituation.label: String
    get() = when (this) {
        PaymentSituation.NO_PAYMENTS -> "Sem pagamentos"
        PaymentSituation.PARTIAL -> "Parcial"
        PaymentSituation.SETTLED -> "Liquidado"
        PaymentSituation.TO_REGULARIZE -> "A regularizar"
    }

/** Correção de um registo de horas: editar (com histórico) ou anular com motivo. As taxas históricas não mudam. */
@Composable
private fun TimeEntryDialog(
    entry: TimeEntryEntity,
    workerName: String,
    onDismiss: () -> Unit,
    onSave: suspend (LocalDate, Int, TimeEntryKind, Boolean, String?) -> SaveResult,
    onDelete: suspend (String) -> SaveResult,
    onDone: (String) -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(entry.date) }
    var hours by rememberSaveable { mutableStateOf((entry.minutes / 60).toString()) }
    var mins by rememberSaveable { mutableStateOf((entry.minutes % 60).toString()) }
    var kind by rememberSaveable { mutableStateOf(entry.kind) }
    var billable by rememberSaveable { mutableStateOf(entry.billable) }
    var note by rememberSaveable { mutableStateOf(entry.note.orEmpty()) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val minutes = hours.toIntOrNull()?.let { h -> mins.toIntOrNull()?.takeIf { it in 0..59 }?.let { h * 60 + it } }

    if (deleting) {
        ReasonDialog(
            title = "Anular registo",
            message = "O registo deixa de contar, mas fica no histórico com o motivo.",
            confirmLabel = "Anular",
            onDismiss = { deleting = false },
            onConfirm = onDelete,
            onDone = onDone,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corrigir horas · $workerName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Dia", date, LocalDate.now(AGENDA_ZONE)) { date = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, label = { Text("Horas") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(mins, { mins = it.filter(Char::isDigit).take(2) }, label = { Text("Minutos") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeEntryKind.entries.forEach { k ->
                        FilterChip(kind == k, { kind = k }, {
                            Text(when (k) { TimeEntryKind.WORK -> "Trabalho"; TimeEntryKind.TRAVEL -> "Deslocação"; TimeEntryKind.OTHER -> "Outro" })
                        }, Modifier.heightIn(min = 48.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Faturável", modifier = Modifier.weight(1f))
                    Switch(billable, { billable = it })
                }
                OutlinedTextField(note, { note = it }, label = { Text("Nota") }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Taxas guardadas neste registo: ${HourlyRate(entry.saleRateCents).amountFor(60).format()}/h venda · ${HourlyRate(entry.costRateCents).amountFor(60).format()}/h custo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { deleting = true }) { Text("Anular este registo…", color = MaterialTheme.colorScheme.error) }
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = minutes != null && minutes > 0 && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = onSave(date, minutes!!, kind, billable, note)
                    busy = false
                    if (r.ok) onDone(r.message) else error = r.message
                }
            }) { Text("Gravar correção") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
