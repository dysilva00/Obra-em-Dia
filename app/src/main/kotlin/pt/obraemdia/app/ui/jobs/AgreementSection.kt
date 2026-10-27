package pt.obraemdia.app.ui.jobs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.db.JobChargeEntity
import pt.obraemdia.app.data.repo.JobSummary
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.components.LabelValue
import pt.obraemdia.app.ui.components.SectionCard
import pt.obraemdia.app.ui.forms.label
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat

/** Editar, ligar ao cliente e abrir a morada na app de mapas (estas duas dependem de outras apps). */
@Composable
fun ContactActions(phone: String?, address: String?, onEdit: () -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssistChip(onClick = onEdit, label = { Text("Editar") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, modifier = Modifier.heightIn(min = 48.dp))
        if (!phone.isNullOrBlank()) {
            AssistChip(
                onClick = {
                    val digits = phone.filter { it.isDigit() || it == '+' }
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))) }
                },
                label = { Text("Ligar") },
                leadingIcon = { Icon(Icons.Filled.Call, null) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        if (!address.isNullOrBlank()) {
            AssistChip(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)))) } },
                label = { Text("Ver no mapa") },
                leadingIcon = { Icon(Icons.Filled.Place, null) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Acordo atual, extras e materiais cobrados, e histórico de alterações ao preço. */
@Composable
fun AgreementSection(
    snapshot: Snapshot,
    summary: JobSummary,
    closed: Boolean,
    onChangeAgreement: () -> Unit,
    onAddCharge: (ChargeKind) -> Unit,
    onCancelCharge: (JobChargeEntity) -> Unit,
) {
    val job = summary.job
    val charges = snapshot.charges.filter { it.jobId == job.id }
    val active = charges.filter { it.deletedAt == null }
    val revisions = snapshot.priceRevisions.filter { it.jobId == job.id }.sortedByDescending { it.revision }
    SectionCard("Acordo e extras", caption = "Materiais: ${job.materialsPolicy.label}") {
        val fixed = job.billingMode == BillingMode.FIXED_PRICE
        LabelValue(
            if (fixed) "Preço fixo (sem IVA)" else "Cobrança à hora",
            if (fixed) job.fixedPriceNetCents?.let { Money(it).format() } ?: "Por definir" else "Taxas por registo",
        )
        LabelValue("IVA a cobrar", job.vatRateBp?.let { Vat.label(it) } ?: "Por definir")
        active.forEach { c ->
            val tag = if (c.kind == ChargeKind.EXTRA) "Extra" else "Material"
            TextButton(onClick = { onCancelCharge(c) }, enabled = !closed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("$tag · ${c.description} · ${Money(c.amountNetCents).format()}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            "aprovado ${c.approvedOn.shortLabel()}",
                            c.evidence,
                            "já incluído no preço".takeIf { c.includedInPrice },
                            "não conta: materiais não são cobrados à parte"
                                .takeIf { c.kind == ChargeKind.MATERIAL && job.materialsPolicy != MaterialsPolicy.BILLED_SEPARATELY },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val cancelled = charges.size - active.size
        if (cancelled > 0) Text("$cancelled anulado(s), no histórico", style = MaterialTheme.typography.bodySmall)
        if (!closed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onChangeAgreement, modifier = Modifier.heightIn(min = 48.dp)) { Text("Alterar acordo") }
                OutlinedButton(onClick = { onAddCharge(ChargeKind.EXTRA) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Extra") }
                OutlinedButton(onClick = { onAddCharge(ChargeKind.MATERIAL) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Material cobrado") }
            }
            if (active.isNotEmpty()) Text("Toque num extra ou material para o anular.", style = MaterialTheme.typography.bodySmall)
        }
        if (revisions.isNotEmpty()) {
            Text("Alterações ao acordo", style = MaterialTheme.typography.labelLarge)
            revisions.forEach { r ->
                fun price(mode: BillingMode, cents: Long?) =
                    if (mode == BillingMode.HOURLY) "à hora" else cents?.let { Money(it).format() } ?: "sem preço"
                val vatChange = if (r.oldVatRateBp != r.newVatRateBp) {
                    " · IVA ${r.oldVatRateBp?.let(Vat::label) ?: "—"} → ${r.newVatRateBp?.let(Vat::label) ?: "—"}"
                } else {
                    ""
                }
                Text(
                    "${r.approvedOn.shortLabel()}: ${price(r.oldBillingMode, r.oldFixedPriceNetCents)} → " +
                        "${price(r.newBillingMode, r.newFixedPriceNetCents)}$vatChange · ${r.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
