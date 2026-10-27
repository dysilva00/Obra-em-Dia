package pt.obraemdia.app.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.JobClosureEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.domain.job.ClosureCheck
import pt.obraemdia.domain.job.ClosureFacts
import pt.obraemdia.domain.job.ClosureIssue
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sumMoney
import java.time.LocalDate

/** Alteração recusada porque a obra está encerrada; é preciso reabri-la com motivo. */
class JobClosedException : IllegalStateException("Obra encerrada: reabra-a (com motivo) antes de alterar valores")

/** Balanço guardado em cada revisão de encerramento (cêntimos e minutos). */
@Serializable
data class ClosureBalance(
    val saleNetCents: Long?,
    val dueGrossCents: Long?,
    val receivedCents: Long,
    val clientBalanceCents: Long?,
    val costsCents: Long,
    val laborCostCents: Long,
    val resultCents: Long?,
    val margin: String?,
    val minutes: Int,
    val unpaidSuppliersCents: Long,
)

data class ClosureConfirmations(
    val finalPriceConfirmed: Boolean,
    val extrasReviewed: Boolean,
    val hoursAndCostsReviewed: Boolean,
    val reviewerWorkerId: String?,
    val notes: String?,
)

sealed interface ClosureOutcome {
    data class Closed(val revision: Int) : ClosureOutcome
    data class Blocked(val issues: List<ClosureIssue>) : ClosureOutcome
}

/** Revisão calculada a partir da base (dentro de uma transação), igual para o ecrã e para o fecho. */
data class ClosureReview(val summary: JobSummary, val unpaidSuppliers: Money, val issues: List<ClosureIssue>, val balance: ClosureBalance)

/**
 * Correções auditáveis e encerramento. Nenhum registo financeiro é apagado:
 * movimentos são estornados, horas e documentos são anulados (eliminação lógica) com motivo no histórico.
 */
class Corrections(private val db: AppDatabase, private val repo: ObraRepository, private val clock: () -> Long) {

    private val json = Json { encodeDefaults = true }

    private suspend fun ensureOpen(jobId: String?) = repo.ensureOpen(jobId)

    /** Estorno: movimento de sinal contrário, ligado ao original. Um movimento só se estorna uma vez. */
    suspend fun reverseMovement(movementId: String, reason: String, date: LocalDate) {
        require(reason.isNotBlank()) { "Indique o motivo do estorno" }
        repo.transaction {
            val m = requireNotNull(db.cashDao().getById(movementId)) { "Movimento não encontrado" }
            require(!m.isReversal) { "Um estorno não pode ser estornado; registe um novo movimento" }
            // Pagamentos a fornecedores podem ser corrigidos depois do fecho (a obrigação mantém-se);
            // recebimentos de clientes não.
            if (m.kind != CashKind.SUPPLIER_PAYMENT) ensureOpen(m.jobId)
            val rowId = db.cashDao().insert(
                m.copy(
                    id = newId(),
                    operationId = "reversal:${m.id}",
                    date = date,
                    note = reason.trim(),
                    reversalOfId = m.id,
                    createdAt = clock(),
                    isDemo = false,
                ),
            )
            require(rowId != -1L) { "Este movimento já foi estornado" }
            audit("cash_movement", m.id, "reverse", "${m.kind} ${Money(m.amountCents).format()} · $reason")
        }
    }

    suspend fun updateTimeEntry(id: String, date: LocalDate, minutes: Int, kind: TimeEntryKind, billable: Boolean, note: String?) {
        require(minutes > 0) { "A duração tem de ser positiva" }
        repo.transaction {
            val e = requireNotNull(db.timeEntryDao().getById(id)) { "Registo não encontrado" }
            ensureOpen(e.jobId)
            val updated = e.copy(date = date, minutes = minutes, kind = kind, billable = billable, note = note?.takeIf { it.isNotBlank() }, updatedAt = clock())
            db.timeEntryDao().update(updated)
            audit("time_entry", id, "update", "${e.date} ${e.minutes} min ${if (e.billable) "fat." else "não fat."} → ${updated.date} ${updated.minutes} min ${if (updated.billable) "fat." else "não fat."}")
        }
    }

    suspend fun deleteTimeEntry(id: String, reason: String) {
        require(reason.isNotBlank()) { "Indique o motivo" }
        repo.transaction {
            val e = requireNotNull(db.timeEntryDao().getById(id)) { "Registo não encontrado" }
            ensureOpen(e.jobId)
            db.timeEntryDao().update(e.copy(deletedAt = clock(), updatedAt = clock()))
            audit("time_entry", id, "delete", "${e.date} ${e.minutes} min · $reason")
        }
    }

    /** Anula um documento de compra e estorna, na mesma transação, os pagamentos ainda não estornados. */
    suspend fun voidPurchase(documentId: String, reason: String, date: LocalDate): Int {
        require(reason.isNotBlank()) { "Indique o motivo" }
        return repo.transaction {
            val doc = requireNotNull(db.purchaseDao().getById(documentId)) { "Documento não encontrado" }
            require(doc.deletedAt == null) { "O documento já está anulado" }
            db.purchaseDao().allocationsFor(documentId).map { it.jobId }.distinct().forEach { ensureOpen(it) }
            val payments = db.cashDao().forDocument(documentId)
            val reversed = payments.mapNotNull { it.reversalOfId }.toSet()
            val toReverse = payments.filter { !it.isReversal && it.id !in reversed }
            toReverse.forEach { m ->
                db.cashDao().insert(
                    m.copy(id = newId(), operationId = "reversal:${m.id}", date = date, note = "Documento anulado: $reason", reversalOfId = m.id, createdAt = clock(), isDemo = false),
                )
            }
            db.purchaseDao().update(doc.copy(deletedAt = clock(), updatedAt = clock()))
            audit("purchase", documentId, "void", "$reason · ${toReverse.size} pagamento(s) estornado(s)")
            toReverse.size
        }
    }

    /** Revisão do encerramento a partir da base de dados; usada no ecrã e repetida no fecho. */
    suspend fun review(jobId: String, confirmations: ClosureConfirmations): ClosureReview {
        val f = db.jobFactsDao()
        val row = requireNotNull(f.row(jobId)) { "Obra não encontrada" }
        val docs = f.documents(jobId).associateBy { it.id }
        val costLines = f.allocations(jobId).mapNotNull { a -> docs[a.documentId]?.let { a to it } }
        val summary = JobSummary.of(row, f.timeEntries(jobId), f.cash(jobId), costLines)
        // Dívida a fornecedores em documentos desta obra: não impede o fecho, mas é mostrada.
        val unpaid = docs.values.filter { it.grossCents > 0 }.sumMoney { Money(it.grossCents - db.purchaseDao().paidCents(it.id)) }
        val balance = summary.balance
        val issues = buildList {
            if (balance == null) add(ClosureIssue.Blocker("Preço ou IVA por definir: não é possível confirmar o saldo do cliente"))
            addAll(
                ClosureCheck.issues(
                    ClosureFacts(
                        status = row.job.status,
                        finalPriceConfirmed = confirmations.finalPriceConfirmed,
                        extrasReviewed = confirmations.extrasReviewed,
                        clientBalance = balance?.balance ?: Money.ZERO,
                        hoursAndCostsReviewed = confirmations.hoursAndCostsReviewed,
                        unpaidSupplierAmount = unpaid,
                    ),
                ),
            )
            if (summary.costsWithUnknownVat) add(ClosureIssue.Warning("Há despesas com IVA por classificar: o resultado conta esse IVA como custo"))
        }
        val r = summary.result
        val snapshot = ClosureBalance(
            saleNetCents = summary.saleNet?.cents,
            dueGrossCents = summary.dueGross?.cents,
            receivedCents = summary.received.cents,
            clientBalanceCents = balance?.balance?.cents,
            costsCents = summary.costs.cents,
            laborCostCents = r?.internalLaborCost?.cents ?: 0,
            resultCents = r?.result?.cents,
            margin = r?.marginPercent?.toPlainString(),
            minutes = summary.minutes,
            unpaidSuppliersCents = unpaid.cents,
        )
        return ClosureReview(summary, unpaid, issues, snapshot)
    }

    /** Encerramento transacional: revê de novo dentro da transação e grava uma nova revisão. */
    suspend fun close(jobId: String, confirmations: ClosureConfirmations): ClosureOutcome = repo.transaction {
        val latest = db.closureDao().latest(jobId)
        require(latest == null || latest.reopenedAt != null) { "A obra já está encerrada" }
        val review = review(jobId, confirmations)
        if (review.issues.any { it is ClosureIssue.Blocker }) return@transaction ClosureOutcome.Blocked(review.issues)
        val revision = (latest?.revision ?: 0) + 1
        db.closureDao().insert(
            JobClosureEntity(
                id = newId(),
                jobId = jobId,
                revision = revision,
                closedAt = clock(),
                reviewerWorkerId = confirmations.reviewerWorkerId,
                notes = confirmations.notes?.takeIf { it.isNotBlank() },
                balanceJson = json.encodeToString(review.balance),
            ),
        )
        audit("job", jobId, "close", "Revisão $revision")
        ClosureOutcome.Closed(revision)
    }

    suspend fun reopen(jobId: String, reason: String) {
        require(reason.isNotBlank()) { "Indique o motivo da reabertura" }
        repo.transaction {
            val latest = db.closureDao().latest(jobId)
            require(latest != null && latest.reopenedAt == null) { "A obra não está encerrada" }
            db.closureDao().update(latest.copy(reopenedAt = clock(), reopenReason = reason.trim()))
            audit("job", jobId, "reopen", "Revisão ${latest.revision} · $reason")
        }
    }

    fun decodeBalance(closure: JobClosureEntity): ClosureBalance? =
        runCatching { json.decodeFromString<ClosureBalance>(closure.balanceJson) }.getOrNull()

    private suspend fun audit(entity: String, entityId: String, action: String, detail: String?) {
        db.auditDao().insert(pt.obraemdia.app.data.db.AuditEventEntity(newId(), entity, entityId, action, detail, clock()))
    }
}

/** Movimentos estornáveis: não são estornos e ainda não foram estornados. */
fun CashMovementEntity.canReverse(reversedIds: Set<String>) = !isReversal && id !in reversedIds
