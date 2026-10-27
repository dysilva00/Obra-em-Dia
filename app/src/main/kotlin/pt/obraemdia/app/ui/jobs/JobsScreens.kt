package pt.obraemdia.app.ui.jobs

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
import androidx.compose.material.icons.Icons
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
import pt.obraemdia.app.demo.DemoData
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.PriorityTag
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.components.StatusTag
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.theme.AppColors
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.PaymentSituation

@Composable
fun JobsScreen(onOpenJob: (String) -> Unit, onNotReady: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val jobs = DemoData.jobs.filter { job ->
        query.isBlank() || listOfNotNull(job.title, job.client, job.address).any { it.contains(query, ignoreCase = true) }
    }

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
            Button(onClick = { onNotReady("Nova obra") }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Nova obra")
            }
        }
        if (jobs.isEmpty()) {
            item { Text("Nenhuma obra encontrada.", modifier = Modifier.padding(8.dp)) }
        }
        items(jobs, key = { it.id }) { job ->
            OutlinedCard(onClick = { onOpenJob(job.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(job.client ?: "Sem cliente", job.address).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusTag(job.status)
                        PriorityTag(job.priority)
                    }
                    LabelValue("Falta receber", job.balance.balance.format())
                }
            }
        }
    }
}

@Composable
fun JobDetailScreen(jobId: String, onNotReady: (String) -> Unit) {
    val job = DemoData.jobs.firstOrNull { it.id == jobId }
    if (job == null) {
        Text("Obra não encontrada.", modifier = Modifier.padding(16.dp))
        return
    }
    val balance = job.balance
    val result = job.result

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(job.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(job.client ?: "Sem cliente associado", job.address).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(job.status)
                    PriorityTag(job.priority)
                }
            }
        }
        item {
            // FlowRow: com letra grande, os botões passam para a linha seguinte em vez de partir palavras.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Horas" to "Registar horas", "Gasto" to "Adicionar gasto", "Pagamento" to "Registar pagamento").forEach { (text, action) ->
                    FilledTonalButton(onClick = { onNotReady(action) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text, softWrap = false)
                    }
                }
            }
        }
        item {
            val mode = when (job.mode) {
                BillingMode.FIXED_PRICE -> "Preço fixo"
                BillingMode.HOURLY -> "À hora"
            }
            SectionCard("Cliente e pagamentos", caption = "$mode · valores com IVA quando aplicável") {
                LabelValue("Valor da obra", job.saleValue.format())
                LabelValue("Recebido", balance.netReceived.format(), AppColors.Positive)
                LabelValue(if (balance.balance.isNegative) "A regularizar" else "Falta receber", balance.balance.format())
                LabelValue("Situação", balance.situation.label)
            }
        }
        item {
            SectionCard("Esforço e custos", caption = "Real · registos até hoje") {
                LabelValue("Horas-pessoa", formatMinutes(job.hoursMinutes))
                LabelValue("Materiais e despesas", job.result.costsNetOfRecoverableVat.format())
                LabelValue("Custo interno de mão de obra", result.internalLaborCost.format())
            }
        }
        item {
            SectionCard("Resultado de gestão", caption = "Antes de impostos sobre o rendimento · não é um valor fiscal") {
                LabelValue("Resultado", result.result.format())
                LabelValue("Margem", result.marginPercent?.let { "${it.toPlainString().replace('.', ',')} %" } ?: "—")
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
