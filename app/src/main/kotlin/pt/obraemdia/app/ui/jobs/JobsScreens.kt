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
import pt.obraemdia.app.data.repo.JobSummary
import pt.obraemdia.app.data.repo.Snapshot
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
    jobId: String,
    onRecordTime: () -> Unit,
    onRecordPayment: () -> Unit,
    onNotReady: (String) -> Unit,
) {
    val s = snapshot.summary(jobId)
    if (s == null) {
        Text("Obra não encontrada.", modifier = Modifier.padding(16.dp))
        return
    }
    val job = s.job

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
                }
                job.urgentReason?.let { Text("Motivo da urgência: $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            // FlowRow: com letra grande, os botões passam para a linha seguinte em vez de partir palavras.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRecordTime, modifier = Modifier.heightIn(min = 48.dp)) { Text("Horas", softWrap = false) }
                FilledTonalButton(onClick = { onNotReady("Adicionar gasto") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Gasto", softWrap = false) }
                FilledTonalButton(onClick = onRecordPayment, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pagamento", softWrap = false) }
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
                s.entries.sortedByDescending { it.date }.take(5).forEach { e ->
                    Text(
                        "${e.date.shortLabel()} · ${snapshot.workerName(e.workerId)} · ${formatMinutes(e.minutes)}" +
                            if (job.billingMode == BillingMode.HOURLY) " · ${HourlyRate(e.saleRateCents).amountFor(60).format()}/h" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    LabelValue("Materiais e despesas", "Ainda não registados")
                    LabelValue("Resultado (provisório)", r.result.format())
                    LabelValue("Margem", r.marginPercent?.let { "${it.toPlainString().replace('.', ',')} %" } ?: "—")
                }
            }
        }
        item {
            Button(onClick = { onNotReady("Rever encerramento") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Rever encerramento")
            }
        }
    }
}

val PaymentSituation.label: String
    get() = when (this) {
        PaymentSituation.NO_PAYMENTS -> "Sem pagamentos"
        PaymentSituation.PARTIAL -> "Parcial"
        PaymentSituation.SETTLED -> "Liquidado"
        PaymentSituation.TO_REGULARIZE -> "A regularizar"
    }
