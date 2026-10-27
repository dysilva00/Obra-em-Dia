package pt.obraemdia.app.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.db.ClientEntity
import pt.obraemdia.app.data.repo.AgreementChange
import pt.obraemdia.app.data.repo.JobDetails
import pt.obraemdia.app.data.repo.JobSummary
import pt.obraemdia.app.data.repo.NewCharge
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.SaveResult
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.Picker
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.label
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import java.time.LocalDate

val MaterialsPolicy.label: String
    get() = when (this) {
        MaterialsPolicy.INCLUDED -> "Incluídos no preço"
        MaterialsPolicy.BILLED_SEPARATELY -> "Cobrados à parte"
        MaterialsPolicy.SUPPLIED_BY_CLIENT -> "Fornecidos pelo cliente"
    }

/** Texto de um montante para edição ("1234,50"). */
fun Money.editText(): String = "%d,%02d".format(cents / 100, cents % 100)

@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o -> FilterChip(o == selected, { onSelect(o) }, { Text(text(o)) }, Modifier.heightIn(min = 48.dp)) }
        }
    }
}

/** Dados gerais da obra. O acordo de preço muda-se à parte, na ficha, com motivo e histórico. */
@Composable
fun EditJobScreen(vm: AppViewModel, summary: JobSummary, clients: List<ClientEntity>, closed: Boolean, onDone: (String) -> Unit) {
    val job = summary.job
    var title by rememberSaveable { mutableStateOf(job.title) }
    var description by rememberSaveable { mutableStateOf(job.description.orEmpty()) }
    var clientId by rememberSaveable { mutableStateOf(job.clientId) }
    var newClient by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf(summary.row.clientPhone.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(summary.row.siteAddress.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(job.priority) }
    var urgent by rememberSaveable { mutableStateOf(job.urgentReason.orEmpty()) }
    var materials by rememberSaveable { mutableStateOf(job.materialsPolicy) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val noClient = ClientEntity("", "Sem cliente", createdAt = 0, updatedAt = 0)

    Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            title, { title = it }, label = { Text("Título *") }, singleLine = true, isError = title.isBlank(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth(),
        )
        Picker(
            "Cliente", listOf(noClient) + clients, clients.firstOrNull { it.id == clientId } ?: noClient, { it.name },
            { clientId = it.id.takeIf { id -> id.isNotEmpty() }; newClient = ""; phone = it.phone.orEmpty() },
        )
        OutlinedTextField(
            newClient, { newClient = it }, label = { Text("…ou novo cliente") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
        )
        if (clientId != null || newClient.isNotBlank()) {
            OutlinedTextField(
                phone, { phone = it }, label = { Text("Telefone do cliente (opcional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(address, { address = it }, label = { Text("Morada da obra") }, modifier = Modifier.fillMaxWidth())
        ChipRow("Prioridade", Priority.entries, priority, { it.label }) { priority = it }
        if (priority == Priority.URGENT) {
            OutlinedTextField(urgent, { urgent = it }, label = { Text("Motivo da urgência *") }, isError = urgent.isBlank(), modifier = Modifier.fillMaxWidth())
        }
        if (closed) {
            Text("Materiais: ${materials.label} (obra encerrada: não se altera)", style = MaterialTheme.typography.bodyMedium)
        } else {
            ChipRow("Materiais", MaterialsPolicy.entries, materials, { it.label }) { materials = it }
        }
        OutlinedTextField(description, { description = it }, label = { Text("Notas") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        error?.let { WarningBox(it) }
        Button(
            enabled = title.isNotBlank() && (priority != Priority.URGENT || urgent.isNotBlank()) && !saving,
            onClick = {
                saving = true
                scope.launch {
                    val r = vm.updateJobDetails(
                        job.id,
                        JobDetails(title, description, clientId, newClient.takeIf { it.isNotBlank() }, phone, address, priority, urgent, materials),
                    )
                    if (r.ok) onDone(r.message) else { saving = false; error = r.message }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Guardar") }
    }
}

/** Alterar preço, IVA ou modalidade: cria uma revisão do acordo com motivo. */
@Composable
fun AgreementDialog(summary: JobSummary, today: LocalDate, onDismiss: () -> Unit, onConfirm: suspend (AgreementChange) -> SaveResult, onDone: (String) -> Unit) {
    val job = summary.job
    var mode by rememberSaveable { mutableStateOf(job.billingMode) }
    var price by rememberSaveable { mutableStateOf(job.fixedPriceNetCents?.let { Money(it).editText() }.orEmpty()) }
    var vat by rememberSaveable { mutableStateOf(job.vatRateBp) }
    var approvedOn by rememberSaveable { mutableStateOf(today) }
    var reason by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = price.takeIf { it.isNotBlank() }?.let(Money::parse)
    val priceInvalid = price.isNotBlank() && parsed == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alterar acordo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O acordo atual fica no histórico.", style = MaterialTheme.typography.bodySmall)
                ChipRow("Cobrança", BillingMode.entries, mode, { if (it == BillingMode.FIXED_PRICE) "Preço fixo" else "À hora" }) { mode = it }
                if (mode == BillingMode.FIXED_PRICE) {
                    OutlinedTextField(
                        price, { price = it }, label = { Text("Preço sem IVA (€)") }, singleLine = true, isError = priceInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                ChipRow("IVA a cobrar", listOf<Int?>(null, 0, 600, 1300, 2300), vat, { it?.let(Vat::label) ?: "Por definir" }) { vat = it }
                DateField("Aprovado em", approvedOn, today) { approvedOn = it }
                OutlinedTextField(reason, { reason = it }, label = { Text("Motivo (obrigatório)") }, modifier = Modifier.fillMaxWidth())
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank() && !priceInvalid && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = onConfirm(AgreementChange(mode, parsed, vat, approvedOn, reason))
                    busy = false
                    if (r.ok) onDone(r.message) else error = r.message
                }
            }) { Text("Gravar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Novo extra aprovado ou material cobrado ao cliente (valores sem IVA). */
@Composable
fun ChargeDialog(
    jobId: String,
    kind: ChargeKind,
    materialsPolicy: MaterialsPolicy,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: suspend (NewCharge) -> SaveResult,
    onDone: (String) -> Unit,
) {
    val operationId = rememberSaveable { newId() }
    var description by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var approvedOn by rememberSaveable { mutableStateOf(today) }
    var evidence by rememberSaveable { mutableStateOf("") }
    var included by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = Money.parse(amount)?.takeIf { it.isPositive }
    val isExtra = kind == ChargeKind.EXTRA

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExtra) "Extra aprovado" else "Material cobrado ao cliente") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isExtra && materialsPolicy != MaterialsPolicy.BILLED_SEPARATELY) {
                    WarningBox("A política desta obra é \"${materialsPolicy.label}\": este valor só entra na venda se os materiais forem cobrados à parte.")
                }
                OutlinedTextField(description, { description = it }, label = { Text("Descrição *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    amount, { amount = it }, label = { Text("Valor sem IVA (€) *") }, singleLine = true,
                    isError = amount.isNotBlank() && parsed == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                DateField(if (isExtra) "Aprovado em" else "Data", approvedOn, today) { approvedOn = it }
                if (isExtra) {
                    OutlinedTextField(evidence, { evidence = it }, label = { Text("Evidência da aprovação (ex.: SMS, conversa)") }, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Já incluído no preço acordado", modifier = Modifier.weight(1f))
                    Switch(included, { included = it })
                }
                if (included) Text("Fica registado, mas não soma outra vez ao valor da obra.", style = MaterialTheme.typography.bodySmall)
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = description.isNotBlank() && parsed != null && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = onConfirm(NewCharge(operationId, jobId, kind, description, parsed!!, approvedOn, evidence, included))
                    busy = false
                    if (r.ok) onDone(r.message) else error = r.message
                }
            }) { Text("Gravar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
