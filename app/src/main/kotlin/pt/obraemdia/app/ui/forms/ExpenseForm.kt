package pt.obraemdia.app.ui.forms

import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.CallSplit
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.repo.ExpenseLine
import pt.obraemdia.app.data.repo.NewExpense
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.DatePickerChip
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.Picker
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.Nif
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.money.sum
import java.time.LocalDate

val DocumentType.label: String
    get() = when (this) {
        DocumentType.INVOICE -> "Fatura"
        DocumentType.INVOICE_RECEIPT -> "Fatura-recibo"
        DocumentType.SIMPLIFIED_INVOICE -> "Fatura simplificada"
        DocumentType.CREDIT_NOTE -> "Nota de crédito"
        DocumentType.RECEIPT -> "Recibo"
        DocumentType.DELIVERY_NOTE -> "Guia"
        DocumentType.PRO_FORMA -> "Pró-forma"
        DocumentType.OTHER -> "Outro"
    }

val ExpenseCategory.label: String
    get() = when (this) {
        ExpenseCategory.MATERIAL -> "Material"
        ExpenseCategory.TRAVEL -> "Deslocação"
        ExpenseCategory.RENTAL -> "Aluguer"
        ExpenseCategory.TOOL -> "Ferramenta"
        ExpenseCategory.SUBCONTRACT -> "Subcontrato"
        ExpenseCategory.OTHER -> "Outra"
    }

val PaymentMethod.label: String
    get() = when (this) {
        PaymentMethod.CASH -> "Dinheiro"
        PaymentMethod.TRANSFER -> "Transferência"
        PaymentMethod.MBWAY -> "MB WAY"
        PaymentMethod.OTHER -> "Outro"
    }

/** Destino de uma parcela: uma obra, ou despesa geral (id nulo). */
private data class Target(val jobId: String?, val title: String)

private class SplitRow(target: Target?, amount: String) {
    var target by mutableStateOf(target)
    var amount by mutableStateOf(amount)
}

private enum class Assign { ONE_JOB, GENERAL, SPLIT }

@Composable
private fun Chips(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Chip(selected: Boolean, text: String, onClick: () -> Unit) =
    FilterChip(selected, onClick, { Text(text) }, Modifier.heightIn(min = 48.dp))

/** Tipo de documento a partir do código SAF-T do QR (campo D). */
fun qrDocumentType(code: String): DocumentType = when (code.uppercase()) {
    "FT" -> DocumentType.INVOICE
    "FR" -> DocumentType.INVOICE_RECEIPT
    "FS" -> DocumentType.SIMPLIFIED_INVOICE
    "NC" -> DocumentType.CREDIT_NOTE
    "RC", "RG" -> DocumentType.RECEIPT
    "GR", "GT", "GA", "GC", "GD" -> DocumentType.DELIVERY_NOTE
    "PF", "OR", "FO" -> DocumentType.PRO_FORMA
    else -> DocumentType.OTHER
}

@Composable
fun ExpenseScreen(vm: AppViewModel, snapshot: Snapshot, initialJobId: String?, onScan: () -> Unit, onDone: (String) -> Unit) {
    val operationId = rememberSaveable { newId() }
    val jobs = snapshot.summaries.filter { it.job.status != JobStatus.CANCELLED && !it.job.archived }
    val targets = listOf(Target(null, "Despesa geral")) + jobs.map { Target(it.job.id, it.job.title) }

    var total by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(DocumentType.INVOICE) }
    var supplier by rememberSaveable { mutableStateOf("") }
    var nif by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var docDate by rememberSaveable { mutableStateOf(vm.today) }
    var category by rememberSaveable { mutableStateOf(ExpenseCategory.MATERIAL) }
    var vatRate by rememberSaveable { mutableStateOf<Int?>(null) }
    var vatTreatment by rememberSaveable { mutableStateOf(VatTreatment.UNKNOWN) }
    var assign by rememberSaveable { mutableStateOf(Assign.ONE_JOB) }
    var jobId by rememberSaveable { mutableStateOf(initialJobId ?: jobs.firstOrNull { it.job.status == JobStatus.IN_PROGRESS }?.job?.id) }
    val rows = remember { mutableStateListOf(SplitRow(targets.firstOrNull { it.jobId == jobId }, ""), SplitRow(null, "")) }
    var paidNow by rememberSaveable { mutableStateOf(true) }
    var paymentDate by rememberSaveable { mutableStateOf(vm.today) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CASH) }
    var paidBy by rememberSaveable { mutableStateOf<String?>(null) }
    var dueDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var originalId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Dados lidos do QR da AT (câmara ou PDF): base e IVA exatos e o conteúdo original.
    var rawQr by rememberSaveable { mutableStateOf<String?>(null) }
    var atcud by rememberSaveable { mutableStateOf<String?>(null) }
    var qrNetCents by rememberSaveable { mutableStateOf<Long?>(null) }
    var qrVatCents by rememberSaveable { mutableStateOf<Long?>(null) }
    var pdfUri by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    fun applyQr(d: pt.obraemdia.domain.fiscalqr.AtQrDocument, pdf: android.net.Uri?) {
        if (d.isCancelled) {
            error = "Este documento está ANULADO pelo fornecedor: não deve ser registado como gasto."
            return
        }
        total = d.gross.editText()
        type = qrDocumentType(d.docType)
        nif = d.issuerNif
        number = d.docNumber
        docDate = d.date
        atcud = d.atcud
        rawQr = d.raw
        qrNetCents = (d.gross - d.totalTaxes).cents
        qrVatCents = d.totalTaxes.cents
        vatRate = null
        pdfUri = pdf?.toString()
        // O QR não traz o nome comercial: usar o de uma compra anterior com o mesmo NIF.
        if (supplier.isBlank()) supplier = snapshot.purchases.firstOrNull { it.supplierNif == d.issuerNif }?.supplierName.orEmpty()
        if (type == DocumentType.CREDIT_NOTE) paidNow = false
        error = null
    }

    // Resultado do leitor de câmara (partilhado com o ecrã de faturas emitidas).
    val scanned by vm.scannedQr.collectAsState()
    androidx.compose.runtime.LaunchedEffect(scanned) {
        scanned?.let { applyQr(it, null); vm.scannedQr.value = null }
    }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            when (val r = pt.obraemdia.app.fiscal.QrReader.fromPdf(context, uri)) {
                is pt.obraemdia.domain.fiscalqr.AtQrResult.Ok -> applyQr(r.document, uri)
                is pt.obraemdia.domain.fiscalqr.AtQrResult.Invalid -> {
                    // Sem QR: o PDF fica como comprovativo e os valores introduzem-se à mão.
                    pdfUri = uri.toString()
                    error = r.message
                }
            }
            reading = false
        }
    }

    val gross = Money.parse(total)?.takeIf { it.isPositive }
    // Se o total for alterado à mão, a base/IVA do QR deixam de se aplicar.
    val qrAmounts = if (qrNetCents != null && qrVatCents != null && gross?.cents == qrNetCents!! + qrVatCents!!) {
        pt.obraemdia.domain.money.DocumentAmounts(Money(qrNetCents!!), Money(qrVatCents!!), gross)
    } else {
        null
    }
    val isCredit = type == DocumentType.CREDIT_NOTE
    val nifDigits = nif.filter(Char::isDigit)
    val nifWarning = nifDigits.isNotEmpty() && !Nif.isValid(nifDigits)
    val lines: List<ExpenseLine>? = when (assign) {
        Assign.ONE_JOB -> jobId?.let { id -> gross?.let { listOf(ExpenseLine(id, it)) } }
        Assign.GENERAL -> gross?.let { listOf(ExpenseLine(null, it)) }
        Assign.SPLIT -> rows.mapNotNull { r ->
            val amount = Money.parse(r.amount)?.takeIf { it.isPositive }
            if (r.target != null && amount != null) ExpenseLine(r.target!!.jobId, amount) else null
        }
    }
    val allocated = lines.orEmpty().map { it.gross }.sum()
    val unallocated = gross?.let { it - allocated }
    val overAllocated = unallocated?.isNegative == true

    val canSave = type.createsExpense && gross != null && !lines.isNullOrEmpty() && !overAllocated && !saving

    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pt.obraemdia.app.ui.components.CompactAction("Ler QR", Icons.Filled.QrCodeScanner, onScan, Modifier.weight(1f))
            pt.obraemdia.app.ui.components.CompactAction(
                "Importar PDF", Icons.Filled.PictureAsPdf, { pdfPicker.launch(arrayOf("application/pdf")) }, Modifier.weight(1f), enabled = !reading,
            )
        }
        if (reading) Text("A ler o código QR do PDF…", style = MaterialTheme.typography.bodyMedium)
        if (rawQr != null) {
            Text(
                "Dados lidos do código QR da AT" + (if (pdfUri != null) " · o PDF fica como comprovativo" else "") + ". Confira e complete.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (pdfUri != null) {
            Text("O PDF fica como comprovativo.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            total, { total = it }, label = { Text("Total do documento, com IVA (€) *") }, singleLine = true,
            isError = total.isNotBlank() && gross == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Chips("Tipo de documento") {
            DocumentType.entries.forEach { t -> Chip(type == t, t.label) { type = t; if (t == DocumentType.CREDIT_NOTE) paidNow = false } }
        }
        if (!type.createsExpense) {
            WarningBox(
                when (type) {
                    DocumentType.RECEIPT -> "Um recibo prova o pagamento de uma fatura já registada; não é uma nova despesa. Registe o pagamento na fatura (Contas → Por pagar)."
                    else -> "${type.label}s não criam despesa. Registe a fatura quando a receber."
                },
            )
        }
        if (isCredit) {
            val originals = snapshot.purchases.filter { it.grossCents > 0 }
            Picker(
                "Fatura original (opcional)", originals, originals.firstOrNull { it.id == originalId },
                { "${it.supplierName ?: "Sem fornecedor"} · ${it.docDate.shortLabel()} · ${Money(it.grossCents).format()}" },
                { originalId = it.id },
                placeholder = "Escolher fatura…",
            )
            Text(
                "A nota de crédito reduz o custo. Não assume que o dinheiro já foi devolvido.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            supplier, { supplier = it }, label = { Text("Fornecedor") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                nif, { nif = it.filter { c -> c.isDigit() || c == ' ' }.take(11) }, label = { Text("NIF") }, singleLine = true,
                isError = nifWarning,
                supportingText = { if (nifWarning) Text("NIF não parece válido") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(number, { number = it }, label = { Text("Nº documento") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        DateField("Data do documento", docDate, vm.today) { docDate = it }
        Chips("Categoria") { ExpenseCategory.entries.forEach { c -> Chip(category == c, c.label) { category = c } } }

        if (qrAmounts != null) {
            Text("Base ${qrAmounts.net.format()} + impostos ${qrAmounts.vat.format()} (do QR)", style = MaterialTheme.typography.bodyMedium)
        } else {
            Chips("IVA incluído no total") {
                listOf<Int?>(null, 0, 600, 1300, 2300).forEach { bp -> Chip(vatRate == bp, bp?.let(Vat::label) ?: "Não discriminado") { vatRate = bp } }
            }
        }
        if (qrAmounts == null && gross != null && vatRate != null) {
            val split = Vat.splitGross(gross, vatRate!!)
            Text("Base ${split.net.format()} + IVA ${split.vat.format()}", style = MaterialTheme.typography.bodyMedium)
        }
        Chips("O IVA é recuperável?") {
            Chip(vatTreatment == VatTreatment.UNKNOWN, "Por definir") { vatTreatment = VatTreatment.UNKNOWN }
            Chip(vatTreatment == VatTreatment.RECOVERABLE, "Sim") { vatTreatment = VatTreatment.RECOVERABLE }
            Chip(vatTreatment == VatTreatment.NOT_RECOVERABLE, "Não") { vatTreatment = VatTreatment.NOT_RECOVERABLE }
        }

        SectionCard(icon = Icons.AutoMirrored.Filled.CallSplit, title = "A que se refere", caption = "O documento conta uma vez; as parcelas só o distribuem.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(assign == Assign.ONE_JOB, "Uma obra") { assign = Assign.ONE_JOB }
                Chip(assign == Assign.GENERAL, "Despesa geral") { assign = Assign.GENERAL }
                Chip(assign == Assign.SPLIT, "Repartir") { assign = Assign.SPLIT }
            }
            when (assign) {
                Assign.ONE_JOB -> Picker("Obra", jobs, jobs.firstOrNull { it.job.id == jobId }, { it.job.title }, { jobId = it.job.id })
                Assign.GENERAL -> Text("Sem obra específica (ex.: combustível, ferramentas gerais).", style = MaterialTheme.typography.bodySmall)
                Assign.SPLIT -> {
                    rows.forEachIndexed { i, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Picker("Parcela ${i + 1}", targets, row.target, { it.title }, { row.target = it })
                                OutlinedTextField(
                                    row.amount, { row.amount = it }, label = { Text("Valor com IVA (€)") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (rows.size > 1) {
                                IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remover parcela") }
                            }
                        }
                    }
                    AssistChip(
                        onClick = { rows.add(SplitRow(null, unallocated?.takeIf { it.isPositive }?.let { "%d,%02d".format(it.cents / 100, it.cents % 100) } ?: "")) },
                        label = { Text("+ parcela") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (gross != null) {
                LabelValue("Atribuído", allocated.format())
                LabelValue("Por classificar", unallocated!!.format())
                if (overAllocated) WarningBox("As parcelas somam mais do que o total do documento.")
            }
        }

        if (!isCredit) {
            Chips("Pagamento ao fornecedor") {
                Chip(paidNow, "Já está pago") { paidNow = true }
                Chip(!paidNow, "A crédito (por pagar)") { paidNow = false }
            }
            if (paidNow) {
                DateField("Data do pagamento", paymentDate, vm.today) { paymentDate = it }
                Chips("Método") { PaymentMethod.entries.forEach { m -> Chip(method == m, m.label) { method = m } } }
                Chips("Quem pagou") {
                    Chip(paidBy == null, "Não indicar") { paidBy = null }
                    snapshot.workers.forEach { w -> Chip(paidBy == w.id, w.name) { paidBy = w.id } }
                }
            } else {
                Text(
                    "Conta como custo da obra, mas só sai da caixa quando registar o pagamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DatePickerChip(dueDate?.let { "Vence a ${it.shortLabel()}" } ?: "Data de vencimento (opcional)", dueDate ?: vm.today.plusDays(30)) {
                    dueDate = it
                }
            }
        }

        OutlinedTextField(note, { note = it }, label = { Text("Nota (opcional)") }, modifier = Modifier.fillMaxWidth())
        error?.let { WarningBox(it) }

        Button(
            onClick = {
                saving = true
                error = null
                scope.launch {
                    val result = vm.recordExpense(
                        NewExpense(
                            operationId = operationId,
                            supplierName = supplier,
                            supplierNif = nifDigits.takeIf { it.isNotEmpty() },
                            docType = type,
                            docNumber = number,
                            atcud = atcud.takeIf { rawQr != null },
                            docDate = docDate,
                            dueDate = dueDate.takeIf { !paidNow },
                            category = category,
                            gross = gross!!,
                            vatRateBp = vatRate,
                            vatTreatment = vatTreatment,
                            lines = lines!!,
                            paidNow = paidNow && !isCredit,
                            paymentDate = paymentDate,
                            paymentMethod = method,
                            paidByWorkerId = paidBy,
                            originalDocumentId = originalId.takeIf { isCredit },
                            note = note,
                            rawQr = rawQr,
                            exactAmounts = qrAmounts,
                        ),
                        pdfUri?.let(android.net.Uri::parse),
                    )
                    if (result.ok) onDone(result.message) else { saving = false; error = result.message }
                }
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(if (gross != null) "Registar ${if (isCredit) "nota de crédito" else "gasto"} de ${gross.format()}" else "Registar gasto")
        }
    }
}
