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
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.AppViewModel
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
                    ),
                ).fold(onSuccess = onCreated, onFailure = { saving = false; onError("Não foi possível criar a obra: ${it.message}") })
            }
        }
    }
}

private val activeStatuses = setOf(JobStatus.TO_CONFIRM, JobStatus.SCHEDULED, JobStatus.IN_PROGRESS, JobStatus.ON_HOLD, JobStatus.COMPLETED)

@Composable
fun RecordTimeScreen(vm: AppViewModel, snapshot: Snapshot, initialJobId: String?, onDone: (String) -> Unit) {
    // Um id por abertura do formulário: gravar duas vezes a mesma operação não duplica horas.
    val operationId = rememberSaveable { newId() }
    val jobs = snapshot.summaries.filter { it.job.status in activeStatuses && !it.job.archived }
    val workers = snapshot.workers
    var jobId by rememberSaveable { mutableStateOf(initialJobId ?: jobs.firstOrNull { it.job.status == JobStatus.IN_PROGRESS }?.job?.id) }
    var who by rememberSaveable { mutableStateOf<String?>(null) } // null = os dois
    var date by rememberSaveable { mutableStateOf(vm.today) }
    var hours by rememberSaveable { mutableStateOf("8") }
    var mins by rememberSaveable { mutableStateOf("0") }
    var kind by rememberSaveable { mutableStateOf(TimeEntryKind.WORK) }
    var billable by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val h = hours.toIntOrNull()
    val m = mins.toIntOrNull()
    val minutes = if (h != null && m != null && h >= 0 && m in 0..59) h * 60 + m else null
    val tooLong = minutes != null && minutes > 16 * 60
    val canSave = jobId != null && minutes != null && minutes > 0 && !tooLong && !saving
    val workerIds = who?.let(::listOf) ?: workers.map { it.id }

    FormColumn {
        Picker("Obra", jobs, jobs.firstOrNull { it.job.id == jobId }, { it.job.title }, { jobId = it.job.id })
        ChoiceChips("Quem", listOf<String?>(null) + workers.map { it.id }, who, { id -> id?.let(snapshot::workerName) ?: "Os dois" }) { who = it }
        DateField("Dia", date, vm.today) { date = it }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Duração (por pessoa)", style = MaterialTheme.typography.labelLarge)
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
        error?.let { WarningBox(it) }
        val summary = if (minutes != null && minutes > 0) {
            " · ${workerIds.size} × ${formatMinutes(minutes)}"
        } else {
            ""
        }
        SaveButton("Gravar horas$summary", canSave) {
            saving = true
            scope.launch {
                val result = vm.recordTime(
                    NewTimeEntries(operationId, jobId!!, workerIds, date, minutes!!, kind, billable, note),
                )
                if (result.ok) onDone(result.message) else { saving = false; error = result.message }
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
