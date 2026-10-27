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
import pt.obraemdia.app.data.db.JobEntity
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.ScheduleRow
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.WorkSiteEntity
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Money
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

class ObraRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    val snapshot: Flow<Snapshot> = combine(
        db.workerDao().observeAll(),
        db.jobDao().observeAll(),
        db.timeEntryDao().observeAll(),
        db.cashDao().observeAll(),
        db.settingsDao().observeAll().map(Settings::toMap),
    ) { workers, jobs, entries, cash, settings -> Snapshot(workers, jobs, entries, cash, settings) }

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

    private suspend fun audit(entity: String, entityId: String, action: String, detail: String?) {
        db.auditDao().insert(AuditEventEntity(newId(), entity, entityId, action, detail, clock()))
    }

    /** Transação de escrita imediata: ou tudo é gravado, ou nada é. */
    suspend fun <T> transaction(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
