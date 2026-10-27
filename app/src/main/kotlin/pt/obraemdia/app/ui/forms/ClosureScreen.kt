package pt.obraemdia.app.ui.forms

import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.repo.ClosureBalance
import pt.obraemdia.app.data.repo.ClosureConfirmations
import pt.obraemdia.app.data.repo.ClosureReview
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.ReasonDialog
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.WarningBox
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.domain.job.ClosureIssue
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.money.Money
import java.time.Instant
import java.time.format.DateTimeFormatter
import pt.obraemdia.domain.schedule.AGENDA_ZONE

private val stamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private fun Long.stamp() = Instant.ofEpochMilli(this).atZone(AGENDA_ZONE).format(stamp)
private fun Long?.money() = this?.let { Money(it).format() } ?: "Por definir"

@Composable
private fun BalanceCard(title: String, caption: String, b: ClosureBalance) {
    SectionCard(title, caption = caption, icon = Icons.AutoMirrored.Filled.ReceiptLong) {
        LabelValue("Venda sem IVA", b.saleNetCents.money())
        LabelValue("Total devido com IVA", b.dueGrossCents.money())
        LabelValue("Recebido", Money(b.receivedCents).format(), AppColors.Positive)
        LabelValue("Saldo do cliente", b.clientBalanceCents.money())
        LabelValue("Horas-pessoa", formatMinutes(b.minutes))
        LabelValue("Custo interno de mão de obra", Money(b.laborCostCents).format())
        LabelValue("Materiais e despesas", Money(b.costsCents).format())
        LabelValue("Resultado", b.resultCents.money())
        LabelValue("Margem", b.margin?.let { it.replace('.', ',') + " %" } ?: "—")
        if (b.unpaidSuppliersCents > 0) LabelValue("Por pagar a fornecedores", Money(b.unpaidSuppliersCents).format())
    }
}

@Composable
fun ClosureScreen(vm: AppViewModel, snapshot: Snapshot, jobId: String, onMessage: (String) -> Unit, onDone: (String) -> Unit) {
    val summary = snapshot.summary(jobId) ?: return
    val closures = snapshot.closures.filter { it.jobId == jobId }.sortedByDescending { it.revision }
    val closed = snapshot.isClosed(jobId)
    var price by rememberSaveable { mutableStateOf(false) }
    var extras by rememberSaveable { mutableStateOf(false) }
    var hoursCosts by rememberSaveable { mutableStateOf(false) }
    var reviewer by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var review by remember { mutableStateOf<ClosureReview?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var reopening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val confirmations = ClosureConfirmations(price, extras, hoursCosts, reviewer, notes)

    // A revisão é recalculada a partir da base sempre que os dados ou as confirmações mudam.
    LaunchedEffect(snapshot, price, extras, hoursCosts) { if (!closed) review = vm.reviewClosure(jobId, confirmations) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(summary.job.title, style = MaterialTheme.typography.headlineSmall)

        if (closed) {
            val current = closures.first()
            SectionCard("Obra encerrada", icon = Icons.Filled.Lock, caption = "Revisão ${current.revision} · ${current.closedAt.stamp()}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text(
                        "  Alterações com impacto financeiro exigem reabertura com motivo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                current.reviewerWorkerId?.let { LabelValue("Responsável indicado", snapshot.workerName(it)) }
                current.notes?.let { Text("Notas: $it", style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = { reopening = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Reabrir obra") }
            }
        } else {
            review?.let { r -> BalanceCard("Balanço atual", "Calculado agora a partir dos registos", r.balance) }

            SectionCard(icon = Icons.AutoMirrored.Filled.FactCheck, title = "Verificação", caption = "Encerrar é uma ação explícita; concluir o trabalho não basta.") {
                val statusOk = summary.job.status == JobStatus.COMPLETED
                Check(statusOk, if (statusOk) "Trabalho concluído" else "O trabalho ainda não está \"Concluída\" (mude o estado na ficha)")
                val bal = summary.balance?.balance
                Check(bal?.isZero == true, when {
                    bal == null -> "Preço ou IVA por definir"
                    bal.isZero -> "Saldo do cliente regularizado"
                    bal.isPositive -> "Falta receber ${bal.format()}"
                    else -> "Há ${(-bal).format()} a devolver ou regularizar"
                })
                Confirm(price, "Preço final confirmado") { price = it }
                Confirm(extras, "Extras revistos") { extras = it }
                Confirm(hoursCosts, "Horas e despesas revistas") { hoursCosts = it }
            }

            review?.issues?.filterIsInstance<ClosureIssue.Warning>()?.forEach { WarningBox(it.message) }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Responsável pela revisão (opcional)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(reviewer == null, { reviewer = null }, { Text("Não indicar") }, Modifier.heightIn(min = 48.dp))
                    snapshot.workers.forEach { w -> FilterChip(reviewer == w.id, { reviewer = w.id }, { Text(w.name) }, Modifier.heightIn(min = 48.dp)) }
                }
                Text(
                    "Sem contas de acesso, esta indicação não prova quem fez a revisão.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas do encerramento") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            val blockers = review?.issues?.filterIsInstance<ClosureIssue.Blocker>().orEmpty()
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val result = vm.closeJob(jobId, confirmations)
                        busy = false
                        if (result.ok) onDone(result.message) else onMessage(result.message)
                    }
                },
                enabled = review != null && blockers.isEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Encerrar obra") }
            if (blockers.isNotEmpty()) {
                Text(
                    "Por resolver: " + blockers.joinToString("; ") { it.message },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Histórico: todas as revisões ficam guardadas, incluindo as reabertas.
        if (closures.isNotEmpty()) {
            Text("Revisões anteriores", style = MaterialTheme.typography.titleMedium)
            closures.forEach { c ->
                val b = vm.decodeBalance(c)
                SectionCard(
                    icon = Icons.Filled.History,
                    title = "Revisão ${c.revision}",
                    caption = "Encerrada a ${c.closedAt.stamp()}" + (c.reopenedAt?.let { " · reaberta a ${it.stamp()}: ${c.reopenReason}" } ?: ""),
                ) {
                    b?.let {
                        LabelValue("Resultado", it.resultCents.money())
                        LabelValue("Recebido", Money(it.receivedCents).format())
                    }
                }
            }
        }
    }

    if (reopening) {
        ReasonDialog(
            title = "Reabrir obra",
            message = "A revisão atual fica guardada. Ao encerrar de novo é criada uma nova revisão.",
            confirmLabel = "Reabrir",
            onDismiss = { reopening = false },
            onConfirm = { vm.reopenJob(jobId, it) },
            onDone = { reopening = false; onMessage(it) },
        )
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else pt.obraemdia.app.ui.components.WarningIcon,
            contentDescription = if (ok) "Cumprido" else "Por resolver",
            tint = if (ok) AppColors.Positive else AppColors.Amber,
        )
        Text("  $text", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Confirm(checked: Boolean, text: String, onChange: (Boolean) -> Unit) {
    // A linha inteira é tocável (não só o quadrado), e o leitor de ecrã anuncia-a como caixa de verificação.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text("  $text", style = MaterialTheme.typography.bodyLarge)
    }
}
