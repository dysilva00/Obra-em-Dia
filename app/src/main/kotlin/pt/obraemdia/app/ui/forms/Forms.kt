package pt.obraemdia.app.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import pt.obraemdia.app.data.db.ActiveTimerEntity
import pt.obraemdia.app.data.repo.HourChecks
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import java.time.LocalTime
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.ContactPickerButton
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.Picker
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.label
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun <T> ChoiceChips(label: String, options: List<T>, selected: T, optionLabel: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SaveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text) }
}

/** Opções de IVA: "Por definir" é o padrão, para nunca assumir uma taxa. */
private val vatOptions: List<Int?> = listOf(null, 0, 600, 1300, 2300)
private fun vatLabel(bp: Int?) = bp?.let { Vat.label(it) } ?: "Por definir"

@Composable
fun NewJobScreen(vm: AppViewModel, onCreated: (String) -> Unit, onError: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var client by rememberSaveable { mutableStateOf("") }
    var clientPhone by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(BillingMode.FIXED_PRICE) }
    var materials by rememberSaveable { mutableStateOf(MaterialsPolicy.INCLUDED) }
    var price by rememberSaveable { mutableStateOf("") }
    var vat by rememberSaveable { mutableStateOf<Int?>(null) }
    var priority by rememberSaveable { mutableStateOf(Priority.NORMAL) }
    var urgentReason by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val parsedPrice = price.takeIf { it.isNotBlank() }?.let(Money::parse)
    val priceInvalid = price.isNotBlank() && (parsedPrice == null || parsedPrice.isNegative)
    val urgentMissing = priority == Priority.URGENT && urgentReason.isBlank()
    val canSave = title.isNotBlank() && !priceInvalid && !urgentMissing && !saving

    FormColumn {
        OutlinedTextField(
            title, { title = it }, label = { Text("Título da obra *") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            client, { client = it }, label = { Text("Cliente (opcional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        ContactPickerButton(onPicked = { client = it.name; clientPhone = it.phone.orEmpty() }, onUnavailable = onError)
        if (client.isNotBlank()) {
            OutlinedTextField(
                clientPhone, { clientPhone = it }, label = { Text("Telefone do cliente (opcional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(address, { address = it }, label = { Text("Morada da obra (opcional)") }, modifier = Modifier.fillMaxWidth())
        ChoiceChips("Cobrança", BillingMode.entries, mode, { if (it == BillingMode.FIXED_PRICE) "Preço fixo" else "À hora" }) { mode = it }
        if (mode == BillingMode.FIXED_PRICE) {
            OutlinedTextField(
                price, { price = it },
                label = { Text("Preço sem IVA (€) — pode ficar para depois") },
                isError = priceInvalid,
                supportingText = { if (priceInvalid) Text("Valor inválido") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Usa as taxas habituais de cada pessoa, guardadas em cada registo de horas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChoiceChips("IVA a cobrar ao cliente", vatOptions, vat, ::vatLabel) { vat = it }
        ChoiceChips(
            "Materiais", MaterialsPolicy.entries, materials,
            {
                when (it) {
                    MaterialsPolicy.INCLUDED -> "Incluídos"
                    MaterialsPolicy.BILLED_SEPARATELY -> "Cobrados à parte"
                    MaterialsPolicy.SUPPLIED_BY_CLIENT -> "Do cliente"
                }
            },
        ) { materials = it }
        ChoiceChips("Prioridade", Priority.entries, priority, { it.label }) { priority = it }
        if (priority == Priority.URGENT) {
            OutlinedTextField(
                urgentReason, { urgentReason = it }, label = { Text("Motivo da urgência *") },
                isError = urgentMissing, modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(description, { description = it }, label = { Text("Notas (opcional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        SaveButton("Criar obra", canSave) {
            saving = true
            scope.launch {
                vm.createJob(
                    NewJob(
                        title = title, clientName = client, address = address, billingMode = mode, materialsPolicy = materials,
                        fixedPriceNet = if (mode == BillingMode.FIXED_PRICE) parsedPrice else null, vatRateBp = vat,
                        priority = priority, urgentReason = urgentReason.takeIf { priority == Priority.URGENT }, description = description,
                        clientPhone = clientPhone,
                    ),
                ).fold(onSuccess = onCreated, onFailure = { saving = false; onError("Não foi possível criar a obra: ${it.message}") })
            }
        }
    }
}

private val activeStatuses = setOf(JobStatus.TO_CONFIRM, JobStatus.SCHEDULED, JobStatus.IN_PROGRESS, JobStatus.ON_HOLD, JobStatus.COMPLETED)

/** Aceita "8", "08", "8:30", "8h30", "0830". */
internal fun parseClock(text: String): LocalTime? {
    val t = text.trim().lowercase().replace('h', ':').replace('.', ':')
    val (h, m) = when {
        ':' in t -> t.substringBefore(':') to t.substringAfter(':').ifEmpty { "0" }
        t.length <= 2 -> t to "0"
        t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
        else -> return null
    }
    val hh = h.toIntOrNull() ?: return null
    val mm = m.toIntOrNull() ?: return null
    return if (hh in 0..23 && mm in 0..59) LocalTime.of(hh, mm) else null
}

private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
private fun LocalTime.label() = format(clockFormat)
private fun Long.localTime() = java.time.Instant.ofEpochMilli(this).atZone(AGENDA_ZONE).toLocalTime()

@Composable
fun RecordTimeScreen(
    vm: AppViewModel,
    snapshot: Snapshot,
    initialJobId: String?,
    timerGroupId: String?,
    timers: List<ActiveTimerEntity>,
    onDone: (String) -> Unit,
) {
    // Um id por abertura do formulário: gravar duas vezes a mesma operação não duplica horas.
    val operationId = rememberSaveable { newId() }
    val jobs = snapshot.summaries.filter { it.job.status in activeStatuses && !it.job.archived }
    val workers = snapshot.workers
    val work = snapshot.workHours
    var jobId by rememberSaveable { mutableStateOf(initialJobId ?: jobs.firstOrNull { it.job.status == JobStatus.IN_PROGRESS }?.job?.id) }
    var who by rememberSaveable { mutableStateOf<String?>(null) } // null = todas as pessoas ativas
    var date by rememberSaveable { mutableStateOf(vm.today) }
    var useTimes by rememberSaveable { mutableStateOf(false) }
    var hours by rememberSaveable { mutableStateOf("8") }
    var mins by rememberSaveable { mutableStateOf("0") }
    var startText by rememberSaveable { mutableStateOf(work.morningStart.label()) }
    var endText by rememberSaveable { mutableStateOf(work.afternoonEnd.label()) }
    var breakText by rememberSaveable {
        mutableStateOf(java.time.Duration.between(work.morningEnd, work.afternoonStart).toMinutes().coerceAtLeast(0).toString())
    }
    var kind by rememberSaveable { mutableStateOf(TimeEntryKind.WORK) }
    var billable by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var timerApplied by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Vindo de um cronómetro: preenche obra, pessoas, dia e horário (a lista pode chegar depois do 1.º desenho).
    val timerGroup = timerGroupId?.let { g -> timers.filter { it.groupId == g } }.orEmpty()
    LaunchedEffect(timerGroup.isNotEmpty()) {
        if (timerApplied || timerGroup.isEmpty()) return@LaunchedEffect
        val first = timerGroup.first()
        val started = java.time.Instant.ofEpochMilli(first.startedAt).atZone(AGENDA_ZONE)
        val now = java.time.ZonedDateTime.now(AGENDA_ZONE)
        jobId = first.jobId
        who = if (timerGroup.size == 1 && workers.size > 1) first.workerId else null
        date = started.toLocalDate()
        useTimes = true
        startText = started.toLocalTime().label()
        // Passou da meia-noite: o fim fica para acertar à mão.
        endText = if (now.toLocalDate() == started.toLocalDate()) now.toLocalTime().label() else "23:59"
        breakText = "0"
        timerApplied = true
    }

    val h = hours.toIntOrNull()
    val m = mins.toIntOrNull()
    val start = parseClock(startText)
    val end = parseClock(endText)
    val pause = breakText.toIntOrNull()
    val minutes = if (useTimes) {
        if (start != null && end != null && pause != null) HourChecks.minutesBetween(start, end, pause) else null
    } else {
        if (h != null && m != null && h >= 0 && m in 0..59) h * 60 + m else null
    }
    val tooLong = minutes != null && minutes > HourChecks.MAX_DAY_MINUTES
    val workerIds = who?.let(::listOf) ?: workers.map { it.id }
    val warnings = if (minutes != null && minutes > 0) {
        HourChecks.warnings(
            snapshot.timeEntries, operationId, workerIds, date, minutes,
            start.takeIf { useTimes }, end.takeIf { useTimes }, snapshot::workerName,
        )
    } else {
        emptyList()
    }
    val canSave = jobId != null && minutes != null && minutes > 0 && !tooLong && !saving && (warnings.isEmpty() || confirmed)

    // Último registo (todas as pessoas da mesma operação), para repetir sem voltar a escrever.
    val last = snapshot.timeEntries.groupBy { it.operationId }.values.maxByOrNull { g -> g.maxOf { it.createdAt } }
    val runningHere = timers.filter { t -> t.workerId in workerIds }

    FormColumn {
        if (timerGroupId != null) {
            Text("Horas do cronómetro: confirme o horário e a pausa antes de gravar.", style = MaterialTheme.typography.bodyMedium)
        } else if (last != null) {
            val l = last.first()
            OutlinedButton(
                onClick = {
                    jobId = l.jobId.takeIf { id -> jobs.any { it.job.id == id } } ?: jobId
                    who = if (last.size == 1 && workers.size > 1) l.workerId else null
                    kind = l.kind
                    billable = l.billable
                    if (l.startMs != null && l.endMs != null) {
                        useTimes = true
                        startText = l.startMs.localTime().label()
                        endText = l.endMs.localTime().label()
                        breakText = l.breakMinutes.toString()
                    } else {
                        useTimes = false
                        hours = (l.minutes / 60).toString()
                        mins = (l.minutes % 60).toString()
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    "Repetir último: ${snapshot.summary(l.jobId)?.job?.title ?: "obra"} · ${formatMinutes(l.minutes)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Picker("Obra", jobs, jobs.firstOrNull { it.job.id == jobId }, { it.job.title }, { jobId = it.job.id })
        if (workers.size > 1) {
            ChoiceChips("Quem", listOf<String?>(null) + workers.map { it.id }, who, { id -> id?.let(snapshot::workerName) ?: snapshot.everyoneLabel }) { who = it }
        }
        DateField("Dia", date, vm.today) { date = it }
        ChoiceChips("Como indicar", listOf(false, true), useTimes, { if (it) "Início e fim" else "Duração" }) { useTimes = it }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (useTimes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        startText, { startText = it.take(5) }, label = { Text("Início") }, singleLine = true, isError = start == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        endText, { endText = it.take(5) }, label = { Text("Fim") }, singleLine = true, isError = end == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        breakText, { breakText = it.filter(Char::isDigit).take(3) }, label = { Text("Pausa (min)") }, singleLine = true,
                        isError = pause == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    when {
                        start == null || end == null || pause == null -> "Horas no formato 8:30."
                        minutes == null -> "O fim tem de ser depois do início, descontada a pausa."
                        else -> (if (workers.size > 1) "Por pessoa: " else "Total: ") + formatMinutes(minutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (minutes == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(if (workers.size > 1) "Duração (por pessoa)" else "Duração", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        hours, { hours = it.filter(Char::isDigit).take(2) }, label = { Text("Horas") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        mins, { mins = it.filter(Char::isDigit).take(2) }, label = { Text("Minutos") }, singleLine = true,
                        isError = m != null && m > 59,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 8).forEach { preset ->
                        FilterChip(
                            selected = minutes == preset * 60,
                            onClick = { hours = preset.toString(); mins = "0" },
                            label = { Text("$preset h") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            if (tooLong) Text("Mais de 16 horas num dia: confirme a duração.", color = MaterialTheme.colorScheme.error)
        }
        ChoiceChips(
            "Tipo", TimeEntryKind.entries, kind,
            { when (it) { TimeEntryKind.WORK -> "Trabalho"; TimeEntryKind.TRAVEL -> "Deslocação"; TimeEntryKind.OTHER -> "Outro" } },
        ) { kind = it }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Faturável ao cliente", modifier = Modifier.weight(1f))
            Switch(billable, { billable = it })
        }
        OutlinedTextField(note, { note = it }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        if (warnings.isNotEmpty()) {
            warnings.forEach { WarningBox(it) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(confirmed, role = Role.Checkbox) { confirmed = it },
            ) {
                Checkbox(confirmed, onCheckedChange = null)
                Text("Está certo, gravar mesmo assim", modifier = Modifier.padding(start = 8.dp))
            }
        }
        error?.let { WarningBox(it) }
        val summary = if (minutes != null && minutes > 0) " · ${workerIds.size} × ${formatMinutes(minutes)}" else ""
        SaveButton("Gravar horas$summary", canSave) {
            saving = true
            scope.launch {
                val result = vm.recordTime(
                    NewTimeEntries(
                        operationId, jobId!!, workerIds, date, minutes!!, kind, billable, note,
                        start = start.takeIf { useTimes }, end = end.takeIf { useTimes },
                        breakMinutes = if (useTimes) pause ?: 0 else 0,
                        confirmWarnings = confirmed, timerGroupId = timerGroupId,
                    ),
                )
                if (result.ok) onDone(result.message) else { saving = false; error = result.message }
            }
        }
        // Cronómetro: só para hoje e quando não se está já a registar um.
        if (timerGroupId == null && date == vm.today) {
            if (runningHere.isNotEmpty()) {
                Text(
                    "Já há um cronómetro a contar para ${runningHere.joinToString(" e ") { snapshot.workerName(it.workerId) }}: pare-o no Início.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(
                    onClick = {
                        saving = true
                        scope.launch {
                            val r = vm.startTimer(jobId!!, workerIds)
                            if (r.ok) onDone("Cronómetro a contar. Pare-o no Início quando acabar.") else { saving = false; error = r.message }
                        }
                    },
                    enabled = jobId != null && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Filled.Timer, contentDescription = null)
                    Text("  Começar agora com cronómetro")
                }
            }
        }
    }
}

@Composable
fun RecordPaymentScreen(vm: AppViewModel, snapshot: Snapshot, initialJobId: String?, onDone: (String) -> Unit) {
    val operationId = rememberSaveable { newId() }
    val jobs = snapshot.summaries.filter { it.job.status != JobStatus.CANCELLED && !it.job.archived }
    var jobId by rememberSaveable { mutableStateOf(initialJobId) }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(vm.today) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.TRANSFER) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val parsed = Money.parse(amount)
    val selected = jobs.firstOrNull { it.job.id == jobId }
    val canSave = selected != null && parsed != null && parsed.isPositive && !saving

    FormColumn {
        Picker("Obra", jobs, selected, { it.job.title }, { jobId = it.job.id })
        selected?.let { s ->
            Text(
                s.balance?.let { "Falta receber: ${it.balance.format()}" } ?: "Valor da obra por definir",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            amount, { amount = it }, label = { Text("Valor recebido (€) *") }, singleLine = true,
            isError = amount.isNotBlank() && (parsed == null || !parsed.isPositive),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        selected?.balance?.let { b ->
            if (parsed != null && parsed > b.balance && b.balance.isPositive) {
                WarningBox("O valor é superior ao que falta receber. O excesso fica \"a regularizar\".")
            }
        }
        DateField("Data do recebimento", date, vm.today) { date = it }
        ChoiceChips(
            "Método", PaymentMethod.entries, method,
            { when (it) { PaymentMethod.CASH -> "Dinheiro"; PaymentMethod.TRANSFER -> "Transferência"; PaymentMethod.MBWAY -> "MB WAY"; PaymentMethod.OTHER -> "Outro" } },
        ) { method = it }
        OutlinedTextField(note, { note = it }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        error?.let { WarningBox(it) }
        SaveButton(if (parsed != null && parsed.isPositive) "Registar ${parsed.format()}" else "Registar pagamento", canSave) {
            saving = true
            scope.launch {
                val result = vm.recordPayment(NewPayment(operationId, jobId!!, parsed!!, date, method, note))
                if (result.ok) onDone(result.message) else { saving = false; error = result.message }
            }
        }
    }
}
