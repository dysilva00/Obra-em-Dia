package pt.obraemdia.app.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.ui.components.IconTile
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import java.time.Instant
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private enum class Group(val label: String) { MONEY("Dinheiro"), WORK("Obras e agenda"), HOURS("Horas"), OTHER("Outros") }

private data class Described(val title: String, val group: Group, val icon: ImageVector)

/** Texto em português para cada tipo de registo do histórico. */
private fun describe(e: AuditEventEntity): Described {
    val k = "${e.entity}/${e.action}"
    return when (k) {
        "job/create" -> Described("Obra criada", Group.WORK, Icons.Filled.Construction)
        "job/update" -> Described("Dados da obra alterados", Group.WORK, Icons.Filled.Construction)
        "job/status" -> Described("Estado da obra alterado", Group.WORK, Icons.Filled.Construction)
        "job/agreement" -> Described("Acordo de preço alterado", Group.MONEY, Icons.Filled.Euro)
        "job/charge" -> Described("Extra ou material cobrado registado", Group.MONEY, Icons.Filled.Euro)
        "job/charge_cancel" -> Described("Extra ou material anulado", Group.MONEY, Icons.Filled.Euro)
        "job/installment", "job/installment_remove" -> Described(if (e.action == "installment") "Prestação acordada" else "Prestação removida", Group.MONEY, Icons.Filled.Euro)
        "job/close" -> Described("Obra encerrada", Group.WORK, Icons.Filled.Construction)
        "job/reopen" -> Described("Obra reaberta", Group.WORK, Icons.Filled.Construction)
        "schedule/create" -> Described("Marcação na agenda", Group.WORK, Icons.Filled.CalendarMonth)
        "time_off/create", "time_off/delete" -> Described(if (e.action == "create") "Indisponibilidade registada" else "Indisponibilidade removida", Group.WORK, Icons.Filled.CalendarMonth)
        "time_entry/create" -> Described("Horas registadas", Group.HOURS, Icons.Filled.Schedule)
        "time_entry/update" -> Described("Horas corrigidas", Group.HOURS, Icons.Filled.Schedule)
        "time_entry/delete" -> Described("Horas anuladas", Group.HOURS, Icons.Filled.Schedule)
        "cash_movement/create" -> Described("Recebimento registado", Group.MONEY, Icons.Filled.Euro)
        "cash_movement/reverse" -> Described("Movimento estornado", Group.MONEY, Icons.Filled.Euro)
        "cash_movement/supplier_payment" -> Described("Pagamento a fornecedor", Group.MONEY, Icons.Filled.Euro)
        "purchase/create" -> Described("Gasto registado", Group.MONEY, Icons.Filled.ShoppingCart)
        "purchase/void" -> Described("Documento de compra anulado", Group.MONEY, Icons.Filled.ShoppingCart)
        "issued_invoice/create" -> Described("Fatura emitida registada", Group.MONEY, Icons.AutoMirrored.Filled.ReceiptLong)
        "issued_invoice/cancel" -> Described("Registo de fatura anulado", Group.MONEY, Icons.AutoMirrored.Filled.ReceiptLong)
        "attachment/create", "attachment/delete" -> Described(if (e.action == "create") "Fotografia adicionada" else "Fotografia removida", Group.OTHER, Icons.Filled.PhotoCamera)
        "worker/update" -> Described("Pessoa ou taxas alteradas", Group.OTHER, Icons.Filled.Settings)
        "setting/update" -> Described("Definição alterada (${e.entityId})", Group.OTHER, Icons.Filled.Settings)
        else -> Described("${e.entity} · ${e.action}", Group.OTHER, Icons.Filled.History)
    }
}

/**
 * Histórico local das alterações relevantes (o que, quando e o motivo). Sem contas de acesso,
 * não identifica quem usou o telemóvel.
 */
@Composable
fun HistoryScreen(events: List<AuditEventEntity>) {
    var group by rememberSaveable { mutableStateOf<Group?>(null) }
    val described = events.map { it to describe(it) }.filter { group == null || it.second.group == group }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "Tudo o que foi criado, corrigido, estornado ou anulado fica aqui, com a data e o motivo. Nada é apagado em silêncio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilterChip(group == null, { group = null }, { Text("Tudo") }, Modifier.heightIn(min = 48.dp))
                Group.entries.forEach { g -> FilterChip(group == g, { group = g }, { Text(g.label) }, Modifier.heightIn(min = 48.dp)) }
            }
        }
        if (described.isEmpty()) item { Text("Sem registos.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(described, key = { it.first.id }) { (e, d) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(d.icon, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall)
                        e.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            Instant.ofEpochMilli(e.at).atZone(AGENDA_ZONE).format(stamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}
