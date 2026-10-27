package pt.obraemdia.app.ui.invoices

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.InvoiceSource
import pt.obraemdia.app.data.db.IssuedInvoiceEntity
import pt.obraemdia.app.data.repo.InvoiceTotals
import pt.obraemdia.app.data.repo.IssuedInvoiceInput
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.fiscal.QrReader
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.CompactAction
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.Picker
import pt.obraemdia.app.ui.components.ReasonDialog
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.forms.editText
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.app.ui.theme.MoneyLarge
import pt.obraemdia.domain.fiscalqr.AtQrDocument
import pt.obraemdia.domain.fiscalqr.AtQrResult
import pt.obraemdia.domain.money.Money
import java.io.File
import java.time.LocalDate

private val typeLabels = mapOf("FT" to "Fatura", "FS" to "Fatura simplificada", "FR" to "Fatura-recibo", "NC" to "Nota de crédito", "ND" to "Nota de débito")

/** Rascunho a rever antes de gravar (vindo do QR, do PDF ou em branco). */
private data class Draft(val input: IssuedInvoiceInput, val warning: String? = null)

@Composable
fun InvoicesScreen(
    vm: AppViewModel,
    snapshot: Snapshot,
    invoices: List<IssuedInvoiceEntity>,
    attachmentPaths: Map<String, String>,
    scanned: AtQrDocument?,
    onScanConsumed: () -> Unit,
    onScan: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var year by rememberSaveable { mutableStateOf(vm.today.year) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<IssuedInvoiceEntity?>(null) }
    var editingLimit by remember { mutableStateOf(false) }
    val limit = snapshot.settings[AppViewModel.INVOICE_LIMIT_KEY]?.toLongOrNull()?.let(::Money)

    // Resultado do leitor de câmara.
    if (scanned != null && draft == null) {
        draft = Draft(IssuedInvoiceInput.fromQr(newId(), scanned, InvoiceSource.QR_CAMERA, null), cancelledWarning(scanned))
        onScanConsumed()
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val r = QrReader.fromPdf(context, uri)
            reading = false
            draft = when (r) {
                is AtQrResult.Ok -> Draft(IssuedInvoiceInput.fromQr(newId(), r.document, InvoiceSource.PDF, uri), cancelledWarning(r.document))
                // Sem QR legível: o PDF fica anexado e os valores introduzem-se à mão.
                is AtQrResult.Invalid -> Draft(blank(vm.today, InvoiceSource.PDF, uri, defaultNif(invoices)), r.message)
            }
        }
    }

    val ofYear = invoices.filter { it.issueDate.year == year }
    val totals = InvoiceTotals.byYearAndIssuer(invoices, year)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Ano anterior") }
                Text("$year", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { year++ }, enabled = year < vm.today.year) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ano seguinte") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactAction("Ler QR", Icons.Filled.QrCodeScanner, onScan, Modifier.weight(1f))
                CompactAction("Importar PDF", Icons.Filled.PictureAsPdf, { pdfPicker.launch(arrayOf("application/pdf")) }, Modifier.weight(1f), enabled = !reading)
                CompactAction("À mão", Icons.Filled.Edit, { draft = Draft(blank(vm.today, InvoiceSource.MANUAL, null, defaultNif(invoices))) }, Modifier.weight(1f))
            }
        }
        if (reading) item { Text("A ler o código QR do PDF…", style = MaterialTheme.typography.bodyMedium) }

        if (totals.isEmpty()) {
            item {
                SectionCard("Faturado em $year", icon = Icons.AutoMirrored.Filled.ReceiptLong, caption = "Ainda sem faturas registadas neste ano") {
                    Text("Registe as faturas que emitem aos clientes para acompanhar o total do ano.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(totals, key = { "t-${it.issuerNif}" }) { t ->
            SectionCard("Faturado em $year", icon = Icons.AutoMirrored.Filled.ReceiptLong, caption = "NIF ${t.issuerNif} · ${t.count} documento(s)") {
                Text(t.net.format(), style = MoneyLarge)
                Text("sem IVA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabelValue("IVA e outros impostos", t.vat.format())
                LabelValue("Total com impostos", t.gross.format())
                if (t.withholding.isPositive) LabelValue("Retenções na fonte", t.withholding.format())
                if (limit != null) {
                    val ratio = (t.net.cents.toFloat() / limit.cents).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    val rest = limit - t.net
                    Text(
                        if (rest.isNegative) "Ultrapassou o limite de ${limit.format()} em ${(-rest).format()}"
                        else "${(ratio * 100).toInt()} % do limite de ${limit.format()} · faltam ${rest.format()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ratio >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            TextButton(onClick = { editingLimit = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (limit == null) "Definir um limite anual a vigiar" else "Alterar limite anual (${limit.format()})")
            }
            Text(
                "Controlo interno com base nas faturas registadas aqui. Não substitui o Portal das Finanças nem aconselhamento contabilístico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Text("Documentos de $year", style = MaterialTheme.typography.titleMedium) }
        if (ofYear.isEmpty()) item { Text("Nenhum documento.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(ofYear, key = { it.id }) { inv ->
            InvoiceRow(inv, snapshot, attachmentPaths, onOpenPdf = { path -> openPdf(context, vm, path) }, onCancel = { cancelling = inv })
        }
    }

    draft?.let { d ->
        ReviewDialog(
            draft = d,
            snapshot = snapshot,
            error = draftError,
            onDismiss = { draft = null; draftError = null },
            onSave = { input ->
                scope.launch {
                    val r = vm.recordInvoice(input)
                    // Erros (ex.: duplicado) ficam visíveis dentro do diálogo; sucesso fecha e avisa.
                    if (r.ok) { draft = null; draftError = null; onMessage(r.message) } else draftError = r.message
                }
            },
        )
    }
    cancelling?.let { inv ->
        ReasonDialog(
            title = "Anular registo",
            message = "${inv.docNumber} · ${Money(inv.grossCents).format()}. Deixa de contar no total do ano, mas fica no histórico.",
            confirmLabel = "Anular",
            onDismiss = { cancelling = null },
            onConfirm = { vm.cancelInvoice(inv.id, it) },
            onDone = { cancelling = null; onMessage(it) },
        )
    }
    if (editingLimit) {
        LimitDialog(limit, onDismiss = { editingLimit = false }) { value ->
            scope.launch { onMessage(vm.setInvoiceLimit(value).message); editingLimit = false }
        }
    }
}

private fun cancelledWarning(d: AtQrDocument): String? = when {
    d.isCancelled -> "Este documento está ANULADO no programa de faturação: não deve contar no total."
    d.docType !in pt.obraemdia.app.data.repo.IssuedInvoices.COUNTED_TYPES ->
        "Documento do tipo ${d.docType}: não é uma fatura emitida e não pode ser registado aqui."
    else -> null
}

/** NIF mais usado até agora, para pré-preencher. */
private fun defaultNif(invoices: List<IssuedInvoiceEntity>) =
    invoices.groupingBy { it.issuerNif }.eachCount().maxByOrNull { it.value }?.key.orEmpty()

private fun blank(today: LocalDate, source: InvoiceSource, pdf: Uri?, nif: String) = IssuedInvoiceInput(
    operationId = newId(), issuerNif = nif, buyerNif = null, buyerName = null, docType = "FR", docNumber = "", atcud = null,
    issueDate = today, net = Money.ZERO, vat = Money.ZERO, gross = Money.ZERO, withholding = Money.ZERO, jobId = null,
    source = source, rawQr = null, pdf = pdf, note = null,
)

private fun openPdf(context: android.content.Context, vm: AppViewModel, relativePath: String) {
    // Copia para a pasta partilhável temporária e abre no leitor de PDF do telemóvel.
    val src = vm.invoiceAttachmentFile(relativePath)
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val copy = File(dir, "fatura-${src.name}").also { src.copyTo(it, overwrite = true) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

@Composable
private fun InvoiceRow(inv: IssuedInvoiceEntity, snapshot: Snapshot, attachmentPaths: Map<String, String>, onOpenPdf: (String) -> Unit, onCancel: () -> Unit) {
    val pdfPath = inv.attachmentId?.let { id -> attachmentPaths[id] }
    Column(Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${typeLabels[inv.docType] ?: inv.docType} · ${inv.docNumber}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        inv.issueDate.shortLabel(),
                        inv.buyerName ?: inv.buyerNif?.let { "NIF $it" },
                        inv.jobId?.let { id -> snapshot.jobs.firstOrNull { it.job.id == id }?.job?.title },
                        when (inv.source) { InvoiceSource.QR_CAMERA -> "QR"; InvoiceSource.PDF -> "PDF"; InvoiceSource.MANUAL -> "manual" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money(inv.netCents).format(), style = MaterialTheme.typography.titleSmall, color = if (inv.netCents < 0) MaterialTheme.colorScheme.error else AppColors.OnSurface)
                Text("c/ imp. ${Money(inv.grossCents).format()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pdfPath != null) {
            TextButton(onClick = { onOpenPdf(pdfPath) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PictureAsPdf, null)
                Text("  Ver PDF")
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ReviewDialog(draft: Draft, snapshot: Snapshot, error: String?, onDismiss: () -> Unit, onSave: (IssuedInvoiceInput) -> Unit) {
    val i = draft.input
    val fromQr = i.rawQr != null
    var nif by rememberSaveable(i.operationId) { mutableStateOf(i.issuerNif) }
    var type by rememberSaveable(i.operationId) { mutableStateOf(i.docType) }
    var number by rememberSaveable(i.operationId) { mutableStateOf(i.docNumber) }
    var date by rememberSaveable(i.operationId) { mutableStateOf(i.issueDate) }
    var net by rememberSaveable(i.operationId) { mutableStateOf(if (fromQr) i.net.editText() else "") }
    var vat by rememberSaveable(i.operationId) { mutableStateOf(if (fromQr) i.vat.editText() else "0,00") }
    var withholding by rememberSaveable(i.operationId) { mutableStateOf(if (i.withholding.isPositive) i.withholding.editText() else "") }
    var buyer by rememberSaveable(i.operationId) { mutableStateOf(i.buyerName.orEmpty()) }
    var jobId by rememberSaveable(i.operationId) { mutableStateOf<String?>(null) }
    val netMoney = Money.parse(net)
    val vatMoney = Money.parse(vat)
    val gross = if (netMoney != null && vatMoney != null) netMoney + vatMoney else null
    val blocked = draft.warning?.let { it.contains("ANULADO") || it.contains("não pode ser registado") } == true
    val jobs = snapshot.summaries.filter { !it.job.archived }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fromQr) "Confirmar fatura lida" else "Fatura emitida") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.warning?.let { WarningBox(it) }
                if (fromQr) Text("Valores lidos do código QR da AT. Confira antes de gravar.", style = MaterialTheme.typography.bodySmall)
                if (i.pdf != null) Text("O PDF fica guardado como anexo.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(nif, { nif = it.filter(Char::isDigit).take(9) }, label = { Text("NIF de quem emitiu") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("FR", "FT", "FS", "NC", "ND").forEach { t -> FilterChip(type == t, { type = t }, { Text(t) }, Modifier.heightIn(min = 48.dp)) }
                }
                Text(typeLabels[type].orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(number, { number = it }, label = { Text("N.º do documento") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("Data", date, LocalDate.now()) { date = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(net, { net = it }, label = { Text("Sem IVA (€)") }, singleLine = true, isError = net.isNotBlank() && netMoney == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(vat, { vat = it }, label = { Text("Impostos (€)") }, singleLine = true, isError = vatMoney == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                gross?.let { LabelValue("Total com impostos", it.format()) }
                OutlinedTextField(withholding, { withholding = it }, label = { Text("Retenção na fonte (€, opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(buyer, { buyer = it }, label = { Text("Cliente (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Obra (opcional)", jobs, jobs.firstOrNull { it.job.id == jobId }, { it.job.title }, { jobId = it.job.id }, placeholder = "Sem obra")
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !blocked && nif.length == 9 && number.isNotBlank() && netMoney != null && vatMoney != null && gross != null,
                onClick = {
                    onSave(
                        i.copy(
                            issuerNif = nif, docType = type, docNumber = number, issueDate = date,
                            net = netMoney!!, vat = vatMoney!!, gross = gross!!,
                            withholding = Money.parse(withholding) ?: Money.ZERO,
                            buyerName = buyer, jobId = jobId,
                        ),
                    )
                },
            ) { Text("Gravar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun LimitDialog(current: Money?, onDismiss: () -> Unit, onSave: (Money?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.editText().orEmpty()) }
    val parsed = text.takeIf { it.isNotBlank() }?.let(Money::parse)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Limite anual a vigiar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Valor sem IVA, por NIF, a partir do qual querem ser avisados (por exemplo o limite de isenção de IVA que se aplica ao vosso caso). " +
                        "A app não assume nenhum valor: confirme-o com o contabilista ou no Portal das Finanças.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(text, { text = it }, label = { Text("Limite (€)") }, singleLine = true,
                    isError = text.isNotBlank() && (parsed == null || !parsed.isPositive),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(enabled = text.isBlank() || (parsed != null && parsed.isPositive), onClick = { onSave(parsed) }) {
                Text(if (text.isBlank()) "Remover limite" else "Gravar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
