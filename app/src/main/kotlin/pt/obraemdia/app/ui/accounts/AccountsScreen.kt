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
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.repo.PurchaseStatus
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.SaveResult
import pt.obraemdia.app.ui.components.DateField
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.forms.label
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
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
fun AccountsScreen(
    snapshot: Snapshot,
    today: LocalDate,
    onAddExpense: () -> Unit,
    onPaySupplier: suspend (operationId: String, documentId: String, amount: Money, date: LocalDate, method: PaymentMethod) -> SaveResult,
    onMessage: (String) -> Unit,
    onNotReady: (String) -> Unit,
) {
    val month = YearMonth.from(today)
    val monthMovements = snapshot.movementsIn(month)
    val received = monthMovements.filter { it.kind.inflow }.sumMoney { Money(it.amountCents) }
    val paid = monthMovements.filter { !it.kind.inflow }.sumMoney { Money(it.amountCents) }
    val jobTitles = snapshot.jobs.associate { it.job.id to it.job.title }
    val allocationsByDoc = snapshot.allocations.groupBy { it.documentId }
    // Movimento sem obra: despesa geral, ou pagamento de uma compra repartida por várias obras.
    fun where(docId: String?): String {
        val jobsOfDoc = docId?.let { allocationsByDoc[it] }.orEmpty().map { it.jobId }.distinct()
        return if (jobsOfDoc.count { it != null } > 1) "Várias obras" else "Despesa geral"
    }
    val unpaid = snapshot.purchaseStatus.filter { it.outstanding.isPositive }.sortedBy { it.doc.dueDate ?: it.doc.docDate }
    val unclassified = snapshot.purchaseStatus.filter { !it.unallocated.isZero }
    var paying by remember { mutableStateOf<PurchaseStatus?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Resumo de ${month.label()}", caption = "Real · movimentos efetivos") {
                LabelValue("Recebimentos", received.format(), AppColors.Positive)
                LabelValue("Pagamentos", paid.format())
                LabelValue("Saldo do mês", (received - paid).format())
            }
        }
        item {
            Button(onClick = onAddExpense, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Adicionar gasto")
            }
        }
        item {
            SectionCard(
                "Por pagar a fornecedores",
                caption = "Posição em ${today.shortLabel()} · compras registadas e ainda não pagas",
            ) {
                LabelValue("Total", snapshot.toPaySuppliers.format())
                if (unpaid.isEmpty()) Text("Nada por pagar.", style = MaterialTheme.typography.bodyMedium)
                unpaid.forEach { st ->
                    val overdue = st.doc.dueDate?.let { it < today } == true
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${st.doc.supplierName ?: "Fornecedor não indicado"} · ${st.outstanding.format()}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(
                                    "${st.doc.docType.label} de ${st.doc.docDate.shortLabel()}",
                                    st.doc.dueDate?.let { (if (overdue) "VENCIDA a " else "vence a ") + it.shortLabel() },
                                    st.paid.takeIf { it.isPositive }?.let { "já pago ${it.format()}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { paying = st }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pagar") }
                    }
                }
            }
        }
        if (unclassified.isNotEmpty()) {
            item {
                SectionCard("Por classificar", caption = "Partes de documentos ainda sem obra nem despesa geral") {
                    unclassified.forEach { st ->
                        LabelValue("${st.doc.supplierName ?: "Sem fornecedor"} · ${st.doc.docDate.shortLabel()}", st.unallocated.format())
                    }
                }
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
                            "${m.date.shortLabel()} · ${m.jobId?.let(jobTitles::get) ?: where(m.purchaseDocumentId)}",
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

    paying?.let { st ->
        PaySupplierDialog(st, today, onDismiss = { paying = null }, onConfirm = onPaySupplier) { message ->
            paying = null
            onMessage(message)
        }
    }
}

@Composable
private fun PaySupplierDialog(
    status: PurchaseStatus,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: suspend (String, String, Money, LocalDate, PaymentMethod) -> SaveResult,
    onDone: (String) -> Unit,
) {
    val operationId = rememberSaveable { newId() }
    var amount by rememberSaveable { mutableStateOf("%d,%02d".format(status.outstanding.cents / 100, status.outstanding.cents % 100)) }
    var date by rememberSaveable { mutableStateOf(today) }
    var method by rememberSaveable { mutableStateOf(PaymentMethod.TRANSFER) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = Money.parse(amount)
    val valid = parsed != null && parsed.isPositive && parsed <= status.outstanding

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pagar a ${status.doc.supplierName ?: "fornecedor"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Falta pagar ${status.outstanding.format()}")
                OutlinedTextField(
                    amount, { amount = it }, label = { Text("Valor (€)") }, singleLine = true, isError = !valid,
                    supportingText = { if (parsed != null && parsed > status.outstanding) Text("Superior ao que falta pagar") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                DateField("Data", date, today) { date = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaymentMethod.entries.forEach { m ->
                        FilterChip(method == m, { method = m }, { Text(m.label) }, Modifier.heightIn(min = 48.dp))
                    }
                }
                error?.let { WarningBox(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    val result = onConfirm(operationId, status.doc.id, parsed!!, date, method)
                    busy = false
                    if (result.ok) onDone(result.message) else error = result.message
                }
            }) { Text("Registar pagamento") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
