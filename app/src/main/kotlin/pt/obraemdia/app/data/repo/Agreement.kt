package pt.obraemdia.app.data.repo

import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.db.ClientEntity
import pt.obraemdia.app.data.db.JobChargeEntity
import pt.obraemdia.app.data.db.PriceRevisionEntity
import pt.obraemdia.app.data.db.WorkSiteEntity
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

/** Dados gerais da obra. O cliente pode ser um existente ([clientId]) ou um novo ([newClientName]). */
data class JobDetails(
    val title: String,
    val description: String?,
    val clientId: String?,
    val newClientName: String?,
    val clientPhone: String?,
    val address: String?,
    val priority: Priority,
    val urgentReason: String?,
    val materialsPolicy: MaterialsPolicy,
)

data class AgreementChange(
    val billingMode: BillingMode,
    val fixedPriceNet: Money?,
    val vatRateBp: Int?,
    val approvedOn: LocalDate,
    val reason: String,
)

data class NewCharge(
    val operationId: String,
    val jobId: String,
    val kind: ChargeKind,
    val description: String,
    val amountNet: Money,
    val approvedOn: LocalDate,
    val evidence: String?,
    val includedInPrice: Boolean,
)

/**
 * Dados da obra e acordo com o cliente. O acordo (preço, IVA, modalidade, extras, materiais cobrados)
 * tem impacto financeiro: exige obra aberta e fica no histórico; o anterior nunca é apagado.
 */
class Agreement(private val db: AppDatabase, private val repo: ObraRepository, private val clock: () -> Long) {

    /** Título, notas, cliente e morada podem ser corrigidos mesmo com a obra encerrada; a política de materiais não. */
    suspend fun updateDetails(jobId: String, d: JobDetails) {
        require(d.title.isNotBlank()) { "O título é obrigatório" }
        require(d.priority != Priority.URGENT || !d.urgentReason.isNullOrBlank()) { "Indique o motivo da urgência" }
        val now = clock()
        repo.transaction {
            val job = requireNotNull(db.jobDao().getById(jobId)) { "Obra não encontrada" }
            if (d.materialsPolicy != job.materialsPolicy) repo.ensureOpen(jobId)

            val clientId = when {
                !d.newClientName.isNullOrBlank() ->
                    newId().also { db.clientDao().insert(ClientEntity(it, d.newClientName.trim(), phone = d.clientPhone?.takeIf { p -> p.isNotBlank() }, createdAt = now, updatedAt = now)) }
                else -> d.clientId
            }
            // Telefone de um cliente existente: atualiza o cliente (partilhado entre as suas obras).
            if (clientId != null && d.newClientName.isNullOrBlank()) {
                val c = db.agreementDao().client(clientId)
                val phone = d.clientPhone?.trim()?.takeIf { it.isNotEmpty() }
                if (c != null && c.phone != phone) db.agreementDao().updateClient(c.copy(phone = phone, updatedAt = now))
            }

            val address = d.address?.trim()?.takeIf { it.isNotEmpty() }
            val siteId = when {
                address == null -> null
                job.siteId != null -> {
                    val site = db.agreementDao().site(job.siteId)
                    if (site != null && site.address != address) db.agreementDao().updateSite(site.copy(address = address, label = address, clientId = clientId, updatedAt = now))
                    job.siteId
                }
                else -> newId().also { db.clientDao().insertSite(WorkSiteEntity(it, clientId, address, address, now, now)) }
            }

            db.jobDao().update(
                job.copy(
                    title = d.title.trim(),
                    description = d.description?.takeIf { it.isNotBlank() },
                    clientId = clientId,
                    siteId = siteId,
                    priority = d.priority,
                    urgentReason = d.urgentReason.takeIf { d.priority == Priority.URGENT },
                    materialsPolicy = d.materialsPolicy,
                    updatedAt = now,
                ),
            )
            audit(jobId, "update", "Dados da obra")
        }
    }

    /** Novo acordo de preço: grava a revisão com os valores anteriores e os novos. */
    suspend fun changeAgreement(jobId: String, c: AgreementChange) {
        require(c.reason.isNotBlank()) { "Indique o motivo da alteração" }
        require(c.fixedPriceNet == null || !c.fixedPriceNet.isNegative) { "Preço inválido" }
        val now = clock()
        repo.transaction {
            repo.ensureOpen(jobId)
            val job = requireNotNull(db.jobDao().getById(jobId)) { "Obra não encontrada" }
            val newPrice = c.fixedPriceNet?.cents.takeIf { c.billingMode == BillingMode.FIXED_PRICE }
            require(job.billingMode != c.billingMode || job.fixedPriceNetCents != newPrice || job.vatRateBp != c.vatRateBp) {
                "Não há alterações ao acordo"
            }
            val revision = db.agreementDao().lastRevision(jobId) + 1
            db.agreementDao().insertRevision(
                PriceRevisionEntity(
                    id = newId(), jobId = jobId, revision = revision,
                    oldBillingMode = job.billingMode, newBillingMode = c.billingMode,
                    oldFixedPriceNetCents = job.fixedPriceNetCents, newFixedPriceNetCents = newPrice,
                    oldVatRateBp = job.vatRateBp, newVatRateBp = c.vatRateBp,
                    approvedOn = c.approvedOn, reason = c.reason.trim(), createdAt = now,
                ),
            )
            db.jobDao().update(job.copy(billingMode = c.billingMode, fixedPriceNetCents = newPrice, vatRateBp = c.vatRateBp, updatedAt = now))
            audit(jobId, "agreement", "Revisão $revision · ${c.reason}")
        }
    }

    suspend fun addCharge(input: NewCharge): SaveOutcome {
        require(input.description.isNotBlank()) { "Descreva o extra ou material" }
        require(input.amountNet.isPositive) { "O valor tem de ser positivo" }
        return repo.transaction {
            repo.ensureOpen(input.jobId)
            val id = newId()
            val rowId = db.agreementDao().insertCharge(
                JobChargeEntity(
                    id = id, operationId = input.operationId, jobId = input.jobId, kind = input.kind,
                    description = input.description.trim(), amountNetCents = input.amountNet.cents, approvedOn = input.approvedOn,
                    evidence = input.evidence?.takeIf { it.isNotBlank() }, includedInPrice = input.includedInPrice, createdAt = clock(),
                ),
            )
            if (rowId == -1L) return@transaction SaveOutcome.Duplicate
            audit(input.jobId, "charge", "${input.kind} ${input.amountNet.format()} · ${input.description}")
            SaveOutcome.Saved
        }
    }

    suspend fun cancelCharge(chargeId: String, reason: String) {
        require(reason.isNotBlank()) { "Indique o motivo" }
        repo.transaction {
            val charge = requireNotNull(db.agreementDao().getCharge(chargeId)) { "Registo não encontrado" }
            require(charge.deletedAt == null) { "Já está anulado" }
            repo.ensureOpen(charge.jobId)
            db.agreementDao().updateCharge(charge.copy(deletedAt = clock(), deleteReason = reason.trim()))
            audit(charge.jobId, "charge_cancel", "${charge.description} · $reason")
        }
    }

    private suspend fun audit(jobId: String, action: String, detail: String) {
        db.auditDao().insert(AuditEventEntity(newId(), "job", jobId, action, detail, clock()))
    }
}
