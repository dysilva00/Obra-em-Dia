package pt.obraemdia.app.data.repo

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.ClientEntity
import pt.obraemdia.app.data.db.CostAllocationEntity
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.PurchaseDocumentEntity
import pt.obraemdia.app.data.db.JobEntity
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.ScheduleRow
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.WorkSiteEntity
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.Costs
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.app.data.db.ScheduleAssignmentEntity
import pt.obraemdia.app.data.db.ScheduleEntryEntity
import pt.obraemdia.domain.job.JobTransitions
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.job.TransitionResult
import pt.obraemdia.domain.money.Allocation
import pt.obraemdia.domain.money.DocumentAmounts
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.Conflict
import pt.obraemdia.domain.schedule.ConflictDetector
import pt.obraemdia.domain.schedule.TimeSlot
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Resultado de uma gravação idempotente: [Duplicate] significa que a operação já tinha sido gravada. */
enum class SaveOutcome { Saved, Duplicate }

data class NewJob(
    val title: String,
    val clientName: String?,
    val address: String?,
    val billingMode: BillingMode,
    val materialsPolicy: MaterialsPolicy,
    val fixedPriceNet: Money?,
    val vatRateBp: Int?,
    val priority: Priority,
    val urgentReason: String?,
    val description: String?,
)

data class NewTimeEntries(
    /** Gerado quando o formulário abre; repetir a gravação com o mesmo id não duplica. */
    val operationId: String,
    val jobId: String,
    /** "Os dois" = duas pessoas: cria um registo por pessoa, editáveis separadamente. */
    val workerIds: List<String>,
    val date: LocalDate,
    val minutes: Int,
    val kind: TimeEntryKind,
    val billable: Boolean,
    val note: String?,
)

data class NewPayment(
    val operationId: String,
    val jobId: String,
    val amount: Money,
    val date: LocalDate,
    val method: PaymentMethod,
    val note: String?,
)

data class NewBooking(
    val operationId: String,
    val jobId: String,
    val workerIds: Set<String>,
    /** Uma reserva por dia; obras com dias descontínuos são simplesmente vários dias. */
    val slots: List<TimeSlot>,
    val firmness: BookingFirmness,
    /** Obrigatório para gravar quando há conflitos ("manter a sobreposição"). */
    val overlapReason: String?,
    val note: String?,
)

sealed interface BookingOutcome {
    data class Saved(val days: Int, val jobBecameScheduled: Boolean) : BookingOutcome
    data class Conflicts(val conflicts: List<Conflict>) : BookingOutcome
    data object Duplicate : BookingOutcome
}

/** Parcela de um gasto: [jobId] nulo = despesa geral. Valor com IVA, positivo como no papel. */
data class ExpenseLine(val jobId: String?, val gross: Money)

data class NewExpense(
    val operationId: String,
    val supplierName: String?,
    val supplierNif: String?,
    val docType: DocumentType,
    val docNumber: String?,
    val atcud: String?,
    val docDate: LocalDate,
    val dueDate: LocalDate?,
    val category: ExpenseCategory,
    /** Total com IVA, positivo como aparece no documento; o sinal vem do tipo. */
    val gross: Money,
    /** Null = IVA não discriminado (o total entra como custo). */
    val vatRateBp: Int?,
    val vatTreatment: VatTreatment,
    val lines: List<ExpenseLine>,
    val paidNow: Boolean,
    val paymentDate: LocalDate?,
    val paymentMethod: PaymentMethod?,
    val paidByWorkerId: String?,
    val originalDocumentId: String? = null,
    val rawQr: String? = null,
    val note: String? = null,
)

sealed interface ExpenseOutcome {
    data class Saved(val documentId: String, val unallocated: Money) : ExpenseOutcome
    /** [existing] é o documento já registado, para o abrir em vez de duplicar. */
    data class Duplicate(val existing: PurchaseDocumentEntity?) : ExpenseOutcome
}

private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

sealed interface StatusOutcome {
    data class Changed(val releasedBookings: Int) : StatusOutcome
    data class Rejected(val message: String) : StatusOutcome
}

class ObraRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    val snapshot: Flow<Snapshot> = combine(
        combine(
            db.workerDao().observeAll(),
            db.jobDao().observeAll(),
            db.timeEntryDao().observeAll(),
            db.cashDao().observeAll(),
            db.settingsDao().observeAll().map(Settings::toMap),
        ) { workers, jobs, entries, cash, settings -> Snapshot(workers, jobs, entries, cash, settings) },
        db.purchaseDao().observeAll(),
        db.purchaseDao().observeAllocations(),
        db.closureDao().observeAll(),
    ) { base, purchases, allocations, closures -> base.copy(purchases = purchases, allocations = allocations, closures = closures) }

    fun schedule(fromMs: Long, toMs: Long): Flow<Pair<List<ScheduleRow>, List<TimeOffEntity>>> =
        combine(db.scheduleDao().observeRange(fromMs, toMs), db.scheduleDao().observeTimeOff(fromMs, toMs)) { a, b -> a to b }

    suspend fun createJob(input: NewJob): String {
        require(input.title.isNotBlank()) { "O título é obrigatório" }
        val now = clock()
        val jobId = newId()
        transaction {
            val clientId = input.clientName?.takeIf { it.isNotBlank() }?.let { name ->
                newId().also { db.clientDao().insert(ClientEntity(it, name.trim(), createdAt = now, updatedAt = now)) }
            }
            val siteId = input.address?.takeIf { it.isNotBlank() }?.let { address ->
                newId().also {
                    db.clientDao().insertSite(WorkSiteEntity(it, clientId, address.trim(), address.trim(), now, now))
                }
            }
            db.jobDao().insert(
                JobEntity(
                    id = jobId,
                    title = input.title.trim(),
                    description = input.description?.takeIf { it.isNotBlank() },
                    clientId = clientId,
                    siteId = siteId,
                    billingMode = input.billingMode,
                    materialsPolicy = input.materialsPolicy,
                    fixedPriceNetCents = input.fixedPriceNet?.cents,
                    vatRateBp = input.vatRateBp,
                    status = JobStatus.TO_CONFIRM,
                    priority = input.priority,
                    urgentReason = input.urgentReason,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            audit("job", jobId, "create", input.title)
        }
        return jobId
    }

    /** Copia as taxas habituais de cada pessoa para o registo: o histórico não muda se a taxa mudar. */
    suspend fun recordTime(input: NewTimeEntries): SaveOutcome {
        require(input.minutes > 0) { "A duração tem de ser positiva" }
        require(input.workerIds.isNotEmpty()) { "Escolha pelo menos uma pessoa" }
        val now = clock()
        return transaction {
            ensureOpen(input.jobId)
            val workers = db.workerDao().getAll().associateBy { it.id }
            val entries = input.workerIds.map { workerId ->
                val worker = requireNotNull(workers[workerId]) { "Pessoa desconhecida" }
                TimeEntryEntity(
                    id = newId(),
                    operationId = input.operationId,
                    jobId = input.jobId,
                    workerId = workerId,
                    date = input.date,
                    minutes = input.minutes,
                    kind = input.kind,
                    billable = input.billable,
                    saleRateCents = worker.defaultSaleRateCents,
                    costRateCents = worker.defaultCostRateCents,
                    note = input.note?.takeIf { it.isNotBlank() },
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val inserted = db.timeEntryDao().insertAll(entries).count { it != -1L }
            if (inserted == 0) return@transaction SaveOutcome.Duplicate
            audit("time_entry", input.operationId, "create", "${entries.size} registo(s), ${input.minutes} min em ${input.date}")
            SaveOutcome.Saved
        }
    }

    suspend fun recordPayment(input: NewPayment): SaveOutcome {
        require(input.amount.isPositive) { "O valor tem de ser positivo" }
        val now = clock()
        return transaction {
            ensureOpen(input.jobId)
            val id = newId()
            val rowId = db.cashDao().insert(
                CashMovementEntity(
                    id = id,
                    operationId = input.operationId,
                    date = input.date,
                    kind = CashKind.CLIENT_RECEIPT,
                    amountCents = input.amount.cents,
                    jobId = input.jobId,
                    method = input.method,
                    note = input.note?.takeIf { it.isNotBlank() },
                    createdAt = now,
                ),
            )
            if (rowId == -1L) return@transaction SaveOutcome.Duplicate
            audit("cash_movement", id, "create", "Recebimento ${input.amount.format()}")
            SaveOutcome.Saved
        }
    }

    /**
     * Grava uma marcação (uma reserva por dia). Os conflitos são verificados de novo dentro da
     * transação: se existirem e não houver motivo para manter a sobreposição, nada é gravado.
     * Uma marcação firme numa obra "Por confirmar" passa-a a "Agendada".
     */
    suspend fun createBooking(input: NewBooking, travelMarginMinutes: Long = 0): BookingOutcome {
        require(input.slots.isNotEmpty()) { "Escolha pelo menos um dia" }
        require(input.workerIds.isNotEmpty()) { "Escolha pelo menos uma pessoa" }
        val now = clock()
        val margin = Duration.ofMinutes(travelMarginMinutes)
        return transaction {
            val job = requireNotNull(db.jobDao().getById(input.jobId)) { "Obra não encontrada" }
            require(job.status != JobStatus.CANCELLED) { "A obra está cancelada" }
            ensureOpen(job.id)
            val from = input.slots.minOf { it.start } - margin
            val to = input.slots.maxOf { it.end } + margin
            // Exclui as reservas desta mesma operação: uma nova tentativa não conflitua consigo própria.
            val existing = db.scheduleDao().getRange(from.toEpochMilli(), to.toEpochMilli())
                .filter { it.entry.operationId != input.operationId }
                .toBookings()
            val unavailable = db.scheduleDao().getTimeOff(from.toEpochMilli(), to.toEpochMilli()).toUnavailabilities()
            val detector = ConflictDetector(margin)
            val conflicts = input.slots.flatMap { slot ->
                detector.conflictsFor(Booking("new", job.id, job.siteId, job.title, input.workerIds, slot, input.firmness), existing, unavailable)
            }
            if (conflicts.isNotEmpty() && input.overlapReason.isNullOrBlank()) {
                return@transaction BookingOutcome.Conflicts(conflicts)
            }

            var inserted = 0
            input.slots.forEach { slot ->
                val id = newId()
                val rowId = db.scheduleDao().insertEntryIgnoring(
                    ScheduleEntryEntity(
                        id = id,
                        operationId = input.operationId,
                        jobId = job.id,
                        startMs = slot.start.toEpochMilli(),
                        endMs = slot.end.toEpochMilli(),
                        firmness = input.firmness,
                        overlapReason = input.overlapReason?.takeIf { conflicts.isNotEmpty() },
                        note = input.note?.takeIf { it.isNotBlank() },
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (rowId != -1L) {
                    db.scheduleDao().insertAssignments(input.workerIds.map { ScheduleAssignmentEntity(id, it) })
                    inserted++
                }
            }
            if (inserted == 0) return@transaction BookingOutcome.Duplicate

            val becomesScheduled = input.firmness == BookingFirmness.FIRM && job.status == JobStatus.TO_CONFIRM
            if (becomesScheduled) {
                db.jobDao().update(job.copy(status = JobStatus.SCHEDULED, updatedAt = now))
            }
            val overlap = if (conflicts.isNotEmpty()) " · sobreposição mantida: ${input.overlapReason}" else ""
            audit("schedule", input.operationId, "create", "$inserted dia(s)$overlap")
            BookingOutcome.Saved(inserted, becomesScheduled)
        }
    }

    /**
     * Regista um documento de compra uma única vez e reparte-o pelas obras/despesas gerais.
     * Duplicados (mesmo emitente + tipo + número, ou mesmo ATCUD, ou repetição da operação) não são gravados.
     * Compra a crédito: conta como custo mas só afeta a caixa quando for paga.
     */
    suspend fun recordExpense(input: NewExpense): ExpenseOutcome {
        require(input.docType.createsExpense) { "Este tipo de documento não cria despesa" }
        require(input.gross.isPositive) { "O total tem de ser positivo" }
        require(input.lines.all { it.gross.isPositive }) { "As parcelas têm de ser positivas" }
        val now = clock()
        return transaction {
            db.purchaseDao().getByOperation(input.operationId)?.let { return@transaction ExpenseOutcome.Duplicate(it) }
            input.lines.map { it.jobId }.distinct().forEach { ensureOpen(it) }
            val nif = input.supplierNif?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
            val number = input.docNumber?.trim()?.takeIf { it.isNotEmpty() }
            val atcud = input.atcud?.trim()?.takeIf { it.isNotEmpty() }
            db.purchaseDao().findDuplicate(nif, input.docType, number, atcud)?.let { return@transaction ExpenseOutcome.Duplicate(it) }

            val amounts = Costs.signed(
                input.docType,
                input.vatRateBp?.let { Vat.splitGross(input.gross, it) } ?: DocumentAmounts(input.gross, Money.ZERO, input.gross),
            )
            val docId = newId()
            val rowId = db.purchaseDao().insert(
                PurchaseDocumentEntity(
                    id = docId,
                    operationId = input.operationId,
                    supplierName = input.supplierName?.trim()?.takeIf { it.isNotEmpty() },
                    supplierNif = nif,
                    docType = input.docType,
                    docNumber = number,
                    atcud = atcud,
                    docDate = input.docDate,
                    dueDate = input.dueDate,
                    category = input.category,
                    netCents = amounts.net.cents,
                    vatCents = amounts.vat.cents,
                    grossCents = amounts.gross.cents,
                    vatTreatment = input.vatTreatment,
                    originalDocumentId = input.originalDocumentId,
                    paidByWorkerId = input.paidByWorkerId,
                    rawQr = input.rawQr,
                    contentHash = input.rawQr?.let(::sha256),
                    note = input.note?.takeIf { it.isNotBlank() },
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (rowId == -1L) return@transaction ExpenseOutcome.Duplicate(null)

            val sign = input.docType.sign
            val split = Allocation.splitDocument(amounts, input.lines.map { if (sign < 0) -it.gross else it.gross })
            db.purchaseDao().insertAllocations(
                split.lines.mapIndexed { i, line ->
                    CostAllocationEntity(newId(), docId, input.lines[i].jobId, line.net.cents, line.vat.cents, line.gross.cents)
                },
            )
            // Uma nota de crédito não pressupõe devolução de dinheiro; só faturas podem ser pagas aqui.
            if (input.paidNow && sign > 0) {
                db.cashDao().insert(
                    CashMovementEntity(
                        id = newId(),
                        operationId = "${input.operationId}:pay",
                        date = input.paymentDate ?: input.docDate,
                        kind = CashKind.SUPPLIER_PAYMENT,
                        amountCents = input.gross.cents,
                        jobId = input.lines.singleOrNull()?.jobId,
                        method = input.paymentMethod,
                        counterparty = input.supplierName,
                        purchaseDocumentId = docId,
                        createdAt = now,
                    ),
                )
            }
            audit("purchase", docId, "create", "${input.docType} ${amounts.gross.format()}" + if (split.isComplete) "" else " · por classificar ${split.unallocated.format()}")
            ExpenseOutcome.Saved(docId, split.unallocated)
        }
    }

    /** Pagamento (total ou parcial) a um fornecedor de um documento já registado. */
    suspend fun paySupplier(operationId: String, documentId: String, amount: Money, date: LocalDate, method: PaymentMethod): SaveOutcome {
        require(amount.isPositive) { "O valor tem de ser positivo" }
        val now = clock()
        return transaction {
            val doc = requireNotNull(db.purchaseDao().getById(documentId)) { "Documento não encontrado" }
            val outstanding = Money(doc.grossCents - db.purchaseDao().paidCents(documentId))
            require(amount <= outstanding) { "O valor é superior ao que falta pagar (${outstanding.format()})" }
            val jobIds = db.purchaseDao().allocationsFor(documentId).map { it.jobId }.distinct()
            val rowId = db.cashDao().insert(
                CashMovementEntity(
                    id = newId(), operationId = operationId, date = date, kind = CashKind.SUPPLIER_PAYMENT,
                    amountCents = amount.cents, jobId = jobIds.singleOrNull(), method = method,
                    counterparty = doc.supplierName, purchaseDocumentId = documentId, createdAt = now,
                ),
            )
            if (rowId == -1L) return@transaction SaveOutcome.Duplicate
            audit("cash_movement", documentId, "supplier_payment", amount.format())
            SaveOutcome.Saved
        }
    }

    /** Muda o estado do trabalho segundo as regras do domínio; o estado de pagamento é independente. */
    suspend fun changeStatus(jobId: String, change: StatusChange): StatusOutcome {
        val now = clock()
        return transaction {
            ensureOpen(jobId)
            val job = requireNotNull(db.jobDao().getById(jobId)) { "Obra não encontrada" }
            val hasBooking = db.scheduleDao().firmCount(jobId) > 0
            when (val check = JobTransitions.validate(job.status, change, hasBooking)) {
                is TransitionResult.Rejected -> StatusOutcome.Rejected(check.message)
                TransitionResult.Allowed -> {
                    val onHold = change.target == JobStatus.ON_HOLD
                    db.jobDao().update(
                        job.copy(
                            status = change.target,
                            holdReason = if (onHold) change.holdReason else null,
                            holdNote = if (onHold) change.holdNote?.takeIf { it.isNotBlank() } else null,
                            holdReviewDate = if (onHold) change.reviewDate else null,
                            updatedAt = now,
                        ),
                    )
                    // Cancelar liberta as reservas futuras (sem as apagar do histórico).
                    val released = if (change.target == JobStatus.CANCELLED) db.scheduleDao().releaseFuture(jobId, now) else 0
                    audit("job", jobId, "status", "${job.status} → ${change.target}" + if (released > 0) " · $released reserva(s) libertada(s)" else "")
                    StatusOutcome.Changed(released)
                }
            }
        }
    }

    private suspend fun audit(entity: String, entityId: String, action: String, detail: String?) {
        db.auditDao().insert(AuditEventEntity(newId(), entity, entityId, action, detail, clock()))
    }

    /** Falha se a obra estiver encerrada (última revisão sem reabertura). */
    suspend fun ensureOpen(jobId: String?) {
        if (jobId == null) return
        val latest = db.closureDao().latest(jobId) ?: return
        if (latest.reopenedAt == null) throw JobClosedException()
    }

    /** Transação de escrita imediata: ou tudo é gravado, ou nada é. */
    suspend fun <T> transaction(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
